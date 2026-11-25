# Changelog

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
