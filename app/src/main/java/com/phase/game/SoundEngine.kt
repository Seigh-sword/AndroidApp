package com.phase.game

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * SoundEngine — lightweight procedural sound effects for PHASE.
 *
 * All sounds are synthesized on the fly using [AudioTrack] in STATIC mode.
 * No external assets are required. Sounds are short blips for: phase shift,
 * orb pickup, level up, game over, and a soft beat tick on the rhythm.
 *
 * If audio initialization fails (very old devices, emulators without audio),
 * the engine falls back to silent no-ops so the game keeps running.
 */
class SoundEngine {

    private val sampleRate = 22050
    private var initialized = false
    private var muted = false

    // Pre-rendered PCM buffers (16-bit mono)
    private var sfxPhaseShift: ShortArray? = null
    private var sfxOrb: ShortArray? = null
    private var sfxLevelUp: ShortArray? = null
    private var sfxGameOver: ShortArray? = null
    private var sfxBeat: ShortArray? = null
    private var sfxTap: ShortArray? = null

    // Reusable AudioTracks
    private val poolSize = 4
    private val tracks = ArrayList<AudioTrack>(poolSize)
    private var nextTrack = 0

    fun setMuted(m: Boolean) { muted = m }

    fun init() {
        if (initialized) return
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf, 2048)

            for (i in 0 until poolSize) {
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                tracks.add(t)
            }
            sfxPhaseShift = renderSweep(220f, 880f, 0.18f, 0.7f, kind = SweepKind.RISE)
            sfxOrb = renderPluck(880f, 0.15f, 0.7f)
            sfxLevelUp = renderArpeggio(doubleArrayOf(523.25, 659.25, 783.99, 1046.5), 0.10f, 0.6f)
            sfxGameOver = renderArpeggio(doubleArrayOf(440.0, 349.23, 261.63, 174.61), 0.16f, 0.7f)
            sfxBeat = renderPluck(110f, 0.06f, 0.35f)
            sfxTap = renderPluck(660f, 0.05f, 0.5f)
            initialized = true
        } catch (t: Throwable) {
            initialized = false
        }
    }

    fun release() {
        for (t in tracks) {
            try { t.stop() } catch (_: Throwable) {}
            try { t.release() } catch (_: Throwable) {}
        }
        tracks.clear()
        initialized = false
    }

    fun playPhaseShift() = play(sfxPhaseShift, 0.7f)
    fun playOrb() = play(sfxOrb, 0.9f)
    fun playLevelUp() = play(sfxLevelUp, 0.85f)
    fun playGameOver() = play(sfxGameOver, 0.9f)
    fun playBeat() = play(sfxBeat, 0.5f)
    fun playTap() = play(sfxTap, 0.7f)

    private fun play(buf: ShortArray?, gain: Float) {
        if (!initialized || muted) return
        val data = buf ?: return
        val idx = nextTrack
        nextTrack = (nextTrack + 1) % poolSize
        val track = tracks[idx]
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
                track.flush()
            }
            track.setVolume(gain)
            track.write(data, 0, data.size)
            track.play()
        } catch (_: Throwable) {
            // audio errors are non-fatal
        }
    }

    // ----- Synthesis helpers -----

    private enum class SweepKind { RISE, FALL }

    private fun renderSweep(fStart: Float, fEnd: Float, duration: Float, gain: Float, kind: SweepKind): ShortArray {
        val n = (sampleRate * duration).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i / sampleRate.toFloat()
            val tn = i / n.toFloat()
            val freq = if (kind == SweepKind.RISE) fStart + (fEnd - fStart) * tn
                       else fStart + (fStart - fEnd) * tn
            val phase = 2.0 * PI * freq * t
            val env = exp(-tn * 2.4) * (1 - exp(-tn * 12)) // quick attack, decay
            val s = sin(phase) * env * gain
            out[i] = (s * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun renderPluck(freq: Float, duration: Float, gain: Float): ShortArray {
        val n = (sampleRate * duration).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i / sampleRate.toFloat()
            val tn = i / n.toFloat()
            val env = exp(-tn * 6.0)
            val s = sin(2.0 * PI * freq * t) * env * gain
            out[i] = (s * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun renderArpeggio(freqs: DoubleArray, noteDuration: Float, gain: Float): ShortArray {
        val n = (sampleRate * noteDuration).toInt()
        val out = ShortArray(n * freqs.size)
        for (k in freqs.indices) {
            val f = freqs[k]
            for (i in 0 until n) {
                val t = i / sampleRate.toFloat()
                val tn = i / n.toFloat()
                val env = exp(-tn * 5.0)
                val s = sin(2.0 * PI * f * t) * env * gain
                out[k * n + i] = (s * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }

    private fun maxOf(a: Int, b: Int) = if (a > b) a else b
}

