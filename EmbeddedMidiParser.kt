package com.example.korgplayer

/** Extracts standard MIDI chunks embedded as STG container leaves and parses note/program events. */
object EmbeddedMidiParser {
    data class Event(val tick: Long, val status: Int, val a: Int, val b: Int)
    data class Sequence(val events: List<Event>, val ppq: Int, val tempoMicros: Int, val trackCount: Int) {
        val noteCount: Int get() = events.count { (it.status and 0xF0) == 0x90 && it.b > 0 }
    }

    /** Extract and merge all MIDI leaves belonging to a named KORG scene. */
    fun extractScene(container: ByteArray, sceneName: String): Sequence? {
        val marker = findAscii(container, "Branch\u0000$sceneName.scn")
        if (marker < 0) return null
        val knownScenes = listOf("Intro1", "Intro2", "Intro3", "CountIn", "Var1", "Var2", "Var3", "Var4", "Fill1", "Fill2", "Fill3", "Break", "Ending1", "Ending2", "ChordSequence", "KbdSet1")
        val next = knownScenes.map { findAscii(container, "Branch\u0000$it.scn", marker + 1) }
            .filter { it > marker }
        val sceneEnd = next.minOrNull() ?: container.size
        val parts = ArrayList<Sequence>()
        var p = marker
        while (p + 14 <= sceneEnd) {
            val hit = findAscii(container, "MThd", p, sceneEnd)
            if (hit < 0) break
            try { parseAt(container, hit).let { if (it.noteCount > 0) parts.add(it) } } catch (_: Exception) { }
            p = hit + 4
        }
        if (parts.isEmpty()) return null
        val ppq = parts.first().ppq
        val compatible = parts.filter { it.ppq == ppq }
        val merged = compatible.flatMap { it.events }.sortedBy { it.tick }
        return if (merged.any { (it.status and 0xF0) == 0x90 && it.b > 0 })
            Sequence(merged, ppq, compatible.first().tempoMicros, compatible.sumOf { it.trackCount }) else null
    }

    /** Default to Var1; if a file has no scene tree, fall back to its richest embedded SMF. */
    fun extractAndParse(container: ByteArray): Sequence? {
        extractScene(container, "Var1")?.let { return it }
        var p = 0
        var best: Sequence? = null
        while (p + 14 <= container.size) {
            val hit = findAscii(container, "MThd", p)
            if (hit < 0) break
            try {
                val candidate = parseAt(container, hit)
                if (best == null || candidate.noteCount > best!!.noteCount) best = candidate
            } catch (_: Exception) { }
            p = hit + 4
        }
        return best
    }

    private fun findAscii(b: ByteArray, text: String, from: Int = 0, until: Int = b.size): Int {
        var p = from.coerceAtLeast(0)
        val limit = minOf(until, b.size)
        while (p + text.length <= limit) {
            if (ascii(b, p, text)) return p
            p++
        }
        return -1
    }

    private fun parseAt(b: ByteArray, start: Int): Sequence {
        fun u8(i: Int) = b[i].toInt() and 255
        fun be32(i: Int) = (u8(i).toLong() shl 24) or (u8(i+1).toLong() shl 16) or (u8(i+2).toLong() shl 8) or u8(i+3).toLong()
        require(ascii(b, start, "MThd"))
        val hlen = be32(start+4).toInt(); require(hlen >= 6 && start+8+hlen <= b.size)
        val format = (u8(start+8) shl 8) or u8(start+9)
        val tracks = (u8(start+10) shl 8) or u8(start+11)
        val rawDivision = (u8(start+12) shl 8) or u8(start+13)
        val ppq = if ((rawDivision and 0x8000) == 0) rawDivision.coerceAtLeast(1) else 480
        var pos = start + 8 + hlen
        val all = ArrayList<Event>(); var tempo = 500000; var actualTracks = 0
        repeat(tracks.coerceAtMost(128)) {
            if (pos + 8 > b.size) return@repeat
            if (!ascii(b,pos,"MTrk")) {
                var found = pos; while(found+8 <= b.size && !ascii(b,found,"MTrk") && found-pos < 4096) found++
                if (found+8 > b.size || !ascii(b,found,"MTrk")) return@repeat
                pos = found
            }
            val len = be32(pos+4).coerceAtMost((b.size-pos-8).toLong()).toInt(); var i=pos+8; val end=i+len; pos=end; actualTracks++
            var tick=0L; var running=0; var guard=0
            fun vlq(): Long { var v=0L; var n=0; do { require(i<end && n++<4); val x=u8(i++); v=(v shl 7) or (x and 127).toLong(); if(x<128) break } while(true); return v }
            while(i<end && guard++<2_000_000) {
                tick += vlq(); if(i>=end) break
                var st=u8(i++)
                if(st<128) { require(running in 0x80..0xEF); i--; st=running } else if(st<0xF0) running=st
                if(st==0xFF) { if(i>=end) break; val type=u8(i++); val n=vlq().toInt(); if(n<0 || i+n>end) break
                    if(type==0x51 && n>=3) tempo=(u8(i) shl 16) or (u8(i+1) shl 8) or u8(i+2)
                    i+=n; if(type==0x2F) break
                } else if(st==0xF0 || st==0xF7) { val n=vlq().toInt(); i=(i+n).coerceAtMost(end) }
                else if(st in 0x80..0xEF) { val hi=st and 0xF0; val a=u8(i++); val b2=if(hi==0xC0||hi==0xD0) 0 else u8(i++); if(i>end) break
                    if(hi==0x80 || hi==0x90 || hi==0xC0 || hi==0xB0) all.add(Event(tick,st,a,b2))
                } else break
            }
        }
        require(all.isNotEmpty()) { "MIDI has no supported events" }
        return Sequence(all.sortedBy { it.tick }, ppq, tempo, actualTracks)
    }
    private fun ascii(b: ByteArray, p: Int, s: String): Boolean = p>=0 && p+s.length<=b.size && s.indices.all { b[p+it].toInt().toChar()==s[it] }
}
