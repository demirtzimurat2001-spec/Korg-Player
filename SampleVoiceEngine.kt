package com.example.korgplayer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Lightweight software sampler/mixer for decoded PCM zones.
 * It is deliberately format-agnostic: the KORG PCM/KMP parser must provide decoded PCM
 * and key-zone metadata before original SET sounds can be played by this engine.
 */
class SampleVoiceEngine(sampleRate: Int = 44100, private val polyphony: Int = 48) : AutoCloseable {
    data class SampleZone(
        val name: String,
        val pcm: ShortArray,
        val sourceRate: Int = 44100,
        val rootKey: Int = 60,
        val lowKey: Int = 0,
        val highKey: Int = 127,
        val program: Int = 0,
        val loopStart: Int = -1,
        val loopEnd: Int = -1
    )

    private data class Voice(
        val zone: SampleZone,
        val note: Int,
        val channel: Int,
        var position: Double,
        val step: Double,
        val velocity: Float,
        var envelope: Float = 0f,
        var releasing: Boolean = false,
        var released: Boolean = false
    )

    private val rate = sampleRate.coerceAtLeast(22050)
    private val zones = CopyOnWriteArrayList<SampleZone>()
    private val voices = ArrayList<Voice>()
    private val lock = Any()
    @Volatile private var running = true
    private val audioTrack: AudioTrack
    private val renderThread: Thread
    private var testPhase = 0.0
    @Volatile var masterGain: Float = 0.7f
        set(value) { field = value.coerceIn(0f, 1f) }

    init {
        val frames = 512
        val bytes = frames * 2 * 2
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(max(bytes * 4, AudioTrack.getMinBufferSize(rate,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        audioTrack.play()
        renderThread = Thread({ renderLoop() }, "KorgPlayer-Audio")
        renderThread.priority = Thread.MAX_PRIORITY
        renderThread.start()
    }

    fun replaceBank(newZones: Collection<SampleZone>) {
        zones.clear()
        zones.addAll(newZones.filter { it.pcm.isNotEmpty() && it.sourceRate > 0 && it.lowKey <= it.highKey })
        allNotesOff()
    }

    /** Install a lightweight built-in synthesized kit so the app can make sound without USB MIDI.
     * These are generated diagnostic voices, not samples decoded from a KORG SET. */
    fun installBuiltInKit() {
        val built = ArrayList<SampleZone>()
        val drumNotes = intArrayOf(35, 36, 38, 40, 42, 44, 46, 45, 47, 48, 49, 51)
        for (note in drumNotes) {
            val seconds = when (note) { 42, 44 -> 0.16; 46, 49, 51 -> 0.65; 35, 36 -> 0.55; else -> 0.42 }
            val count = (rate * seconds).toInt().coerceAtLeast(1)
            val pcm = ShortArray(count)
            var seed = (note * 1103515245 + 12345)
            var phase = 0.0
            for (i in pcm.indices) {
                val t = i.toDouble() / rate
                val env = kotlin.math.exp(-t * when (note) { 42, 44 -> 28.0; 46, 49, 51 -> 5.5; 35, 36 -> 7.0; else -> 9.0 })
                seed = seed * 1103515245 + 12345
                val noise = (((seed ushr 16) and 0x7fff) / 16384.0) - 1.0
                val freq = when (note) { 35, 36 -> 95.0 - 65.0 * t / seconds; 45 -> 150.0; 47 -> 190.0; 48 -> 230.0; else -> 0.0 }
                phase += 2.0 * PI * freq / rate
                val wave = when (note) {
                    42, 44, 46, 49, 51 -> noise * 0.8
                    38, 40 -> noise * 0.68 + sin(phase) * 0.25
                    else -> sin(phase) * 0.9 + noise * 0.1
                }
                pcm[i] = (wave * env * 22000.0).toInt().coerceIn(-32768, 32767).toShort()
            }
            built.add(SampleZone("Built-in GM drum $note", pcm, rate, note, note, note, 0))
        }
        // A synthesized bass voice on program 1; note range is deliberately separate from the drum kit.
        val bassPcm = ShortArray((rate * 0.8).toInt())
        var phase = 0.0
        for (i in bassPcm.indices) {
            val t = i.toDouble() / rate
            val env = kotlin.math.exp(-t * 3.2)
            phase += 2.0 * PI * 65.4 / rate
            val wave = sin(phase) * 0.75 + sin(phase * 2.0) * 0.25
            bassPcm[i] = (wave * env * 19000.0).toInt().coerceIn(-32768, 32767).toShort()
        }
        built.add(SampleZone("Built-in bass", bassPcm, rate, 36, 28, 55, 1))
        replaceBank(built)
    }

    fun noteOn(note: Int, velocity: Int, channel: Int = 0, program: Int = 0) {
        if (velocity <= 0) { noteOff(note, channel); return }
        // Percussion channel uses the exact built-in drum zones. Melodic channels prefer
        // the requested program; when no SET sample zone exists, create a quiet synthesized
        // fallback so parsed STG notes remain audible instead of silently disappearing.
        val zone = if (channel == 9) {
            zones.firstOrNull { it.program == program && it.name.startsWith("Built-in GM drum") && note in it.lowKey..it.highKey }
                ?: zones.firstOrNull { it.name.startsWith("Built-in GM drum") && note in it.lowKey..it.highKey }
        } else {
            zones.firstOrNull { it.program == program && !it.name.startsWith("Built-in GM drum") && note in it.lowKey..it.highKey }
                ?: makeFallbackVoice(note, program)
        } ?: return
        val pitch = 2.0.pow((note - zone.rootKey) / 12.0)
        val step = pitch * zone.sourceRate.toDouble() / rate
        synchronized(lock) {
            while (voices.size >= polyphony) voices.removeAt(0)
            voices.add(Voice(zone, note, channel, 0.0, step, velocity.coerceIn(1, 127) / 127f))
        }
    }


    private fun makeFallbackVoice(note: Int, program: Int): SampleZone {
        val count = (rate * 1.2).toInt()
        val pcm = ShortArray(count)
        val fundamental = 440.0 * 2.0.pow((note - 69) / 12.0)
        val timbre = (program % 4)
        var phase = 0.0
        for (i in pcm.indices) {
            val t = i.toDouble() / rate
            phase += 2.0 * PI * fundamental / rate
            val env = kotlin.math.exp(-t * 1.6) * min(1.0, t / 0.012)
            val wave = when (timbre) {
                0 -> sin(phase) * 0.8 + sin(phase * 2.0) * 0.15
                1 -> if (sin(phase) >= 0.0) 0.55 else -0.55
                2 -> 2.0 / PI * kotlin.math.asin(sin(phase))
                else -> sin(phase) * 0.55 + sin(phase * 3.0) * 0.25 + sin(phase * 4.0) * 0.1
            }
            pcm[i] = (wave * env * 14500.0).toInt().coerceIn(-32768, 32767).toShort()
        }
        return SampleZone("Synth fallback program $program note $note", pcm, rate, note, note, note, program)
    }

    fun noteOff(note: Int, channel: Int = 0) {
        synchronized(lock) { voices.filter { it.note == note && it.channel == channel }.forEach { it.releasing = true } }
    }

    fun allNotesOff() {
        synchronized(lock) { voices.forEach { it.releasing = true } }
    }

    /** Adds a short diagnostic tone to the same mixer as all other voices.
     * Do not write to AudioTrack from a second thread: concurrent writers corrupt timing/audio. */
    fun playEngineTest(frequencyHz: Double = 440.0, durationMs: Int = 350) {
        val frames = (rate * durationMs / 1000).coerceAtLeast(1)
        val pcm = ShortArray(frames)
        var phase = 0.0
        val inc = 2.0 * PI * frequencyHz.coerceIn(20.0, 12000.0) / rate
        for (i in pcm.indices) {
            val attack = min(1.0, i / (rate * 0.008))
            val release = min(1.0, (frames - i).toDouble() / (rate * 0.04))
            pcm[i] = (sin(phase) * 12000.0 * min(attack, release)).toInt()
                .coerceIn(-32768, 32767).toShort()
            phase += inc
        }
        val zone = SampleZone("Diagnostic tone (not KORG PCM)", pcm, rate, 69, 69, 69, 127)
        synchronized(lock) {
            while (voices.size >= polyphony) voices.removeAt(0)
            voices.add(Voice(zone, 69, 15, 0.0, 1.0, 0.8f))
        }
    }

    private fun renderLoop() {
        val frames = 512
        val out = ShortArray(frames * 2)
        while (running) {
            java.util.Arrays.fill(out, 0.toShort())
            synchronized(lock) {
                val iterator = voices.iterator()
                while (iterator.hasNext()) {
                    val v = iterator.next()
                    val pcm = v.zone.pcm
                    for (i in 0 until frames) {
                        if (v.releasing) {
                            v.envelope -= 1f / (rate * 0.10f)
                            if (v.envelope <= 0f) { v.released = true; break }
                        } else {
                            v.envelope = min(1f, v.envelope + 1f / (rate * 0.008f))
                        }
                        if (v.position >= pcm.size) {
                            if (v.zone.loopStart >= 0 && v.zone.loopEnd > v.zone.loopStart && v.zone.loopEnd <= pcm.size) {
                                v.position = v.zone.loopStart.toDouble() + (v.position - v.zone.loopEnd)
                            } else { v.released = true; break }
                        }
                        val idx = v.position.toInt().coerceIn(0, pcm.lastIndex)
                        val frac = (v.position - idx).toFloat()
                        val a = pcm[idx].toFloat()
                        val b = pcm[min(idx + 1, pcm.lastIndex)].toFloat()
                        val s = (a + (b - a) * frac) * v.velocity * v.envelope * masterGain
                        val mixed = s.toInt()
                        val li = i * 2
                        out[li] = (out[li].toInt() + mixed).coerceIn(-32768, 32767).toShort()
                        out[li + 1] = (out[li + 1].toInt() + mixed).coerceIn(-32768, 32767).toShort()
                        v.position += v.step
                    }
                    if (v.released) iterator.remove()
                }
            }
            var offset = 0
            while (offset < out.size && running) {
                val written = audioTrack.write(out, offset, out.size - offset)
                if (written <= 0) break
                offset += written
            }
        }
    }

    override fun close() {
        running = false
        try { renderThread.join(500) } catch (_: InterruptedException) { }
        try { audioTrack.pause(); audioTrack.flush(); audioTrack.release() } catch (_: Exception) { }
        synchronized(lock) { voices.clear() }
    }
}
