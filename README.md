# MX Lite

MediaCodec-first Android video player with external codec packs.

Status: 🚧 Bootstrapped

## FD Ownership Rules ✅

- Playback FD ownership belongs to the *playback engines* (AudioEngine / MediaCodecEngine). Engines are responsible for opening and closing the ParcelFileDescriptor they use for playback.
- UI and metadata helpers may open a short-lived ParcelFileDescriptor for metadata inspection (audio tracks, codec info), but must close it immediately after use.
- Do NOT use ParcelFileDescriptor.adoptFd(fd) or similar APIs that transfer ownership of a raw FD you don't own — this can close the FD unexpectedly and break playback.
- For native audio playback, pass the raw integer FD to the native layer (it must call dup(fd) internally). Do not let Java/Compose code retain or close the original FD used by native code.
- Metadata extractors that receive a FileDescriptor must not close it; they should only read synchronously and return. Ownership remains with the caller (usually MediaCodecEngine when used for playback).
- Prefer play(uri: Uri) entry points where the engine opens its own PFD; only open separate PFDs for metadata, never for playback ownership.
- When in doubt: duplicate (dup) the FD if you need an independent lifetime; borrow (do not close) if you only need short-lived metadata access.
- Add a code comment whenever a method accepts a FileDescriptor to explicitly state whether the method takes ownership or not.

