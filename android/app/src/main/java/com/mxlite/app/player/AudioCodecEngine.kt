package com.mxlite.app.player

/**
 * Audio engine is the MASTER clock.
 * Seek modifies the clock directly.
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

    fun seekTo(positionMs: Long) {
        playedSamples = (positionMs * sampleRate) / 1000L
    }

    /**
     * Call from audio decode loop
     */
    fun onSamplesPlayed(samples: Int) {
        if (playing) {
            playedSamples += samples
        }
    }
}
