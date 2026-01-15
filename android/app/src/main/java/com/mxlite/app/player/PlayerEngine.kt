interface PlayerEngine {
    fun attachSurface(surface: Surface)
    fun play(file: File)
    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun release()

    val durationMs: Long
    val currentPositionMs: Long
    val isPlaying: Boolean
}