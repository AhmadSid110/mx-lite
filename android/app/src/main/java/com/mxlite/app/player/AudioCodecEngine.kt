package com.mxlite.app.player

/**
 * Audio engine is the MASTER clock.
 * Video must sync to this.
 */
class AudioCodecEngine : PlaybackClock {

    private var sampleRate = 44100
    private var playedSamples = 0L
    private var playing = false

    override val positionMs: Long
        get() = (playedSamples * 1000L) / sampleRate

    fun play() {
        playing = true
    }

    fun pause() {
        playing = false
    }

    fun reset() {
        playedSamples = 0
    }

    /**
     * Call this from audio decode loop
     */
    fun onSamplesPlayed(samples: Int) {
        if (playing) {
            playedSamples += samples
        }
    }
}
