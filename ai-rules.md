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

If unsure → STOP and ask.
