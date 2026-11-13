package com.gbalink.online

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Salida de audio. El hilo del emulador escribe cada cuadro sin bloquearse; si el
 * búfer se llena (el reloj del audio va un poco más lento), se descarta ese pedazo.
 */
class AudioOut(val sampleRate: Int = 48000) {
    private val track: AudioTrack
    private var written = 0L
    private val maxQueuedFrames = sampleRate / 8 // ~125 ms

    init {
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bytes = maxOf(minBytes, sampleRate / 5 * 4) // ~200 ms de capacidad
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track.play()
    }

    fun write(samples: ShortArray, count: Int) {
        if (count <= 0) return
        val queued = written - (track.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
        if (queued > maxQueuedFrames) return
        val n = track.write(samples, 0, count, AudioTrack.WRITE_NON_BLOCKING)
        if (n > 0) written += n / 2
    }

    fun pause() {
        runCatching { track.pause() }
    }

    fun resume() {
        runCatching { track.play() }
    }

    fun release() {
        runCatching {
            track.pause()
            track.flush()
            track.release()
        }
    }
}
