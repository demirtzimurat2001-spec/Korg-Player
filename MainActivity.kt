package com.example.korgplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.zip.ZipInputStream

class MainActivity : Activity() {
    private val requestZip = 1001
    private lateinit var status: TextView
    private lateinit var fileInfo: TextView
    private lateinit var stylesList: TextView
    private lateinit var midiInfo: TextView
    private var tempo = 120
    private lateinit var audioEngine: SampleVoiceEngine
    private var midiDevice: MidiDevice? = null
    private var midiInput: MidiInputPort? = null
    private var midiManager: MidiManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var selectedDeviceName = ""
    private var rhythmPlaying = false
    private var step = 0
    private var rhythmMode = 0 // 0 = Variation, 1 = Fill, 2 = Break, 3 = Ending
    @Volatile private var loadedStyleMidi: EmbeddedMidiParser.Sequence? = null
    @Volatile private var loadedStyleContainer: ByteArray? = null
    @Volatile private var activeStyleMidi: EmbeddedMidiParser.Sequence? = null
    private data class PlayableStyle(val name: String, val bytes: ByteArray, val sequence: EmbeddedMidiParser.Sequence)
    @Volatile private var playableStyles: List<PlayableStyle> = emptyList()
    @Volatile private var selectedStyleName: String = ""
    private var midiPlaybackIndex = 0
    private val channelPrograms = IntArray(16)
    private val styleMidiTick = object : Runnable { override fun run() { playNextStyleMidiEvent() } }
    private val rhythmNames = arrayOf("VARIATION", "FILL", "BREAK", "ENDING")
    private val rhythmTick = object : Runnable {
        override fun run() {
            if (!rhythmPlaying) return
            playRhythmStep(step)
            step = (step + 1) % 16
            handler.postDelayed(this, (60000L / tempo / 4).coerceAtLeast(20L))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        fileInfo = findViewById(R.id.fileInfo)
        stylesList = findViewById(R.id.stylesList)
        midiInfo = findViewById(R.id.midiInfo)
        midiManager = getSystemService(Context.MIDI_SERVICE) as? MidiManager
        audioEngine = SampleVoiceEngine()
        audioEngine.installBuiltInKit()
        findViewById<Button>(R.id.testSound).setOnClickListener {
            audioEngine.playEngineTest()
            status.text = "Dahili ses motoru test edildi · örnek ton (Korg PCM henüz eşlenmedi)"
        }
        findViewById<Button>(R.id.openZip).setOnClickListener { chooseZip() }
        findViewById<Button>(R.id.selectStyle).setOnClickListener { choosePlayableStyle() }
        findViewById<Button>(R.id.refreshMidi).setOnClickListener { showMidiDevices() }
        findViewById<Button>(R.id.testMidi).setOnClickListener { chooseMidiOutputAndTest() }
        findViewById<Button>(R.id.startStop).setOnClickListener {
            if (rhythmPlaying) stopRhythm() else startRhythm()
        }
        findViewById<Button>(R.id.tempo).setOnClickListener {
            tempo = if (tempo >= 240) 60 else tempo + 10
            (it as Button).text = "TEMPO: $tempo"
        }
        findViewById<Button>(R.id.pattern).setOnClickListener {
            rhythmMode = (rhythmMode + 1) % rhythmNames.size
            (it as Button).text = "BÖLÜM: ${rhythmNames[rhythmMode]}"
        }
        showMidiDevices()
    }

    private fun chooseMidiForRhythm() {
        val devices = midiManager?.devices?.filter { it.inputPortCount > 0 } ?: emptyList()
        if (devices.isEmpty()) {
            Toast.makeText(this, "Önce klavyeyi USB-MIDI ile bağla ve MIDI cihazlarını yenile.", Toast.LENGTH_LONG).show()
            showMidiDevices()
            return
        }
        val labels = devices.map { it.info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI cihazı" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Ritmin gönderileceği klavye")
            .setItems(labels) { _, which -> openDeviceForRhythm(devices[which], labels[which]) }
            .setNegativeButton("İptal", null).show()
    }

    private fun openDeviceForRhythm(info: MidiDeviceInfo, name: String) {
        val manager = midiManager ?: return
        manager.openDevice(info, { device ->
            if (device == null) {
                runOnUiThread { Toast.makeText(this, "MIDI cihazı açılamadı.", Toast.LENGTH_LONG).show() }
                return@openDevice
            }
            try { midiInput?.close() } catch (_: Exception) { }
            try { midiDevice?.close() } catch (_: Exception) { }
            midiDevice = device
            midiInput = device.openInputPort(0)
            selectedDeviceName = name
            runOnUiThread {
                if (midiInput == null) Toast.makeText(this, "Cihazın MIDI giriş portu açılamadı.", Toast.LENGTH_LONG).show()
                else startRhythm()
            }
        }, handler)
    }

    private fun startRhythm() {
        // Internal synthesized kit works without a keyboard; if a MIDI device is connected,
        // the same drum/bass events are mirrored to its input port.
        rhythmPlaying = true
        step = 0
        val sceneName = when (rhythmMode) { 1 -> "Fill1"; 2 -> "Break"; 3 -> "Ending1"; else -> "Var1" }
        val sceneSequence = loadedStyleContainer?.let { EmbeddedMidiParser.extractScene(it, sceneName) }
        activeStyleMidi = sceneSequence ?: if (rhythmMode == 0) loadedStyleMidi else null
        val embedded = activeStyleMidi
        if (embedded != null) {
            midiPlaybackIndex = 0
            java.util.Arrays.fill(channelPrograms, 0)
            handler.removeCallbacks(styleMidiTick)
            findViewById<Button>(R.id.startStop).text = "STOP"
            status.text = "STG ${sceneName} bölümü çalıyor · ${embedded.noteCount} nota · ${embedded.trackCount} iz"
            playNextStyleMidiEvent()
            return
        }
        findViewById<Button>(R.id.startStop).text = "STOP"
        status.text = if (midiInput != null) "Dahili ses + USB-MIDI ritmi · $tempo BPM · $selectedDeviceName" else "Dahili sentezlenmiş ritim · $tempo BPM · Korg STY olayları henüz eşlenmedi"
        handler.removeCallbacks(rhythmTick)
        rhythmTick.run()
    }

    private fun stopRhythm() {
        rhythmPlaying = false
        handler.removeCallbacks(rhythmTick)
        handler.removeCallbacks(styleMidiTick)
        sendMidi(byteArrayOf(0xB9.toByte(), 123, 0)) // all notes off, channel 10
        sendMidi(byteArrayOf(0xB1.toByte(), 123, 0)) // all notes off, bass channel
        sendMidi(byteArrayOf(0xB9.toByte(), 120, 0)) // reset all sounds, percussion channel
        sendMidi(byteArrayOf(0xB1.toByte(), 120, 0)) // reset all sounds, bass channel
        audioEngine.allNotesOff()
        findViewById<Button>(R.id.startStop).text = "RİTMİ BAŞLAT"
        status.text = "Ritim durdu"
    }

    private fun playNextStyleMidiEvent() {
        val seq = activeStyleMidi ?: return
        if (!rhythmPlaying) return
        if (midiPlaybackIndex >= seq.events.size) {
            midiPlaybackIndex = 0
            // loop the extracted MIDI phrase
        }
        val ev = seq.events[midiPlaybackIndex]
        val nextTick = if (midiPlaybackIndex + 1 < seq.events.size) {
            seq.events[midiPlaybackIndex + 1].tick
        } else {
            // Keep the groove at its musical bar length instead of restarting immediately
            // when the final MIDI event is earlier than the end of the bar.
            val barTicks = (seq.ppq.toLong() * 4L).coerceAtLeast(1L)
            val lastTick = seq.events.last().tick
            ((lastTick + barTicks - 1L) / barTicks) * barTicks
        }
        val statusByte = ev.status and 0xff
        val kind = statusByte and 0xF0
        val channel = statusByte and 0x0F
        when (kind) {
            0x90 -> if (ev.b > 0) {
                audioEngine.noteOn(ev.a, ev.b, channel, channelPrograms[channel])
                sendMidi(byteArrayOf(statusByte.toByte(), ev.a.toByte(), ev.b.toByte()))
            } else {
                audioEngine.noteOff(ev.a, channel)
                sendMidi(byteArrayOf((0x80 or channel).toByte(), ev.a.toByte(), 0))
            }
            0x80 -> {
                audioEngine.noteOff(ev.a, channel)
                sendMidi(byteArrayOf(statusByte.toByte(), ev.a.toByte(), ev.b.toByte()))
            }
            0xC0 -> {
                channelPrograms[channel] = ev.a.coerceIn(0, 127)
                sendMidi(byteArrayOf(statusByte.toByte(), ev.a.toByte()))
            }
        }
        midiPlaybackIndex++
        val delta = (nextTick - ev.tick).coerceAtLeast(0L)
        val delay = (delta * 60000L / seq.ppq / tempo.coerceAtLeast(1)).coerceAtLeast(1L)
        handler.postDelayed(styleMidiTick, delay)
    }

    private fun playRhythmStep(s: Int) {
        // General MIDI percussion channel (10). These are generated patterns, not decoded Korg STY events.
        if (rhythmMode == 2) {
            if (s == 0) drum(36, 115) // break: kick at bar start
            return
        }
        if (rhythmMode == 3) {
            if (s == 0) { drum(36, 110); drum(49, 100) }
            if (s == 8) drum(49, 95)
            if (s == 15) stopRhythm()
            return
        }
        if (s % 4 == 0) drum(42, 64)
        if (s % 2 == 0) drum(42, if (s % 4 == 0) 72 else 48)
        if (s == 0 || s == 8) drum(36, if (rhythmMode == 1) 118 else 108)
        if (s == 4 || s == 12) drum(38, if (rhythmMode == 1) 115 else 100)
        if (rhythmMode == 1 && (s == 14 || s == 15)) { drum(45, 92); drum(47, 100) }
        // Simple generated bass accompaniment: root on beats 1/3, fifth on beats 2/4.
        // This is deliberately a demo pattern; chord recognition and original STY bass events
        // still need to be decoded before it can behave like a real Pa-series arranger.
        if (s % 4 == 0) {
            val bassNote = when (s) {
                0, 8 -> 36 // C root
                4, 12 -> 43 // G fifth
                else -> 36
            }
            val bassVelocity = if (rhythmMode == 1) 100 else 84
            audioEngine.noteOn(bassNote, bassVelocity, channel = 1, program = 1)
            handler.postDelayed({ audioEngine.noteOff(bassNote, channel = 1) }, 190)
            sendMidi(byteArrayOf(0x91.toByte(), bassNote.toByte(), bassVelocity.toByte()))
            handler.postDelayed({ sendMidi(byteArrayOf(0x81.toByte(), bassNote.toByte(), 0)) }, 190)
        }
    }

    private fun drum(note: Int, velocity: Int) {
        audioEngine.noteOn(note, velocity, channel = 9, program = 0)
        handler.postDelayed({ audioEngine.noteOff(note, channel = 9) }, 110)
        sendMidi(byteArrayOf(0x99.toByte(), note.toByte(), velocity.toByte()))
        handler.postDelayed({ sendMidi(byteArrayOf(0x89.toByte(), note.toByte(), 0)) }, 65)
    }

    private fun sendMidi(bytes: ByteArray) {
        try { midiInput?.send(bytes, 0, bytes.size, System.nanoTime()) } catch (_: Exception) { }
    }

    private fun choosePlayableStyle() {
        val choices = playableStyles
        if (choices.isEmpty()) {
            Toast.makeText(this, "Önce içinde çalınabilir STG dosyaları olan ZIP seç.", Toast.LENGTH_LONG).show()
            return
        }
        val labels = choices.map { "${it.name}  ·  ${it.sequence.noteCount} nota" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Çalınacak STG ritmini seç")
            .setItems(labels) { _, which ->
                val selected = choices[which]
                loadedStyleContainer = selected.bytes
                loadedStyleMidi = selected.sequence
                selectedStyleName = selected.name
                status.text = "Seçili ritim: ${selected.name} · ${selected.sequence.noteCount} nota · Var1"
                stylesList.text = "Seçili çalınabilir ritim:\n${selected.name}\nOlay: ${selected.sequence.events.size} · Nota: ${selected.sequence.noteCount} · PPQ: ${selected.sequence.ppq}\n\nBölüm düğmesiyle Variation/Fill/Break/Ending seçebilirsin."
            }.setNegativeButton("İptal", null).show()
    }

    private fun chooseZip() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
        }
        try { startActivityForResult(i, requestZip) } catch (_: Exception) {
            val fallback = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }
            startActivityForResult(fallback, requestZip)
        }
    }

    @Deprecated("Uses document picker callback for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestZip || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        fileInfo.text = queryName(uri)
        status.text = "SET arşivi taranıyor…"
        Thread {
            val counts = linkedMapOf<String, Int>()
            val styles = mutableListOf<StyleInfo>()
            val sounds = mutableListOf<String>()
            var foundStyleMidi: EmbeddedMidiParser.Sequence? = null
            var foundStyleContainer: ByteArray? = null
            val foundPlayableStyles = ArrayList<PlayableStyle>()
            var totalBytes = 0L
            var entries = 0
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input.buffered()).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                entries++
                                val name = entry.name
                                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                                counts[if (ext.isEmpty()) "(uzantısız)" else ".$ext"] = (counts[if (ext.isEmpty()) "(uzantısız)" else ".$ext"] ?: 0) + 1
                                val bytes = zip.readBytes()
                                totalBytes += bytes.size
                                when (ext) {
                                    "sty", "stg" -> {
                                        if (ext == "stg") {
                                            val parsed = EmbeddedMidiParser.extractAndParse(bytes)
                                            if (parsed != null) {
                                                if (foundStyleMidi == null) {
                                                    foundStyleContainer = bytes
                                                    foundStyleMidi = parsed
                                                    selectedStyleName = name
                                                }
                                                if (foundPlayableStyles.size < 120) foundPlayableStyles.add(PlayableStyle(name, bytes, parsed))
                                            }
                                        }
                                        val decoded = Oc31Decoder.scanBlocks(bytes)
                                        val decodedLabels = decoded.flatMap { extractLabels(it.data) }.distinct().take(12)
                                        styles.add(StyleInfo(name, bytes.size, extractLabels(bytes), decoded.size, decodedLabels))
                                    }
                                    "pcg", "pad", "prf", "kmp" -> {
                                        val labels = extractLabels(bytes).take(5)
                                        sounds.add("$name${if (labels.isNotEmpty()) " — ${labels.joinToString(", ")}" else ""}")
                                    }
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                } ?: throw IllegalStateException("Dosya okunamadı")
            } catch (e: Exception) {
                runOnUiThread { status.text = "ZIP okunamadı: ${e.message ?: "bilinmeyen hata"}" }
                return@Thread
            }
            loadedStyleMidi = foundStyleMidi
            loadedStyleContainer = foundStyleContainer
            playableStyles = foundPlayableStyles
            runOnUiThread {
                val sortedCounts = counts.entries.sortedBy { it.key }
                val countText = sortedCounts.joinToString("\n") { "• ${it.key}: ${it.value}" }
                val styleText = if (styles.isEmpty()) "Bu arşivde .STY veya .STG bulunamadı." else
                    "${styles.size} STYLE BANKASI/DOSYASI\n\n" + styles.take(100).joinToString("\n\n") { style ->
                        val names = style.labels.take(12).joinToString(" · ")
                        val decodedNames = style.decodedLabels.joinToString(" · ")
                        "${style.path}\n${formatBytes(style.bytes)} · çözülen OC31 blokları: ${style.decodedBlocks}${if (names.isNotBlank()) "\nDosya etiketleri: $names" else ""}${if (decodedNames.isNotBlank()) "\nAçılmış bloklardan etiketler: $decodedNames" else ""}"
                    } + if (styles.size > 100) "\n\n… ve ${styles.size - 100} dosya daha" else ""
                stylesList.text = styleText
                midiInfo.text = "SET ARŞİVİ ANALİZİ\n• Dosya girdisi: $entries\n• Açılan veri toplamı: ${formatBytes(totalBytes)}\n\nUZANTI SAYIMI\n$countText\n\nÖRNEK SOUND/PERFORMANCE DOSYALARI\n" + sounds.take(12).joinToString("\n") +
                    "\n\nNot: Dosya adlarını ve okunabilir metin etiketlerini çıkarmak, Korg'un ikili biçimini bütünüyle çözmek anlamına gelmez. Bu analiz tek başına Style çalma özelliği sağlamaz."
                status.text = "Analiz tamamlandı · $entries dosya · ${styles.size} Style dosyası" + (foundStyleMidi?.let { " · seçili: $selectedStyleName · ${it.noteCount} nota" } ?: " · gömülü MIDI bulunamadı")
            }
        }.start()
    }

    private data class StyleInfo(val path: String, val bytes: Int, val labels: List<String>, val decodedBlocks: Int, val decodedLabels: List<String>)

    private fun extractLabels(bytes: ByteArray): List<String> {
        val result = LinkedHashSet<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.length >= 4) {
                val value = current.toString().trim().replace(Regex("\\s+"), " ")
                if (value.length >= 4 && value.any { it.isLetter() } && !value.equals("KORF", true)) result.add(value)
            }
            current.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xff
            if (c in 32..126) current.append(c.toChar()) else flush()
        }
        flush()
        return result.take(40).toList()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun queryName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "Seçilen dosya"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0) ?: name
        }
        return name
    }

    private fun showMidiDevices() {
        val devices = midiManager?.devices ?: emptyArray()
        val lines = devices.mapIndexed { index, device ->
            val name = device.info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI cihazı"
            "${index + 1}. $name · uygulamanın gönderebileceği giriş portu: ${device.inputPortCount} · cihaz çıkış portu: ${device.outputPortCount}"
        }
        midiInfo.text = if (devices.isEmpty()) {
            "Henüz MIDI cihazı görünmüyor. Telefon USB-OTG desteklemeli; klavyeyi USB-MIDI adaptörüyle bağla ve Yenile'ye dokun."
        } else "GÖRÜNEN MIDI CİHAZLARI\n${lines.joinToString("\n")}\n\nTest notası, seçtiğin cihazın ilk giriş portuna gönderilir."
        status.text = "Korg Player · görünen MIDI cihazı: ${devices.size}"
    }

    private fun chooseMidiOutputAndTest() {
        val devices = midiManager?.devices?.filter { it.inputPortCount > 0 } ?: emptyList()
        if (devices.isEmpty()) {
            Toast.makeText(this, "MIDI giriş portu olan cihaz bulunamadı. Önce klavyeyi bağla ve cihazları yenile.", Toast.LENGTH_LONG).show()
            showMidiDevices()
            return
        }
        val labels = devices.map { it.info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI cihazı" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Test notasının gönderileceği cihaz")
            .setItems(labels) { _, which -> openDeviceAndPlay(devices[which], labels[which]) }
            .setNegativeButton("İptal", null).show()
    }

    private fun openDeviceAndPlay(info: MidiDeviceInfo, name: String) {
        val manager = midiManager ?: return
        status.text = "$name açılıyor…"
        manager.openDevice(info, { device ->
            if (device == null) {
                runOnUiThread { Toast.makeText(this, "MIDI cihazı açılamadı.", Toast.LENGTH_LONG).show() }
                return@openDevice
            }
            try { midiInput?.close() } catch (_: Exception) { }
            try { midiDevice?.close() } catch (_: Exception) { }
            midiDevice = device
            midiInput = device.openInputPort(0)
            selectedDeviceName = name
            runOnUiThread {
                val port = midiInput
                if (port == null) {
                    Toast.makeText(this, "Cihazın giriş portu açılamadı.", Toast.LENGTH_LONG).show()
                } else {
                    try {
                        port.send(byteArrayOf(0x90.toByte(), 60, 100), 0, 3, System.nanoTime())
                        handler.postDelayed({
                            try { port.send(byteArrayOf(0x80.toByte(), 60, 0), 0, 3, System.nanoTime()) } catch (_: Exception) { }
                        }, 500)
                        Toast.makeText(this, "$name cihazına C4 test notası gönderildi.", Toast.LENGTH_LONG).show()
                        status.text = "MIDI test notası gönderildi · $name"
                    } catch (e: Exception) {
                        Toast.makeText(this, "MIDI gönderimi başarısız: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }, handler)
    }

    override fun onDestroy() {
        rhythmPlaying = false
        handler.removeCallbacks(rhythmTick)
        try { midiInput?.close() } catch (_: Exception) { }
        try { midiDevice?.close() } catch (_: Exception) { }
        try { audioEngine.close() } catch (_: Exception) { }
        super.onDestroy()
    }
}
