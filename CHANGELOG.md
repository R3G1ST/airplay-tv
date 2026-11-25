# Changelog

## 1.4.4

- Fixed the "cut" outline on the left button: the focused chip scales up a few
  percent and the parent row clipped its outer edges flat. The row no longer
  clips its children and has a small horizontal padding.
- Status pill and both buttons now share one compact glass-chip style.

## 1.4.3

- Status pill and the two buttons use one shared "glass chip" style: rounded
  rectangle with a hairline border instead of a full capsule, so no end looks
  clipped. Both are smaller (13 sp status, 12 sp chips, tighter padding).

## 1.4.2

- Added breathing room between the status pill and the two chip buttons on the
  idle screen (removing the hint paragraph had left them almost touching).

## 1.4.1 — calmer, more compact home screen

- The idle screen is smaller and quieter: 84 dp icon, 28 sp title, compact status
  pill and small chip buttons instead of the two large ones.
- The status dot now has a soft expanding halo (one animator, no assets); it
  stops when the activity is hidden.
- Removed the "Open Screen Mirroring on iPhone or Mac" hint: the status pill
  says everything, and the idle screen no longer shows a paragraph.
- The status pill has a hairline border, closer to a tvOS capsule.

## 1.4.0 — typography

- Bundled **Inter** (SIL OFL 1.1) as the UI typeface — a legal, metrically close
  stand-in for Apple's SF Pro, which cannot be redistributed. Latin, Cyrillic,
  digits and punctuation are subset to ~30 KB per weight (60 KB total).
- App title and track title use Semibold; body text uses Regular.
- Timeline digits use tabular figures (`tnum`), so the clock does not wobble.

## 1.3.8

- The now-playing row is slightly narrower (cover 340 dp, gap 40 dp), so the
  cover no longer sits almost against the screen edge: it keeps a ~48 dp margin
  on a 1080p TV while staying centred with the text block.

## 1.3.7

- The now-playing block (title, artist, album) is now centred on the timeline
  axis: the progress bar and the time row were left-aligned inside the column,
  so their centre was ~18 dp off from the centred text.

## 1.3.6

- Empty "clear now-playing" messages from the sender no longer wipe the music
  screen or the cached last track (this was the bare "AirPlay" screen while
  music played).
- Repeated identical metadata heartbeats (about one per second) are ignored, so
  the UI is not redrawn for nothing.
- The noisy per-request protocol log is gone; the raw DMAP dump stays only when
  a metadata update carries no known tags.
- Restored the decoded-fps log line lost during the presentation-time experiment.
- The decoder is back on the known-good vendor configuration (the removed
  low-latency flag had stopped it from producing frames and triggered a green,
  corrupted software-decoder picture).

## 1.3.5

- Log every SET_PARAMETER (content type, size, first bytes) to find where the
  sender puts now-playing data when it sends an empty DMAP block.

## 1.3.4

- Empty metadata updates from the sender (radio, system audio, some Apple Music
  states) no longer wipe the now-playing screen and the cached last track. That
  used to leave a bare "AirPlay" screen while music played.
- A music session without metadata now shows "Играет музыка" instead of an empty
  artist/album block.
- The log records the raw DMAP body when it carries no known tags, for diagnosis.

## 1.3.3

- Restored the vendor low-latency decoder flag: removing it made the hardware
  decoder produce no frames at all on this TV, after which the software fallback
  showed a green, corrupted picture.
- The software fallback now requires three stalled attempts (15 s) and never
  replaces a decoder that is already producing frames.
- The decoder log now names the actual codec in use.

## 1.3.2

- Reverted timestamp-scheduled presentation added in 1.3.1: this vendor codec
  held frames until the given time, which added up to half a second of input
  lag. Frames are presented immediately again.
- Kept the larger decoder buffer pool (no vendor "low latency" mode).

## 1.3.1 — smooth motion

- Video frames are now presented at their real timestamps instead of "as soon as
  decoded". Mirroring arrives over TCP in bursts, so showing every frame
  immediately made the cursor jump: two frames on one screen refresh, none on
  the next. Early frames wait for their vsync; late ones still show at once.
- The vendor "low latency" decoder mode is no longer requested: it reduces the
  output buffer count to 4, which is too few once frames are paced.
- The 5-second FPS line now also reports how many frames were late and the worst
  lateness, so judder can be measured instead of guessed.

## 1.3.0 — reliability pass ("always connects, always works")

- **Health watchdog**: every 30 s the service checks that the RTSP listener and
  the mDNS responder are actually alive and restarts them if they died. Before,
  the service could stay running but permanently deaf.
- **Network changes**: a connectivity callback re-binds the multicast lock and
  re-announces mDNS after Wi-Fi reconnect / subnet change, so the receiver
  immediately reappears in the AirPlay list.
- **CPU wake lock** during a cast (renewed every 30 s), so audio does not glitch
  when the receiver runs in the background or the screen turns off.
- **Decoder fallback**: if the hardware H.264 decoder stalls twice without ever
  producing a frame, the receiver switches to the platform software decoder
  instead of staying black forever.
- **Audio route recovery**: a failed `AudioTrack.write` (HDMI re-negotiation,
  sleep) rebuilds the track instead of going silent.
- **Cover art is decoded off the main thread** and capped at 1024 px, removing
  the stutter on track change and large-image memory spikes.
- **Diagnostics**: during a cast the log gets `health: phase=… threads=… fds=…
  pss=…KB` every 30 s, so leaks are measurable over long sessions.

## 1.2.3

- Log the SPS/PPS of each mirroring stream (diagnostics for the 1080p black
  picture on Amlogic codecs).

## 1.2.2

- Reverted the decoder operating-rate hints added in 1.2.1: on this Amlogic
  codec they made it accept input but never output a frame (black picture).
  The full-frame (no-overscan) change and FPS telemetry are kept.

## 1.2.1 — sharper mirroring, 60 fps

- The receiver no longer tells the sender it has overscan. macOS used that to
  shrink the picture to a ~94 % safe area (we measured 1804×1016 instead of
  1920×1080), which then had to be upscaled again — the source of the slightly
  soft text. It now requests the full frame, so a 1080p TV gets 1:1 pixels.
- `/info` advertises `maxFPS = 60`, and the decoder is told it decodes a 60 fps
  live stream (`KEY_OPERATING_RATE`), so TV codecs no longer fall back to 30 fps.
- Aspect fitting snaps to the full screen when the ratio differs by < 0.6 %,
  removing a pointless non-integer scale.
- Video FPS is logged every 5 s (`video: … fps`, `decoded: … fps`) for diagnosis.

## 1.2.0 — stability and smoothness pass

Full audit of the mirroring and music paths.

- **Race fix**: a video thread from a previous stream could report "video ended"
  right after a new stream started, ending the fresh session. Streams are now
  generation-tagged.
- **Android 5.0/5.1**: `MediaCodec.setOutputSurface` (API 23) is guarded, so the
  receiver no longer crashes on old TVs when the window returns.
- **Less jank**: the mirror receive buffer is reused instead of allocating one
  array per frame (~60/s); the decoder uses the stream's real resolution instead
  of forcing 1920x1080; the music timeline updates 4 times a second instead of
  10; audio frames no longer touch the UI thread on every packet.
- **Faster main thread**: the "remote idle" lookup (a system IPC) is cached for
  one second instead of running on every tick and on every new session.
- **Recovery**: if port 7000 is still held after an update, the receiver retries
  instead of staying dead.

## 1.1.8

- Fixed a black picture from macOS: a new mirroring SETUP could reuse the
  previous stream's port and AES key, so every frame decrypted to garbage after
  a reconnect. A new SETUP now always restarts the stream with the new key.
- Malformed-frame warnings are rate-limited instead of flooding the log at
  60 fps.

## 1.1.7

- A TV whose model is reported as "Chromecast" (many Android TVs) is no longer
  advertised under that name, where it collided with the built-in Cast target
  and was easy to pick by mistake. Such devices now default to "AirPlay TV";
  the name can still be changed with the "Device name" button.

## 1.1.6

- Fixed a stuck last frame: a sender takeover and a closed mirroring socket now
  always end the cast on screen. Previously this depended on an internal flag
  that macOS clears with its frequent TEARDOWN, so the old Mac picture stayed
  frozen while a phone played music (and the phone looked "not connected").
- Ending a cast also closes the sender's control connections, so its AirPlay
  menu stops showing the receiver as connected.

## 1.1.5

- Screen mirroring no longer re-opens the receiver by itself after you leave it:
  a hidden mirroring decoder cannot be re-synced without a keyframe. Open the
  receiver manually and the picture is there. A new cast still opens it.
- Music keeps the automatic 12-second return.

## 1.1.4

- Mirroring comes back to the screen after 3 s of remote inactivity (music keeps
  12 s). A hidden mirroring surface cannot be re-synced without a keyframe, and
  macOS only sends one when the picture changes, so the receiver must not stay
  hidden for long.
- The decoder is never recreated while the window is hidden.

## 1.1.3

- The decoder is no longer destroyed when the receiver is hidden. It keeps
  running off-screen and, on return, its output surface is switched with
  `setOutputSurface`, so the picture resumes instantly instead of waiting for a
  keyframe that a static Mac screen may never send.
- Preempting a sender now also closes its connections, so the old device stops
  showing "connected".

## 1.1.2

- The auto-return ticker no longer re-launches the activity when it is already
  on screen. It used to fire every 4 s, re-attaching the surface and recreating
  the decoder each time, so the picture never appeared (black screen).
  Visibility is now tracked with onStart/onStop, which is reliable on TV.

## 1.1.1

- Music after a mirroring session used to stay on a black video surface: the
  mirror geometry was never reset, so a music session was treated as video.
  It is now cleared on session end and when audio starts.
- The auto-return ticker no longer depends on incoming frames or a stale
  "visible" flag: while a cast is active it checks every 4 s whether the remote
  has been idle for 12 s and brings the receiver back.

## 1.1.0

- A new sender now takes over the screen on any new SETUP/RECORD, not only on
  pair-setup. A phone that skips pair-setup (already paired) used to leave a
  stuck Mac session on screen and its music cover never appeared.
- Decoder self-heal no longer reuses a released codec (that caused
  "codec is released already" and endless re-creation).

## 1.0.9

- Decoder watchdog: if frames keep arriving but the codec produces no output for
  3 s, it is recreated automatically (some TV codecs stall after a surface change).
- The receiver stops decoding as soon as its window loses focus, so it never
  feeds a dead surface.
- Auto-return now checks window focus, not just "resumed": a receiver covered by
  the launcher comes back after ~12 s of remote inactivity.

## 1.0.8

- Fixed a layout feedback loop: the layout listener re-applied the surface size,
  which scheduled another layout, forever. The surface was being resized
  continuously, which starved the decoder and produced a black first attempt.

## 1.0.7

- If the receiver screen is minimised during a cast, it comes back by itself
  after ~12 seconds without remote input (and never while the remote is in use).

## 1.0.6

- macOS sends TEARDOWN about half a second after mirroring starts (it restarts
  its audio channel). We used to stop every stream on TEARDOWN, so the picture
  vanished and the app fell back to the last music track. TEARDOWN now stops
  only the audio channel; the video ends when its own socket closes.

## 1.0.5

- Reopening the app during an active mirroring no longer shows a frozen or
  black picture: the decoder is always recreated when a fresh surface appears
  (including after the activity was hidden and shown again).

## 1.0.4

- One sender owns the screen: a new device preempts the previous cast instead of
  both playing at once.
- Cleaner mirroring: the corner badge and the on-screen format button are gone
  (screen format is still switchable with the MENU key).

## 1.0.3

- The video stream now has a 15 s silence watchdog: a stalled connection is
  dropped instead of leaving the sender "connected" with a frozen picture.
- Heartbeat every ~5 s (`video frames: N`) and a decoder-stall warning, so a
  freeze can be diagnosed from the log.

## 1.0.2

- Fixed the black screen on the first connection: a released (or reused)
  SurfaceView surface was passed to the decoder, which failed with
  "surface has been released". The decoder now validates the surface and
  re-configures when a fresh one arrives.

## 1.0.1

- Pairing and FairPlay state are now kept per sender device. macOS opens several
  connections at once; before, they could overwrite each other's keys and the
  first attempt ended in a black screen or a dropped session.
- High-performance Wi-Fi lock while streaming, so the TV does not fall into
  power saving in the middle of a cast.

## 1.0.0 — first public release

- Screen mirroring from iPhone, iPad and Mac (H.264 → MediaCodec)
- Mirroring audio (AAC-ELD) with duplicate removal and a reorder buffer
- Apple Music: lossless ALAC decoded by a bit-exact built-in decoder
- Now-playing screen: cover art, title, artist, album, live timeline
- DACP remote: OK pauses/resumes, ◀ ▶ switch tracks
- Three screen formats: fit, fill, fill-and-crop
- Runs as part of the TV: auto-start after boot and after updates,
  appears on cast, returns to the system when done
- Works on Android 5.0+, any CPU, 260 KB, no native libraries
