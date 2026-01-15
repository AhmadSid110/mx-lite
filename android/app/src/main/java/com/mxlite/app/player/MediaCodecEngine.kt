class PlayerController(
    private val context: Context
) : PlayerEngine {

    private val nativeClock = NativeClock()
    private var video: MediaCodecEngine? = null
    private var surface: Surface? = null

    private val legacyAudio = AudioCodecEngine()

    private var hasAudio = false
    private var playing = false
    private var currentFile: File? = null

    override val durationMs: Long
        get() = video?.durationMs ?: 0

    override val currentPositionMs: Long
        get() = if (hasAudio)
            NativePlayer.getClockUs() / 1000
        else
            video?.currentPositionMs ?: 0

    override val isPlaying: Boolean
        get() = playing

    override fun attachSurface(surface: Surface) {
        this.surface = surface
        video?.attachSurface(surface)
    }

    override fun play(file: File) {
        release()

        currentFile = file
        hasAudio = legacyAudio.hasAudioTrack(file)

        // 1️⃣ Start audio FIRST (master)
        if (hasAudio) {
            NativePlayer.play(context, file.absolutePath)
        }

        // 2️⃣ Create NEW video engine
        video = MediaCodecEngine(clock = nativeClock).apply {
            surface?.let { attachSurface(it) }
            play(file)
        }

        playing = true
    }

    override fun pause() {
        if (!playing) return
        playing = false

        // ONLY pause audio
        if (hasAudio) {
            NativePlayer.nativePause()
        }

        // Video stays alive but idle
    }

    override fun resume() {
        if (playing) return
        playing = true

        if (hasAudio) {
            NativePlayer.nativeResume()
        }
        // Video auto-catches up using audio clock
    }

    override fun seekTo(positionMs: Long) {
        val wasPlaying = playing
        playing = false

        // 1️⃣ Pause audio
        if (hasAudio) {
            NativePlayer.nativePause()
            NativePlayer.nativeSeek(positionMs * 1000)
        }

        // 2️⃣ DESTROY video completely
        video?.release()
        video = null

        // 3️⃣ RECREATE video
        currentFile?.let { file ->
            video = MediaCodecEngine(clock = nativeClock).apply {
                surface?.let { attachSurface(it) }
                play(file)
            }
        }

        // 4️⃣ Resume audio if needed
        if (wasPlaying && hasAudio) {
            NativePlayer.nativeResume()
            playing = true
        }
    }

    override fun release() {
        if (hasAudio) {
            NativePlayer.release()
        }

        legacyAudio.release()
        video?.release()
        video = null

        hasAudio = false
        playing = false
        currentFile = null
    }
}