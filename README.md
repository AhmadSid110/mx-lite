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

---

## Soft Pause (AAudio) Policy 🔇

**Goal:** Avoid touching the audio driver on UI pause; guarantee instant silence without blocking or deadlocking the app.

**Principles**

- **Never** call AAudio driver stop/start from the UI pause/resume flows.
- Keep the AAudio stream alive and **gate output in the callback** (write silence when paused).
- **Gate decoding** in the decoder thread (cooperative non-blocking loop — timeout 0 on dequeue).
- Use atomics (`audioOutputEnabled_`, `isPlaying_`, `decodeEnabled_`) to coordinate state without joins or blocking calls.
- This matches industry practice (ExoPlayer / Oboe) and prevents freezes on affected OEM drivers.

**Reference snippets** (see `AudioEngine.cpp` for full implementation):

```cpp
// pause(): soft pause only — NO DRIVER CALLS
audioOutputEnabled_.store(false, std::memory_order_release);
isPlaying_.store(false, std::memory_order_release);
// DO NOT call AAudioStream_requestStop(stream_);
```

```cpp
// start(): stream kept alive, just wake up decoder
audioOutputEnabled_.store(true, std::memory_order_release);
isPlaying_.store(true, std::memory_order_release);
// DO NOT call AAudioStream_requestStart(stream_);
```

```cpp
// decode loop: cooperative, non-blocking
while (decodeEnabled_.load(std::memory_order_acquire)) {
    if (!isPlaying_.load(std::memory_order_acquire)) {
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
        continue;
    }
    // dequeue output with timeout 0 (never block)
    ssize_t outIndex = AMediaCodec_dequeueOutputBuffer(codec_, &info, 0);
    // ... writeAudio() (drop if buffer full)
}
```

```cpp
// AAudio callback: hard gate (instant silence)
if (!engine->audioOutputEnabled_.load(std::memory_order_acquire)) {
    memset(out, 0, engine->framesToSamples(numFrames) * sizeof(int16_t));
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}
```

**How to validate (manual smoke tests)**

1. Play a file with audio.
2. Pause immediately and verify audio becomes silent instantly (no tail or delay).
3. Rapidly pause/resume several times; ensure no UI freezes, ANRs, or audio thread deadlocks.
4. Background and foreground the app while paused; validate no unexpected driver calls or crashes.
5. Seek while paused and ensure audio remains silent and resume plays correctly.

**Notes**

- If you need automated integration tests later, prefer instrumentation tests that exercise rapid pause/resume and backgrounding flows; keep them platform-specific (device/emulator) since OEM driver behavior varies.

---

