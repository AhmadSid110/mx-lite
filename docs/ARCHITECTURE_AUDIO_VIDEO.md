# MX Lite Playback Architecture (CANONICAL)

## Core Law

AudioEngine never decides playback state.
PlayerController is the sole authority.

## Playback State Ownership

- UI → PlayerController
- PlayerController → NativePlayer
- NativePlayer → AudioEngine + VirtualClock

No reverse calls are allowed.

## AudioEngine State Machine

[PAUSED ↔ RUNNING only]

### nativeResume()

- starts AudioEngine
- starts VirtualClock

### nativePause()

- stops AudioEngine
- pauses VirtualClock

### nativeSeek()

- updates VirtualClock position ONLY
- never resumes or pauses

### nativeInit()

- hard resets native globals

### release()

- destroys everything

## Illegal Transitions (NEVER ADD)

- seek → resume
- seek → pause
- seek → start
- auto-resume on seek

## Video Seek Contract

- First frame after seek ALWAYS renders
- Decode loop runs exactly once while paused
- No frame is dropped before anchor frame

## Surface Contract

- Surface lifecycle is independent of playback lifecycle
- play(uri) MUST NOT clear surface
- release() MAY clear surface

## Clock Invariants

- Clock owns time
- UI never stores time
- Clock never resumes itself

## Regression Checklist

- [ ] Seek while paused renders first frame.
- [ ] Rapid seek-dragging does not cause audio/video desync.
- [ ] `nativeInit()` is called once per session.
- [ ] Surface is not cleared on `play(uri)`.
- [ ] `nativeSeek()` does not trigger state changes (resume/pause).
- [ ] VirtualClock is paused during seek-drag.
