# AI DEVELOPMENT RULES (MANDATORY)

Before modifying:

- MediaCodecEngine
- PlayerController
- NativePlayer
- AudioEngine
- VirtualClock

YOU MUST:

1. Read /docs/ARCHITECTURE_AUDIO_VIDEO.md
2. Preserve all state machines and invariants
3. Never add implicit state transitions
4. Never let nativeSeek resume playback
5. Never modify Surface lifecycle inside play(uri)
6. SURFACE LAW: Surface must PERSIST across play/stop/seek. Only clear in release().
7. SURFACE LAW: play(uri) must CALL stop() / resetPlaybackStateOnly(), NEVER release().
8. SURFACE LAW: MediaCodec.configure() must have explicit non-silent checks for surface validity.

If unsure → STOP and ask.
