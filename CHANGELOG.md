# Changelog

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
