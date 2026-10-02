// SPDX-License-Identifier: GPL-3.0-or-later

//! librespot as a source of samples.
//!
//! This crate speaks the service's protocol and hands decoded PCM to Kotlin.
//! The queue, the transport rules and the rendering are Kotlin's; see
//! `docs/librespot-pack.md`.
//!
//! Nothing here waits while it holds the engine's lock, and nothing that can wait
//! on the network or on another thread runs on the caller's thread without a bound.
//! The callers are Kotlin's main thread and its IO threads.

use std::future::Future;
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::mpsc::{Receiver, SyncSender, TrySendError, sync_channel};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::{Duration, Instant};

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jfloat, jint, jlong, jstring};
use once_cell::sync::OnceCell;
use tokio::runtime::Runtime;
use tokio::task::JoinHandle;

use base64::Engine as _;
use librespot_core::authentication::Credentials;
use librespot_core::{
    FileId, cache::Cache, config::SessionConfig, session::Session, spotify_id::SpotifyId,
    spotify_uri::SpotifyUri,
};
use librespot_metadata::audio::AudioFileFormat;
use librespot_oauth::{DeviceAuthClient, DeviceAuthClientBuilder, DeviceAuthorization};
use librespot_playback::audio_backend::{Sink, SinkError, SinkResult};
use librespot_playback::config::{Bitrate, PlayerConfig};
use librespot_playback::convert::Converter;
use librespot_playback::decoder::AudioPacket;
use librespot_playback::mixer::{Mixer, MixerConfig, softmixer::SoftMixer};
use librespot_playback::player::{Player, PlayerEvent, PlayerEventChannel};
use librespot_protocol::context_page::ContextPage;
use librespot_protocol::extended_metadata::{BatchedEntityRequest, EntityRequest, ExtensionQuery};
use librespot_protocol::extension_kind::ExtensionKind;
use librespot_protocol::metadata::{Album, Artist, Track};
use librespot_protocol::playlist4_external::SelectedListContent;
use protobuf::{EnumOrUnknown, Message, MessageDyn, MessageFull};
use protobuf_json_mapping::ParseOptions;

/// The service's desktop client id, used by the session and by the device-pair
/// login.
///
/// The device-pair login flow is available only for the service's desktop, TV
/// and wearable client ids, so the credential is minted under this one.
/// `login5` refuses that credential unless the session uses the same id.
/// `app/third_party/patches/0001` makes the client-token request use it too.
const DESKTOP_CLIENT_ID: &str = "65b708073fc0480ea92a077233ca87bd";

/// What the pairing asks the listener's account for.
///
/// The service fails the whole request with `invalid_scope` when it does not
/// recognize one of these. The list is the one librespot's own binary asks for.
const SCOPES: &[&str] = &[
    "app-remote-control",
    "playlist-read",
    "playlist-read-collaborative",
    "playlist-read-private",
    "streaming",
    "user-follow-read",
    "user-library-read",
    "user-modify-playback-state",
    "user-read-currently-playing",
    "user-read-playback-state",
    "user-read-private",
];

/// How many packets may wait for Kotlin before the decoder is made to wait.
///
/// A full channel holds librespot's own thread in [KotlinSink::write], which
/// is the only backpressure. Sixteen packets is under a second of audio. What
/// is buffered here is heard after a pause and puts the reported position
/// ahead of what the listener hears, so it only covers the decoder being
/// scheduled late.
const PACKETS_AHEAD: usize = 16;

/// How many lines may wait for Kotlin before [say] drops one.
const EVENTS_AHEAD: usize = 256;

/// How long a connect may take, from the access point lookup to the welcome.
///
/// librespot bounds the TCP connect and nothing after it, so on a half-open
/// network (a captive portal, a hand-over from Wi-Fi) the lookup or the
/// authentication can wait without end.
const CONNECT_PATIENCE: Duration = Duration::from_secs(20);

/// How long one question about the library may take.
///
/// Every question waits on the network and none can be cancelled from Kotlin,
/// whose coroutine can stop waiting but cannot interrupt the thread blocked in
/// here. So the thread is given back after this, with an answer saying why.
const QUESTION_PATIENCE: Duration = Duration::from_secs(15);

/// How long a new session is given to say what kind of account it is.
///
/// The service says it in the packet after the welcome. An account that has
/// not said by then is let through: patch 0003 invalidates the session when
/// the answer does come, and the next open asks again.
const PRODUCT_PATIENCE: Duration = Duration::from_secs(5);

/// How long a read of the samples waits for a packet before answering 0.
const PCM_PATIENCE: Duration = Duration::from_millis(2000);

/// How often a sink with a full channel looks again.
///
/// The sink polls instead of blocking in a send, so a player that has been let
/// go of leaves [KotlinSink::write] and its thread ends even when Kotlin has
/// stopped reading.
const SINK_RETRY: Duration = Duration::from_millis(10);

/// A stereo frame of 16-bit samples. A read never splits one.
const BYTES_PER_FRAME: usize = 4;

const NO_SESSION: &str = "error no session";
const BAD_REQUEST: &str = "error bad request";
const NOT_PREMIUM: &str = "failed not premium";
const NOT_PREMIUM_PAIRING: &str = "pairing failed: this Spotify account is not Premium";

/// The file librespot keeps the credential in, inside a cache made with a
/// credentials directory and nothing else.
const CREDENTIALS_FILE: &str = "credentials.json";

/// A packet of samples, and which run of audio it belongs to; see [GENERATION].
///
/// Signed bytes, because that is what a Java array holds: a read copies
/// straight into the caller's array.
type Packet = (u64, Vec<i8>);

/// The samples on their way to Kotlin: one channel for the life of the process,
/// which every player writes into and the one render loop reads from.
struct Pcm {
    to_kotlin: SyncSender<Packet>,
    reader: Mutex<PcmReader>,
}

struct PcmReader {
    from_rust: Receiver<Packet>,
    /// The rest of a packet the last read had no room for: its generation, its
    /// bytes, and how far into them that read got.
    left: Option<(u64, Vec<i8>, usize)>,
}

/// One cell for both ends, so the sender and the receiver come from the same
/// channel when two first opens race.
static PCM: OnceCell<Pcm> = OnceCell::new();

/// Which run of audio is the one to hear.
///
/// A seek, a restart or a jump to another track makes everything already
/// decoded the wrong audio. Draining the channel would mean taking the lock
/// the reader holds while it waits, so every packet carries the generation it
/// was decoded in, a discard moves the generation on, and the reader drops
/// what is older.
///
/// A track reaching its own end does not move the generation: its last
/// packets are still in the channel and have to be heard. Only a discard or
/// letting go of a player does.
static GENERATION: AtomicU64 = AtomicU64::new(0);

/// A line the engine said, and the claim it was said under; see [CLAIM].
type Line = (u64, String);

/// What the engine has said and Kotlin has not yet read.
///
/// A queue, with no callback into the JVM: nothing attaches a thread or holds
/// a global reference, and an event emitted before anybody is listening waits
/// to be read.
struct Lines {
    to_kotlin: SyncSender<Line>,
    from_engine: Mutex<Receiver<Line>>,
}

static EVENTS: OnceCell<Lines> = OnceCell::new();

/// How many engines have claimed the queue.
///
/// The queue is the process's, and lines can be in it before an engine is
/// built: a library connect that failed with nobody playing, a tick from an
/// engine released at a sign-out. A new engine claims the queue, every line
/// carries the claim it was said under, and a read drops what was said before
/// the latest claim.
static CLAIM: AtomicU64 = AtomicU64::new(0);

/// The streaming quality the listener chose, in kbps: 96, 160 or 320.
///
/// Process-wide and read when a player is built, because librespot takes the
/// quality as part of a player's configuration and cannot change it on a
/// player that exists. A change takes effect at the next load, which builds a
/// player with it; see [nativeLoad].
static BITRATE_KBPS: AtomicU32 = AtomicU32::new(160);

/// A track to start, as the transport verbs describe it.
struct Load {
    track: SpotifyUri,
    start_playing: bool,
    position_ms: u32,
}

/// A player, and the flag that tells everything writing for it that it has
/// been let go of; see [let_go].
struct Playing {
    player: Arc<Player>,
    retired: Arc<AtomicBool>,
    /// The quality the player was built with, which is the one it fetches at
    /// for as long as it lives; see [BITRATE_KBPS].
    bitrate: Bitrate,
}

/// What the transport verbs, the questions and the open agree about, under one
/// lock.
///
/// One lock, because a load either finds the player or is found by the open
/// that installs one, and a close takes the session, the player and the
/// waiting load in the same step. It is never held across anything that can
/// wait (a join, a network round trip) and never while calling into Kotlin.
struct Engine {
    /// The connected session, kept so questions can be asked of it. `Session`
    /// is a handle, so this is the same session the player holds.
    session: Option<Session>,
    /// The player on [Engine::session]. Put in place together with the
    /// session and taken out with it.
    player: Option<Playing>,
    /// A load that arrived before there was a player to give it to.
    ///
    /// Opening is asynchronous, so the load waits here, the other transport
    /// verbs edit it while it waits, and [install] applies it.
    pending: Option<Load>,
    /// The open in flight, if any, by number.
    ///
    /// An ask for a session while one is connecting waits for it; a second
    /// connect would be a second device on the account. A close forgets the
    /// number, and the open that carries it then lets go of what it made.
    opening: Option<u64>,
    /// How many opens have been started, which numbers the next.
    opens: u64,
    /// Where the credential is, from the latest open, so a load or a question
    /// can open again after a connect failed.
    cache_dir: Option<String>,
    /// An account a connect found not to be Premium, by username, so asking
    /// again answers at once without connecting.
    refused: Option<String>,
}

static ENGINE: Mutex<Engine> = Mutex::new(Engine {
    session: None,
    player: None,
    pending: None,
    opening: None,
    opens: 0,
    cache_dir: None,
    refused: None,
});

/// One mixer for the life of the process.
///
/// It starts at full scale because whoever renders the samples applies the
/// listener's volume. Only [nativeSetVolume] moves it, and it is kept across
/// reconnections.
static MIXER: OnceCell<Arc<SoftMixer>> = OnceCell::new();

static RUNTIME: OnceCell<Runtime> = OnceCell::new();

/// Where the latest pairing has got to, which attempt that is, and the task
/// still working on it.
///
/// Separate from [ENGINE], so a session opening meanwhile cannot write into
/// the status a surface reads.
struct Pairing {
    attempt: u64,
    status: String,
    task: Option<JoinHandle<()>>,
}

static PAIRING: Mutex<Pairing> = Mutex::new(Pairing {
    attempt: 0,
    status: String::new(),
    task: None,
});

/// A lock that is taken whether or not a thread panicked while holding it, so
/// no `unwrap` is needed on the way into or out of JNI.
fn lock<T>(held: &Mutex<T>) -> MutexGuard<'_, T> {
    held.lock().unwrap_or_else(PoisonError::into_inner)
}

fn pcm() -> &'static Pcm {
    PCM.get_or_init(|| {
        let (to_kotlin, from_rust) = sync_channel(PACKETS_AHEAD);
        Pcm {
            to_kotlin,
            reader: Mutex::new(PcmReader {
                from_rust,
                left: None,
            }),
        }
    })
}

fn events() -> &'static Lines {
    EVENTS.get_or_init(|| {
        let (to_kotlin, from_engine) = sync_channel(EVENTS_AHEAD);
        Lines {
            to_kotlin,
            from_engine: Mutex::new(from_engine),
        }
    })
}

fn runtime() -> Option<&'static Runtime> {
    RUNTIME
        .get_or_try_init(|| {
            tokio::runtime::Builder::new_multi_thread()
                .enable_all()
                .build()
        })
        .map_err(|why| log::error!("no runtime: {why}"))
        .ok()
}

fn mixer() -> Result<&'static Arc<SoftMixer>, librespot_core::Error> {
    MIXER.get_or_try_init(|| -> Result<Arc<SoftMixer>, librespot_core::Error> {
        let made = SoftMixer::open(MixerConfig::default())?;
        made.set_volume(u16::MAX);
        Ok(Arc::new(made))
    })
}

fn init_logging() {
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info),
    );
}

/// Hands one line to Kotlin.
///
/// A full queue drops the line, whatever it says, so the engine's own thread
/// never blocks here.
fn say(line: impl Into<String>) {
    let line = line.into();
    log::info!("event: {line}");
    let _ = events()
        .to_kotlin
        .try_send((CLAIM.load(Ordering::SeqCst), line));
}

/// Turns a Rust string into a Java one. Null when that fails.
fn into_jstring(env: &JNIEnv, text: &str) -> jstring {
    env.new_string(text)
        .map(|made| made.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// A message as the service's own JSON, or the reason it could not be printed.
fn printed(message: &dyn MessageDyn) -> String {
    protobuf_json_mapping::print_to_string(message)
        .unwrap_or_else(|why| format!("error could not read the answer: {why}"))
}

/// A session configuration with the desktop client id; see [DESKTOP_CLIENT_ID].
fn desktop_config() -> SessionConfig {
    let mut config = SessionConfig::default();
    config.client_id = DESKTOP_CLIENT_ID.to_string();
    config
}

/// The quality to build the next player with; see [BITRATE_KBPS].
fn chosen_bitrate() -> Bitrate {
    bitrate_of(BITRATE_KBPS.load(Ordering::SeqCst))
}

/// A number of kbps as librespot's name for it. Anything but its three
/// qualities is its default, which is also what the setting starts at.
fn bitrate_of(kbps: u32) -> Bitrate {
    match kbps {
        96 => Bitrate::Bitrate96,
        320 => Bitrate::Bitrate320,
        _ => Bitrate::Bitrate160,
    }
}

/// The files librespot looks for at [bitrate], best first.
///
/// The order is copied from `PlayerTrackLoader::load_track` in the pinned
/// player and kept in step by hand: the player's event lists every file a
/// track has and does not say which one it picked.
fn preference(bitrate: Bitrate) -> [AudioFileFormat; 7] {
    use AudioFileFormat::*;
    match bitrate {
        Bitrate::Bitrate96 => [
            OGG_VORBIS_96,
            MP3_96,
            OGG_VORBIS_160,
            MP3_160,
            MP3_256,
            OGG_VORBIS_320,
            MP3_320,
        ],
        Bitrate::Bitrate160 => [
            OGG_VORBIS_160,
            MP3_160,
            OGG_VORBIS_96,
            MP3_96,
            MP3_256,
            OGG_VORBIS_320,
            MP3_320,
        ],
        Bitrate::Bitrate320 => [
            OGG_VORBIS_320,
            MP3_320,
            MP3_256,
            OGG_VORBIS_160,
            MP3_160,
            OGG_VORBIS_96,
            MP3_96,
        ],
    }
}

/// The kbps of the file a player built at [bitrate] plays, given which files
/// a track [has]; `None` when it has none of them, which librespot refuses.
///
/// A track does not always have the quality asked for, so this is the file
/// librespot falls back to.
fn picked_kbps(bitrate: Bitrate, has: impl Fn(AudioFileFormat) -> bool) -> Option<u32> {
    preference(bitrate)
        .into_iter()
        .find(|format| has(*format))
        .map(|format| match format {
            AudioFileFormat::OGG_VORBIS_96 | AudioFileFormat::MP3_96 => 96,
            AudioFileFormat::MP3_256 => 256,
            AudioFileFormat::OGG_VORBIS_320 | AudioFileFormat::MP3_320 => 320,
            _ => 160,
        })
}

/// Sets the quality the next player is built with: 96, 160 or 320 kbps, and
/// anything else is 160.
///
/// The player there is keeps its quality. The next load finds it built at
/// another quality and opens one at this; see [nativeLoad].
#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_Librespot_nativeSetBitrate(
    _env: JNIEnv,
    _class: JClass,
    kbps: jint,
) {
    let kbps = match kbps {
        96 | 320 => kbps as u32,
        _ => 160,
    };
    BITRATE_KBPS.store(kbps, Ordering::SeqCst);
}

fn player_config(bitrate: Bitrate) -> PlayerConfig {
    let mut config = PlayerConfig::default();
    config.bitrate = bitrate;
    // off by default; without it the position never ticks
    config.position_update_interval = Some(Duration::from_millis(250));
    config
}

/// The credential in [cache_dir]: none, or one naming whoever it belongs to.
fn stored_credential(cache_dir: &str) -> Option<Credentials> {
    Cache::new(Some(cache_dir), None, None, None)
        .ok()?
        .credentials()
}

/// Whose account the credential in a cache is, as far as can be told.
enum Owner {
    /// There is no credential: signed out.
    Nobody,
    /// There is one, and it cannot be read just now or names nobody.
    Unknown,
    Named(String),
}

/// Who the credential in [cache_dir] belongs to.
///
/// A file that exists but cannot be read is [Owner::Unknown] and does not
/// count as a sign-out: `LibrespotAccount.signedIn` removes every permission
/// from the file for a moment while it restricts it to the owner.
fn stored_owner(cache_dir: &str) -> Owner {
    if !Path::new(cache_dir).join(CREDENTIALS_FILE).exists() {
        return Owner::Nobody;
    }
    match stored_credential(cache_dir).and_then(|credential| credential.username) {
        Some(name) => Owner::Named(name),
        None => Owner::Unknown,
    }
}

/// The sink librespot writes into, which is a channel to Kotlin.
///
/// It converts the decoder's f64 samples to 16-bit little endian.
struct KotlinSink {
    to_kotlin: SyncSender<Packet>,
    /// Set when the player writing here has been let go of; see [let_go].
    retired: Arc<AtomicBool>,
}

impl Sink for KotlinSink {
    fn start(&mut self) -> SinkResult<()> {
        log::info!("sink started");
        Ok(())
    }

    /// Always `Ok`. `Player::ensure_sink_stopped` treats an error here as
    /// unrecoverable and calls `exit(1)`. Errors from `start` and `write` are
    /// safe; they pause.
    fn stop(&mut self) -> SinkResult<()> {
        log::info!("sink stopped");
        Ok(())
    }

    fn write(&mut self, packet: AudioPacket, converter: &mut Converter) -> SinkResult<()> {
        let samples = packet
            .samples()
            .map_err(|_| SinkError::OnWrite("no samples in packet".into()))?;
        let pcm = converter.f64_to_s16(samples);
        let mut bytes: Vec<i8> = Vec::with_capacity(pcm.len() * 2);
        for sample in pcm {
            let [low, high] = sample.to_le_bytes();
            bytes.push(low as i8);
            bytes.push(high as i8);
        }
        // the generation this was decoded in: a discard while it waits for
        // room makes it old audio
        let mut waiting = (GENERATION.load(Ordering::SeqCst), bytes);
        loop {
            if self.retired.load(Ordering::SeqCst) {
                // an error here pauses the player, which lets its thread see
                // that it has been let go of and end
                return Err(SinkError::OnWrite("this player has been let go of".into()));
            }
            match self.to_kotlin.try_send(waiting) {
                Ok(()) => return Ok(()),
                Err(TrySendError::Full(back)) => {
                    waiting = back;
                    std::thread::sleep(SINK_RETRY);
                }
                Err(TrySendError::Disconnected(_)) => {
                    return Err(SinkError::NotConnected(
                        "nobody is reading the samples".into(),
                    ));
                }
            }
        }
    }
}

/// Lets go of a session and the player on it, without waiting for either.
///
/// The player is told first: its sink stops writing, what it decoded stops
/// being the audio to hear, and its remaining events are not forwarded.
/// Dropping the last handle to a player joins librespot's thread for it, so
/// the drop happens on the runtime's blocking pool, never on the caller's
/// thread and never under a lock.
fn let_go(session: Option<Session>, playing: Option<Playing>) {
    if let Some(playing) = playing {
        playing.retired.store(true, Ordering::SeqCst);
        GENERATION.fetch_add(1, Ordering::SeqCst);
        playing.player.stop();
        match RUNTIME.get() {
            Some(runtime) => {
                let _ = runtime.spawn_blocking(move || drop(playing));
            }
            None => {
                let _ = std::thread::Builder::new()
                    .name("andamp-let-go".into())
                    .spawn(move || drop(playing));
            }
        }
    }
    if let Some(session) = session {
        session.shutdown();
    }
}

/// Whether this device already has a credential of its own.
#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_LibrespotPairing_nativeIsPaired(
    mut env: JNIEnv,
    _class: JClass,
    cache_dir: JString,
) -> jboolean {
    let Ok(cache_dir) = env.get_string(&cache_dir).map(String::from) else {
        return JNI_FALSE;
    };
    if stored_credential(&cache_dir).is_some() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Starts a new pairing attempt: an earlier one stops, and nothing it says from
/// now on reaches the status a surface reads.
fn begin_attempt() -> u64 {
    let mut pairing = lock(&PAIRING);
    if let Some(earlier) = pairing.task.take() {
        // its code could still be approved, and would then sign this device
        // in as whoever approved it - long after the listener moved on
        earlier.abort();
    }
    pairing.attempt += 1;
    pairing.status = "pairing started".into();
    pairing.attempt
}

/// Says where [attempt] has got to, unless a newer one has started.
///
/// Every way a pairing can end badly starts `pairing failed: `, which is the
/// prefix Kotlin's `Pairing.progress` reads as the end of the wait.
fn pairing_says(attempt: u64, status: impl Into<String>) {
    let status = status.into();
    log::info!("pairing {attempt}: {status}");
    let mut pairing = lock(&PAIRING);
    if pairing.attempt == attempt {
        pairing.status = status;
    }
}

/// Starts the device-pair login and returns what the listener has to be shown:
/// the code and the page to enter it on, one per line, or an empty string when
/// the flow could not be started.
///
/// The login uses [DESKTOP_CLIENT_ID], so the credential it ends in is minted
/// under the client id the session uses.
#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_LibrespotPairing_nativeBeginPairing(
    mut env: JNIEnv,
    _class: JClass,
    cache_dir: JString,
) -> jstring {
    init_logging();
    let attempt = begin_attempt();
    let Ok(cache_dir) = env.get_string(&cache_dir).map(String::from) else {
        pairing_says(attempt, "pairing failed: no cache directory");
        return into_jstring(&env, "");
    };
    let Some(runtime) = runtime() else {
        pairing_says(attempt, "pairing failed: no runtime");
        return into_jstring(&env, "");
    };
    let client = match DeviceAuthClientBuilder::new(DESKTOP_CLIENT_ID, SCOPES.to_vec()).build() {
        Ok(client) => client,
        Err(why) => {
            pairing_says(attempt, format!("pairing failed: could not start: {why}"));
            return into_jstring(&env, "");
        }
    };
    let requested = runtime.block_on(async {
        tokio::time::timeout(QUESTION_PATIENCE, client.request_device_code_async()).await
    });
    let pending = match requested {
        Ok(Ok(pending)) => pending,
        Ok(Err(why)) => {
            pairing_says(attempt, format!("pairing failed: could not start: {why}"));
            return into_jstring(&env, "");
        }
        Err(_) => {
            pairing_says(attempt, "pairing failed: could not start: timed out");
            return into_jstring(&env, "");
        }
    };
    let shown = format!("{}\n{}", pending.user_code(), pending.url());
    pairing_says(attempt, "waiting for the pairing to be approved");

    // poll_for_token_async waits for the approval itself, so this is one task
    // that ends in a stored credential and Kotlin has nothing to poll
    let task = runtime.spawn(pair(attempt, client, pending, cache_dir));
    let mut pairing = lock(&PAIRING);
    if pairing.attempt == attempt {
        pairing.task = Some(task);
    } else {
        task.abort();
    }
    drop(pairing);

    into_jstring(&env, &shown)
}

/// The rest of a pairing, once the listener has the code: the approval, the
/// credential, and whether the account it belongs to can play.
async fn pair(
    attempt: u64,
    client: DeviceAuthClient,
    pending: DeviceAuthorization,
    cache_dir: String,
) {
    let token = match client.poll_for_token_async(&pending).await {
        Ok(token) => token,
        Err(why) => return pairing_says(attempt, format!("pairing failed: {why}")),
    };
    let cache = match Cache::new(Some(cache_dir.as_str()), None, None, None) {
        Ok(cache) => cache,
        Err(why) => return pairing_says(attempt, format!("pairing failed: no cache: {why}")),
    };
    let session = Session::new(desktop_config(), Some(cache));
    // true: store the credential, which is what a pairing is for
    let connecting = session.connect(Credentials::with_access_token(token.access_token), true);
    let failed = match tokio::time::timeout(CONNECT_PATIENCE, connecting).await {
        Ok(Ok(())) => None,
        Ok(Err(why)) => Some(format!("pairing failed: could not connect: {why}")),
        Err(_) => Some("pairing failed: could not connect: timed out".to_string()),
    };
    if let Some(failed) = failed {
        session.shutdown();
        return pairing_says(attempt, failed);
    }
    let tier = tier(&session).await;
    session.shutdown();
    match tier {
        Tier::Other(kind) => {
            // the connect stored the credential already; one that cannot play
            // is removed, so the settings page does not show a signed-in
            // account that plays nothing
            log::info!("a {kind} account paired, and cannot play");
            let _ = std::fs::remove_file(Path::new(&cache_dir).join(CREDENTIALS_FILE));
            pairing_says(attempt, NOT_PREMIUM_PAIRING);
        }
        Tier::Premium | Tier::Unknown => pairing_says(attempt, "paired"),
    }
}

/// The last thing a pairing said, or "not started".
#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_LibrespotPairing_nativeStatus(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let status = {
        let pairing = lock(&PAIRING);
        if pairing.status.is_empty() {
            "not started".to_string()
        } else {
            pairing.status.clone()
        }
    };
    into_jstring(&env, &status)
}

/// The pinned librespot, so a device test can check the library it loaded is
/// the one this repository says it builds.
#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_Librespot_nativeVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    into_jstring(&env, librespot_core::version::SEMVER)
}

/// What kind of account a connected session belongs to, as far as the service has
/// said.
enum Tier {
    Premium,
    Other(String),
    Unknown,
}

/// Asks a connected session what kind of account it is.
///
/// With patch 0003 librespot invalidates the session of an account that is
/// not Premium and keeps the attributes the service sent; this reads them.
async fn tier(session: &Session) -> Tier {
    let until = Instant::now() + PRODUCT_PATIENCE;
    loop {
        match session.get_user_attribute("type") {
            Some(kind) if kind == "premium" => return Tier::Premium,
            Some(kind) => return Tier::Other(kind),
            None if session.is_invalid() || Instant::now() >= until => return Tier::Unknown,
            None => tokio::time::sleep(Duration::from_millis(50)).await,
        }
    }
}

/// Opens a session from the stored credential, ready to be told what to play.
///
/// Fire and forget, like every verb on the playback contract: what happened
/// arrives as an event.
#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_Librespot_nativeOpen(
    mut env: JNIEnv,
    _class: JClass,
    cache_dir: JString,
) -> jboolean {
    init_logging();
    let Ok(cache_dir) = env.get_string(&cache_dir).map(String::from) else {
        return JNI_FALSE;
    };
    if runtime().is_none() {
        return JNI_FALSE;
    }
    lock(&ENGINE).cache_dir = Some(cache_dir.clone());
    ensure_open(cache_dir);
    JNI_TRUE
}

/// Takes the account off this process: the player stops and goes, the session
/// is shut down, and nothing is left that could open another.
///
/// The waiting load and the credential's location are forgotten too, so a
/// question asked after this answers that there is no session.
///
/// Never waits. What has to be joined is joined on the runtime; see [let_go].
#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_Librespot_nativeClose(
    _env: JNIEnv,
    _class: JClass,
) {
    let (session, playing) = {
        let mut engine = lock(&ENGINE);
        engine.pending = None;
        engine.cache_dir = None;
        engine.refused = None;
        // an open still connecting finds its number forgotten, and lets go of
        // what it made
        engine.opening = None;
        (engine.session.take(), engine.player.take())
    };
    let_go(session, playing);
    log::info!("closed");
}

/// A session and a player on it, unless there already is one or one is coming.
///
/// The library and the player both ask, and whichever comes first makes the
/// session both use: a second connect would be a second device on the
/// account. A connect still in flight counts; see [Engine::opening].
///
/// An invalid session is replaced, because librespot does not reconnect one
/// and it fails every later request.
///
/// So is a session that is not the stored credential's: signed out, or signed
/// in since as somebody else.
fn ensure_open(cache_dir: String) {
    let Some(runtime) = RUNTIME.get() else {
        return;
    };
    // read before the lock is taken: it is a file
    let owner = stored_owner(&cache_dir);
    let (old, next) = {
        let mut engine = lock(&ENGINE);
        let keep = engine.session.as_ref().is_some_and(|session| {
            !session.is_invalid()
                && match &owner {
                    Owner::Nobody => false,
                    // what cannot be read cannot name somebody else
                    Owner::Unknown => true,
                    Owner::Named(name) => name.eq_ignore_ascii_case(&session.username()),
                }
        });
        if keep {
            return;
        }
        let refused = match &owner {
            Owner::Named(name) => engine.refused.as_deref() == Some(name.as_str()),
            Owner::Nobody | Owner::Unknown => false,
        };
        let old = (engine.session.take(), engine.player.take());
        let next = if engine.opening.is_some() {
            Next::Wait
        } else if refused {
            // only a load that was waiting is told: a verb or a question
            // asking for a session gets its own answer, that there is none
            Next::Refuse(engine.pending.take().is_some())
        } else {
            engine.opens += 1;
            engine.opening = Some(engine.opens);
            Next::Open(engine.opens)
        };
        (old, next)
    };
    let_go(old.0, old.1);
    match next {
        Next::Wait => {}
        Next::Refuse(asked) => {
            if asked {
                say(NOT_PREMIUM);
            }
        }
        Next::Open(id) => {
            runtime.spawn(open(id, cache_dir));
        }
    }
}

/// What [ensure_open] decided under its lock and does after letting go of it.
enum Next {
    Wait,
    Refuse(bool),
    Open(u64),
}

/// Connects, checks the account can play, and puts a player on the session.
async fn open(id: u64, cache_dir: String) {
    let cache = match Cache::new(Some(cache_dir.as_str()), None, None, None) {
        Ok(cache) => cache,
        Err(why) => return gave_up(id, format!("failed no cache: {why}"), None),
    };
    let Some(credentials) = cache.credentials() else {
        return gave_up(id, "failed not signed in".into(), None);
    };
    let owner = credentials.username.clone();

    let session = Session::new(desktop_config(), Some(cache));
    let connecting = session.connect(credentials, false);
    let failed = match tokio::time::timeout(CONNECT_PATIENCE, connecting).await {
        Ok(Ok(())) => None,
        Ok(Err(why)) => Some(format!("failed could not connect: {why}")),
        Err(_) => Some("failed could not connect: timed out".to_string()),
    };
    if let Some(failed) = failed {
        session.shutdown();
        return gave_up(id, failed, None);
    }
    if let Tier::Other(kind) = tier(&session).await {
        log::info!("a {kind} account cannot play");
        session.shutdown();
        return gave_up(id, NOT_PREMIUM.into(), owner);
    }
    if session.is_invalid() {
        return gave_up(
            id,
            "failed could not connect: the connection closed".into(),
            None,
        );
    }
    log::info!("connected");

    let mixer = match mixer() {
        Ok(mixer) => mixer,
        Err(why) => {
            session.shutdown();
            return gave_up(id, format!("failed no mixer: {why}"), None);
        }
    };
    let retired = Arc::new(AtomicBool::new(false));
    let sink = KotlinSink {
        to_kotlin: pcm().to_kotlin.clone(),
        retired: retired.clone(),
    };
    // read once, so the player, what it is remembered as and what its track
    // changes are read against cannot be three different settings
    let bitrate = chosen_bitrate();
    let player = Player::new(
        player_config(bitrate),
        session.clone(),
        mixer.get_soft_volume(),
        move || Box::new(sink) as Box<dyn Sink>,
    );
    let events = player.get_player_event_channel();
    let playing = Playing {
        player,
        retired: retired.clone(),
        bitrate,
    };
    if let Some((session, playing)) = install(id, session, playing) {
        // closed while this was connecting: nobody wants this session
        return let_go(Some(session), Some(playing));
    }
    forward(events, retired, bitrate).await;
}

/// Puts a connected session and its player in place, and plays whatever was
/// asked for meanwhile.
///
/// Only for the open that is still wanted: one that [nativeClose] superseded
/// hands its session back to be let go of.
fn install(id: u64, session: Session, playing: Playing) -> Option<(Session, Playing)> {
    let replaced = {
        let mut engine = lock(&ENGINE);
        if engine.opening != Some(id) {
            return Some((session, playing));
        }
        engine.opening = None;
        // whatever was asked for while this was still opening, as the verbs
        // since have left it
        if let Some(wanted) = engine.pending.take() {
            playing
                .player
                .load(wanted.track, wanted.start_playing, wanted.position_ms);
        }
        (
            engine.session.replace(session),
            engine.player.replace(playing),
        )
    };
    let_go(replaced.0, replaced.1);
    None
}

/// Every way out of an open before its player exists: says why, and lets the
/// next ask try again.
///
/// The waiting load is dropped, so a later open, the library's included, does
/// not start a track after the listener was told it failed. [refusing] is an
/// account found not to be Premium, remembered so the next ask does not
/// connect again.
fn gave_up(id: u64, why: String, refusing: Option<String>) {
    let current = {
        let mut engine = lock(&ENGINE);
        let current = engine.opening == Some(id);
        if current {
            engine.opening = None;
            engine.pending = None;
            if refusing.is_some() {
                engine.refused = refusing;
            }
        }
        current
    };
    if current {
        say(why);
    } else {
        log::info!("an open nobody wants any more ended: {why}");
    }
}

/// What the player says, as lines, until it is gone.
///
/// [bitrate] is the quality the player was built with, which is what decides
/// the file each track plays from.
async fn forward(mut events: PlayerEventChannel, retired: Arc<AtomicBool>, bitrate: Bitrate) {
    while let Some(event) = events.recv().await {
        // a player that has been let go of says nothing more: its remaining
        // events are about a track nobody is listening to
        if retired.load(Ordering::SeqCst) {
            continue;
        }
        match event {
            PlayerEvent::Playing { position_ms, .. } => say(format!("playing {position_ms}")),
            PlayerEvent::Paused { position_ms, .. } => say(format!("paused {position_ms}")),
            PlayerEvent::PositionChanged { position_ms, .. } => {
                say(format!("position {position_ms}"))
            }
            PlayerEvent::Seeked { position_ms, .. } => say(format!("position {position_ms}")),
            // a track that cannot be decrypted and one that is not available
            // both arrive as this; the engine cannot tell them apart
            PlayerEvent::Unavailable { track_id, .. } => {
                say(format!("unavailable {}", track_id.to_uri()))
            }
            PlayerEvent::EndOfTrack { .. } => say("endOfTrack"),
            // said as the track starts, once its file is open: the file is
            // not in the event, so it is found the way the player found it
            PlayerEvent::TrackChanged { audio_item } => {
                if let Some(kbps) =
                    picked_kbps(bitrate, |format| audio_item.files.contains_key(&format))
                {
                    say(format!("bitrate {kbps}"));
                }
            }
            other => log::debug!("{other:?}"),
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_Librespot_nativeLoad(
    mut env: JNIEnv,
    _class: JClass,
    track_uri: JString,
    start_playing: jboolean,
    position_ms: jlong,
) {
    let Ok(track_uri) = env.get_string(&track_uri).map(String::from) else {
        return;
    };
    let Ok(track) = SpotifyUri::from_uri(&track_uri) else {
        return say("failed not a track uri");
    };
    let wanted = Load {
        track,
        start_playing: start_playing != JNI_FALSE,
        position_ms: position_ms.clamp(0, jlong::from(u32::MAX)) as u32,
    };
    // No player means a session that is still opening, or one whose connect
    // failed. The load asks for an open itself; a connect already in flight
    // is waited for. The decision is made under the lock the open installs
    // its player under, so a load cannot land just after the open looked for
    // one.
    //
    // A player built at another quality than the one chosen is let go of,
    // with its session, because librespot cannot change a player's quality.
    // This load then waits for the open that builds the next one. Only a load
    // does this, so the setting never cuts off the song already playing.
    let (cache_dir, outdated) = {
        let mut engine = lock(&ENGINE);
        let chosen = chosen_bitrate();
        let reusable = engine
            .player
            .as_ref()
            .map(|playing| playing.bitrate == chosen);
        let outdated = match reusable {
            // with nowhere to open another from, the existing player is used
            Some(same) if same || engine.cache_dir.is_none() => {
                if let Some(playing) = engine.player.as_ref() {
                    playing
                        .player
                        .load(wanted.track, wanted.start_playing, wanted.position_ms);
                }
                return;
            }
            Some(_) => Some((engine.session.take(), engine.player.take())),
            None => None,
        };
        engine.pending = Some(wanted);
        (engine.cache_dir.clone(), outdated)
    };
    if let Some((session, playing)) = outdated {
        log::info!("the streaming quality changed: opening a player at the new one");
        let_go(session, playing);
    }
    match cache_dir {
        Some(cache_dir) => ensure_open(cache_dir),
        // nothing has said where the credential is, or a close has forgotten
        // it: there is nothing to open
        None => {
            lock(&ENGINE).pending = None;
            say("failed not open");
        }
    }
}

/// A transport verb: on the player if there is one, and on the load waiting
/// for one if not.
///
/// With neither, nothing happens and nothing is said: a stop with nothing
/// playing is not a failure.
///
/// A stop, a pause or a seek while the session is opening edits the waiting
/// load, so it starts as the verbs since have left it.
fn transport(on_player: impl FnOnce(&Player), on_waiting: impl FnOnce(&mut Option<Load>)) {
    let mut held = lock(&ENGINE);
    let engine = &mut *held;
    match engine.player.as_ref() {
        Some(playing) => on_player(playing.player.as_ref()),
        None => on_waiting(&mut engine.pending),
    }
}

#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_Librespot_nativePlay(
    _env: JNIEnv,
    _class: JClass,
) {
    transport(
        |player| player.play(),
        |waiting| {
            if let Some(load) = waiting {
                load.start_playing = true;
            }
        },
    );
}

#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_Librespot_nativePause(
    _env: JNIEnv,
    _class: JClass,
) {
    transport(
        |player| player.pause(),
        |waiting| {
            if let Some(load) = waiting {
                load.start_playing = false;
            }
        },
    );
}

#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_Librespot_nativeStop(
    _env: JNIEnv,
    _class: JClass,
) {
    transport(|player| player.stop(), |waiting| *waiting = None);
}

#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_Librespot_nativeSeek(
    _env: JNIEnv,
    _class: JClass,
    position_ms: jlong,
) {
    let position_ms = position_ms.clamp(0, jlong::from(u32::MAX)) as u32;
    transport(
        |player| player.seek(position_ms),
        |waiting| {
            if let Some(load) = waiting {
                load.position_ms = position_ms;
            }
        },
    );
}

/// 0..1 onto the mixer's own scale, which is the full range of a u16.
#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_Librespot_nativeSetVolume(
    _env: JNIEnv,
    _class: JClass,
    fraction: jfloat,
) {
    if let Ok(mixer) = mixer() {
        let level = fraction.clamp(0.0, 1.0) * f32::from(u16::MAX);
        mixer.set_volume(level as u16);
    }
}

/// A new engine is reading: whatever was said before now is not for it.
/// Never blocks; see [CLAIM].
#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_Librespot_nativeClaimEvents(
    _env: JNIEnv,
    _class: JClass,
) {
    CLAIM.fetch_add(1, Ordering::SeqCst);
}

/// One line the engine had to say since the latest claim, or an empty string
/// when it had nothing within [timeout_ms].
#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_Librespot_nativeNextEvent(
    env: JNIEnv,
    _class: JClass,
    timeout_ms: jlong,
) -> jstring {
    let heard = {
        let from_engine = lock(&events().from_engine);
        // bounded, so no caller's number can overflow the deadline into a panic
        let wait = Duration::from_millis(timeout_ms.clamp(0, 60_000) as u64);
        let until = Instant::now() + wait;
        loop {
            let left = until.saturating_duration_since(Instant::now());
            match from_engine.recv_timeout(left) {
                // said before the reading engine claimed the queue: about a
                // track, a session or a failure that is not this engine's
                Ok((claim, _)) if claim < CLAIM.load(Ordering::SeqCst) => continue,
                Ok((_, line)) => break line,
                Err(_) => break String::new(),
            }
        }
    };
    into_jstring(&env, &heard)
}

/// Everything decoded so far is not to be heard: a seek, a restart, another
/// track. Never blocks; see [GENERATION].
#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_LibrespotPcm_nativeDiscardPcm(
    _env: JNIEnv,
    _class: JClass,
) {
    GENERATION.fetch_add(1, Ordering::SeqCst);
}

impl PcmReader {
    /// The next audio to hear: the rest of a packet an earlier read had no
    /// room for, or a fresh one, waiting up to [wait] for it.
    fn next(&mut self, wait: Duration) -> Option<(u64, Vec<i8>, usize)> {
        if let Some(left) = self.left.take() {
            if left.0 == GENERATION.load(Ordering::SeqCst) {
                return Some(left);
            }
        }
        let until = Instant::now() + wait;
        loop {
            let remaining = until.saturating_duration_since(Instant::now());
            match self.from_rust.recv_timeout(remaining) {
                // decoded before the last discard: audio nobody should hear
                Ok((generation, _)) if generation != GENERATION.load(Ordering::SeqCst) => continue,
                Ok((generation, packet)) => return Some((generation, packet, 0)),
                Err(_) => return None,
            }
        }
    }
}

/// Blocking pull: bytes written, 0 when nothing arrived in time, -1 only when
/// the bytes could not be copied into the array. 44.1 kHz stereo, 16-bit little
/// endian, always.
///
/// A packet bigger than the array is served over as many reads as it takes.
#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_LibrespotPcm_nativeReadPcm(
    env: JNIEnv,
    _class: JClass,
    into: JByteArray,
) -> jint {
    let room = env.get_array_length(&into).unwrap_or(0).max(0) as usize;
    let whole = room - room % BYTES_PER_FRAME;
    if whole == 0 {
        return 0;
    }
    let mut reader = lock(&pcm().reader);
    let Some((generation, packet, from)) = reader.next(PCM_PATIENCE) else {
        return 0;
    };
    let taken = (packet.len() - from).min(whole);
    if env
        .set_byte_array_region(&into, 0, &packet[from..from + taken])
        .is_err()
    {
        return -1;
    }
    if from + taken < packet.len() {
        reader.left = Some((generation, packet, from + taken));
    }
    taken as jint
}

/// The session a question can be asked of, or `None` having asked for one.
///
/// Without a live session the question is answered at once, and an open is
/// asked for when the credential's location is known. The caller decides how
/// long to wait before asking again. An invalid session counts as none.
fn connected() -> Option<(&'static Runtime, Session)> {
    let (live, cache_dir) = {
        let engine = lock(&ENGINE);
        let live = engine
            .session
            .as_ref()
            .filter(|session| !session.is_invalid())
            .cloned();
        (live, engine.cache_dir.clone())
    };
    match (RUNTIME.get(), live) {
        (Some(runtime), Some(session)) => Some((runtime, session)),
        _ => {
            if let Some(cache_dir) = cache_dir {
                ensure_open(cache_dir);
            }
            None
        }
    }
}

/// Answers one question, or says it took too long; see [QUESTION_PATIENCE].
fn ask(runtime: &Runtime, question: impl Future<Output = String>) -> String {
    runtime.block_on(async {
        tokio::time::timeout(QUESTION_PATIENCE, question)
            .await
            .unwrap_or_else(|_| "error timed out".to_string())
    })
}

/// One question about the listener's library, answered as the service's own JSON.
///
/// A passthrough: the `Context` protobuf the service's context-resolve
/// endpoint answers with, printed as JSON and handed over whole. Kotlin reads
/// it, where the parsers are tested against recorded answers.
///
/// The uri is the service's own, including the two that are not ids:
/// `spotify:user:<id>:collection` is Liked Songs and `spotify:search:<terms>`
/// is a search. A failure is a line beginning `error `.
///
/// A long context holds only its first page and the url of the next;
/// [nativeNextPage] follows those one at a time.
#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_LibrespotQueries_nativeBrowse(
    mut env: JNIEnv,
    _class: JClass,
    uri: JString,
) -> jstring {
    let Ok(uri) = env.get_string(&uri).map(String::from) else {
        return into_jstring(&env, BAD_REQUEST);
    };
    let Some((runtime, session)) = connected() else {
        return into_jstring(&env, NO_SESSION);
    };
    let answer = ask(runtime, async move {
        match session.spclient().get_context(&uri).await {
            Ok(context) => printed(&context),
            Err(why) => format!("error {why}"),
        }
    });
    into_jstring(&env, &answer)
}

/// The page after one a context answered with, as the service's own JSON.
///
/// [url] is the next-page url a context or a page named. What comes back is a
/// `ContextPage`, printed the way [nativeBrowse] prints a `Context`. A failure
/// is a line beginning `error `.
#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_LibrespotQueries_nativeNextPage(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
) -> jstring {
    let Ok(url) = env.get_string(&url).map(String::from) else {
        return into_jstring(&env, BAD_REQUEST);
    };
    let Some((runtime, session)) = connected() else {
        return into_jstring(&env, NO_SESSION);
    };
    let answer = ask(runtime, async move {
        let bytes = match session.spclient().get_next_page(&url).await {
            Ok(bytes) => bytes,
            Err(why) => return format!("error {why}"),
        };
        let Ok(json) = std::str::from_utf8(&bytes) else {
            return "error not a page: not text".to_string();
        };
        // a field this build has no name for is ignored, so the tracks beside
        // it are kept
        let options = ParseOptions {
            ignore_unknown_fields: true,
            ..Default::default()
        };
        match protobuf_json_mapping::parse_from_str_with_options::<ContextPage>(json, &options) {
            Ok(page) => printed(&page),
            Err(why) => format!("error not a page: {why}"),
        }
    });
    into_jstring(&env, &answer)
}

/// Who is signed in, so Kotlin can build the uris that name a person. Empty
/// when there is no session, having asked for one.
///
/// Liked Songs is `spotify:user:<id>:collection`, and only the session knows
/// the id.
#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_LibrespotQueries_nativeUsername(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let name = connected()
        .map(|(_, session)| session.username())
        .unwrap_or_default();
    into_jstring(&env, &name)
}

/// The metadata for a batch of uris, of one kind, one printed message per line.
///
/// A line per entry the server answered with, in the order of the uris asked
/// for, and a line beginning `error` for one whose metadata could not be read.
/// A uri the server leaves out has no line.
async fn extended_metadata<M: MessageFull>(
    session: Session,
    kind: ExtensionKind,
    uris: Vec<String>,
) -> String {
    let request = BatchedEntityRequest {
        entity_request: uris
            .iter()
            .map(|uri| EntityRequest {
                entity_uri: uri.clone(),
                query: vec![ExtensionQuery {
                    extension_kind: EnumOrUnknown::new(kind),
                    ..Default::default()
                }],
                ..Default::default()
            })
            .collect(),
        ..Default::default()
    };
    let batch = match session.spclient().get_extended_metadata(request).await {
        Ok(batch) => batch,
        Err(why) => return format!("error {why}"),
    };
    let mut answered: Vec<(String, String)> = Vec::new();
    for entity in batch.extended_metadata {
        for data in entity.extension_data {
            let uri = data.entity_uri.clone();
            let line = data
                .extension_data
                .into_option()
                .and_then(|held| M::parse_from_bytes(&held.value).ok())
                .map(|message| printed(&message))
                .unwrap_or_else(|| "error no metadata".into());
            answered.push((uri, line.replace('\n', " ")));
        }
    }
    in_asked_order(&uris, answered).join("\n")
}

/// The lines of a batch answer, each paired with the uri the server filed it
/// under, put in the order of the uris [asked] for.
///
/// The server may answer in another order, and a row's own uri can differ from
/// the one asked for, so the uri the server echoes is what places a line. A
/// line under a uri that was not asked for goes after the others, in the order
/// it came.
fn in_asked_order(asked: &[String], answered: Vec<(String, String)>) -> Vec<String> {
    let place = |uri: &str| asked.iter().position(|it| it == uri).unwrap_or(asked.len());
    let mut answered = answered;
    answered.sort_by_key(|(uri, _)| place(uri));
    answered.into_iter().map(|(_, line)| line).collect()
}

/// [extended_metadata] for a newline-separated list of uris, as a JNI answer.
fn metadata_for<M: MessageFull>(kind: ExtensionKind, uris: &str) -> String {
    let wanted: Vec<String> = uris
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .map(String::from)
        .collect();
    let Some((runtime, session)) = connected() else {
        return NO_SESSION.to_string();
    };
    ask(runtime, extended_metadata::<M>(session, kind, wanted))
}

/// The metadata for a batch of tracks, as the service's own JSON, one per line.
///
/// What comes back is the `Track` protobuf printed as JSON, unread by this
/// side, lined up as [extended_metadata] says.
#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_LibrespotQueries_nativeTracks(
    mut env: JNIEnv,
    _class: JClass,
    uris: JString,
) -> jstring {
    let Ok(uris) = env.get_string(&uris).map(String::from) else {
        return into_jstring(&env, BAD_REQUEST);
    };
    let answer = metadata_for::<Track>(ExtensionKind::TRACK_V4, &uris);
    into_jstring(&env, &answer)
}

/// The metadata for a batch of albums, as the service's own JSON, one per line.
///
/// An artist's answer names their records by gid alone, with no title or year,
/// so a discography needs this second ask.
#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_LibrespotQueries_nativeAlbums(
    mut env: JNIEnv,
    _class: JClass,
    uris: JString,
) -> jstring {
    let Ok(uris) = env.get_string(&uris).map(String::from) else {
        return into_jstring(&env, BAD_REQUEST);
    };
    let answer = metadata_for::<Album>(ExtensionKind::ALBUM_V4, &uris);
    into_jstring(&env, &answer)
}

/// The listener's own playlists, as the service's own JSON.
///
/// The rootlist is a protobuf, so it is parsed here and printed back out
/// unread. A playlist's contents resolve through [nativeBrowse] by its uri.
#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_LibrespotQueries_nativePlaylists(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let Some((runtime, session)) = connected() else {
        return into_jstring(&env, NO_SESSION);
    };
    // the rootlist comes a page at a time and says when there is more; the
    // pages are joined into one message, so the reader pairs items with their
    // names by position as it would for a list that fitted in one
    let answer = ask(runtime, async move {
        let mut whole: Option<SelectedListContent> = None;
        let mut from = 0usize;
        for _ in 0..ROOTLIST_PAGES {
            let mut page = match session.spclient().get_rootlist(from, Some(ROOTLIST_PAGE)).await {
                Ok(bytes) => match SelectedListContent::parse_from_bytes(&bytes) {
                    Ok(list) => list,
                    Err(why) => return format!("error not a rootlist: {why}"),
                },
                Err(why) => return format!("error {why}"),
            };
            let got = page.contents.items.len();
            let more = page.contents.truncated() && got > 0;
            match whole.as_mut() {
                None => whole = Some(page),
                Some(all) => {
                    let taken = page.contents.mut_or_insert_default();
                    let items = std::mem::take(&mut taken.items);
                    let names = std::mem::take(&mut taken.meta_items);
                    let joined = all.contents.mut_or_insert_default();
                    joined.items.extend(items);
                    joined.meta_items.extend(names);
                }
            }
            from += got;
            if !more {
                break;
            }
        }
        whole.map_or_else(|| "error no rootlist".to_string(), |all| printed(&all))
    });
    into_jstring(&env, &answer)
}

/// How many rootlist entries one request asks for, which is librespot's own default.
const ROOTLIST_PAGE: usize = 120;

/// How many pages are walked at most, so a list that always claims more
/// cannot hold an IO thread. Fifty pages is six thousand entries.
const ROOTLIST_PAGES: usize = 50;

/// Where a cover lives, as the address the service's own client would use.
///
/// The template is the session's `image-url` attribute, which comes from the
/// account, and the file id is substituted into it. Empty when there is no
/// session, having asked for one, or no such attribute.
///
/// No network here; whoever shows the picture fetches it.
#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_LibrespotQueries_nativeImageUrl(
    mut env: JNIEnv,
    _class: JClass,
    file_id: JString,
) -> jstring {
    let Ok(file_id) = env.get_string(&file_id).map(String::from) else {
        return into_jstring(&env, "");
    };
    let Ok(raw) = base64::engine::general_purpose::STANDARD.decode(file_id) else {
        return into_jstring(&env, "");
    };
    let Some((_, session)) = connected() else {
        return into_jstring(&env, "");
    };
    let Some(template) = session.get_user_attribute("image-url") else {
        return into_jstring(&env, "");
    };
    let url = template.replace("{file_id}", &FileId::from_raw(&raw).to_base16());
    into_jstring(&env, &url)
}

/// A gid, as the uri that names the same thing.
///
/// The service's metadata answers carry gids (sixteen raw bytes, base64 in the
/// JSON) and its endpoints take uris. The conversion happens here because the
/// base62 alphabet and the uri format are librespot's.
///
/// [kind] is "artist" or "album". An empty answer means the gid was not one.
#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_LibrespotQueries_nativeUri(
    mut env: JNIEnv,
    _class: JClass,
    kind: JString,
    gid: JString,
) -> jstring {
    let (Ok(kind), Ok(gid)) = (
        env.get_string(&kind).map(String::from),
        env.get_string(&gid).map(String::from),
    ) else {
        return into_jstring(&env, "");
    };
    let Ok(raw) = base64::engine::general_purpose::STANDARD.decode(gid) else {
        return into_jstring(&env, "");
    };
    let Ok(id) = SpotifyId::from_raw(&raw) else {
        return into_jstring(&env, "");
    };
    let uri = match kind.as_str() {
        "artist" => SpotifyUri::Artist { id },
        "album" => SpotifyUri::Album { id },
        _ => return into_jstring(&env, ""),
    };
    into_jstring(&env, &uri.to_uri())
}

/// One artist, as the service's own JSON: who they are and what they released.
///
/// The album, single and compilation groups hold bare gid references, with no
/// title or year; [nativeAlbums] names them. This answer crosses unread.
#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_LibrespotQueries_nativeArtist(
    mut env: JNIEnv,
    _class: JClass,
    uri: JString,
) -> jstring {
    let Ok(uri) = env.get_string(&uri).map(String::from) else {
        return into_jstring(&env, BAD_REQUEST);
    };
    let Ok(wanted) = SpotifyUri::from_uri(&uri) else {
        return into_jstring(&env, "error not an artist uri");
    };
    let Some((runtime, session)) = connected() else {
        return into_jstring(&env, NO_SESSION);
    };
    let answer = ask(runtime, async move {
        match session.spclient().get_artist_metadata(&wanted).await {
            Ok(bytes) => match Artist::parse_from_bytes(&bytes) {
                Ok(artist) => printed(&artist),
                Err(why) => format!("error not an artist: {why}"),
            },
            Err(why) => format!("error {why}"),
        }
    });
    into_jstring(&env, &answer)
}

/// Invalidates the session on purpose, for a device test.
///
/// `shutdown` leaves a session in the slot that fails every request, which is
/// the state a session is in after losing its dispatch loop.
/// `LibrespotHealsTest` breaks it this way and asserts that the engine opens
/// a fresh one.
#[no_mangle]
pub extern "system" fn Java_nl_mattix_andamp_pack_librespot_LibrespotQueries_nativeBreakSession(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Some(session) = lock(&ENGINE).session.as_ref() {
        session.shutdown();
        log::info!("session invalidated on purpose");
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use AudioFileFormat::*;

    /// A track that has [files] and no others.
    fn having(files: &[AudioFileFormat]) -> impl Fn(AudioFileFormat) -> bool + '_ {
        move |format| files.contains(&format)
    }

    const EVERY: &[AudioFileFormat] = &[
        OGG_VORBIS_96,
        OGG_VORBIS_160,
        OGG_VORBIS_320,
        MP3_96,
        MP3_160,
        MP3_256,
        MP3_320,
    ];

    #[test]
    fn a_track_with_every_file_plays_at_the_quality_asked_for() {
        assert_eq!(picked_kbps(Bitrate::Bitrate96, having(EVERY)), Some(96));
        assert_eq!(picked_kbps(Bitrate::Bitrate160, having(EVERY)), Some(160));
        assert_eq!(picked_kbps(Bitrate::Bitrate320, having(EVERY)), Some(320));
    }

    #[test]
    fn a_podcast_with_only_the_low_file_plays_it_whatever_was_asked_for() {
        let only = [OGG_VORBIS_96];
        assert_eq!(picked_kbps(Bitrate::Bitrate160, having(&only)), Some(96));
        assert_eq!(picked_kbps(Bitrate::Bitrate320, having(&only)), Some(96));
    }

    /// librespot's own order, written out: 160 falls back down before up, and
    /// 320 steps down through 256.
    #[test]
    fn the_fallbacks_are_librespots_order() {
        let no_160 = [OGG_VORBIS_96, OGG_VORBIS_320, MP3_256];
        assert_eq!(picked_kbps(Bitrate::Bitrate160, having(&no_160)), Some(96));
        let no_320 = [OGG_VORBIS_96, OGG_VORBIS_160, MP3_256];
        assert_eq!(picked_kbps(Bitrate::Bitrate320, having(&no_320)), Some(256));
        let no_low = [OGG_VORBIS_160, OGG_VORBIS_320];
        assert_eq!(picked_kbps(Bitrate::Bitrate96, having(&no_low)), Some(160));
        let only_mp3_320 = [MP3_320];
        assert_eq!(
            picked_kbps(Bitrate::Bitrate96, having(&only_mp3_320)),
            Some(320)
        );
    }

    #[test]
    fn a_track_with_none_of_the_files_has_no_bitrate() {
        assert_eq!(
            picked_kbps(Bitrate::Bitrate160, having(&[AAC_24, FLAC_FLAC])),
            None
        );
    }

    fn asked(uris: &[&str]) -> Vec<String> {
        uris.iter().map(|uri| uri.to_string()).collect()
    }

    fn answered(pairs: &[(&str, &str)]) -> Vec<(String, String)> {
        pairs
            .iter()
            .map(|(uri, line)| (uri.to_string(), line.to_string()))
            .collect()
    }

    #[test]
    fn a_batch_answered_out_of_order_comes_back_in_the_order_asked() {
        let lines = in_asked_order(
            &asked(&["a:1", "a:2", "a:3"]),
            answered(&[("a:3", "three"), ("a:1", "one"), ("a:2", "two")]),
        );
        assert_eq!(lines, vec!["one", "two", "three"]);
    }

    #[test]
    fn a_uri_the_server_leaves_out_has_no_line_and_the_rest_keep_their_order() {
        let lines = in_asked_order(
            &asked(&["a:1", "a:2", "a:3"]),
            answered(&[("a:3", "three"), ("a:1", "one")]),
        );
        assert_eq!(lines, vec!["one", "three"]);
    }

    #[test]
    fn a_line_under_a_uri_not_asked_for_comes_last() {
        let lines = in_asked_order(
            &asked(&["a:1", "a:2"]),
            answered(&[("b:9", "other"), ("a:2", "two"), ("a:1", "one")]),
        );
        assert_eq!(lines, vec!["one", "two", "other"]);
    }

    #[test]
    fn only_the_three_qualities_are_settings() {
        assert_eq!(bitrate_of(96), Bitrate::Bitrate96);
        assert_eq!(bitrate_of(160), Bitrate::Bitrate160);
        assert_eq!(bitrate_of(320), Bitrate::Bitrate320);
        assert_eq!(bitrate_of(256), Bitrate::Bitrate160);
        assert_eq!(bitrate_of(0), Bitrate::Bitrate160);
    }
}
