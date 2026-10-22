package com.example.data.parser

import android.util.Xml
import com.example.data.local.entity.ProgramEntity
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.StringReader

/** A `<channel>` element of an XMLTV guide. */
data class XmltvChannel(val id: String, val displayNames: List<String>, val iconUrl: String?)

object XmltvParser {

    private val dateRegex = Regex("^(\\d{4})(\\d{2})(\\d{2})(\\d{2})?(\\d{2})?(\\d{2})?\\s*(?:(Z)|([+-])(\\d{2}):?(\\d{2}))?$", RegexOption.IGNORE_CASE)
    private val isoRegex = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})(?:\\.\\d+)?\\s*(?:(Z)|([+-])(\\d{2}):?(\\d{2}))?$", RegexOption.IGNORE_CASE)

    /** Days since 1970-01-01 of a civil date (proleptic Gregorian); thread-safe, no SimpleDateFormat. */
    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era.toLong() * 146097 + doe - 719468
    }

    /**
     * XMLTV times: "20261014120000 +0200", "20261014120000" (UTC), "202610141200", "20261014120000Z" and the ISO
     * forms some feeds use. Returns epoch millis, 0 when unparseable.
     */
    fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        val s = dateStr.trim()
        val g = (dateRegex.find(s) ?: isoRegex.find(s))?.groupValues ?: return 0L
        return try {
            val days = daysFromCivil(g[1].toInt(), g[2].toInt(), g[3].toInt())
            val hh = g[4].ifEmpty { "0" }.toInt()
            val mm = g[5].ifEmpty { "0" }.toInt()
            val ss = g[6].ifEmpty { "0" }.toInt()
            var utc = (days * 86400L + hh * 3600L + mm * 60L + ss) * 1000L
            if (g[8].isNotEmpty()) {
                val offset = (g[9].toInt() * 60 + g[10].toInt()) * 60_000L
                utc -= if (g[8] == "-") -offset else offset
            }
            utc
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Streams an XMLTV document: channels and programmes are handed over in batches while the document is read, so
     * a guide with millions of programmes never sits in memory. Programmes that ended before [minEndTimeMs] or
     * start after [maxStartTimeMs] are dropped.
     */
    fun parseStreaming(
        inputStream: InputStream,
        storeDescriptions: Boolean = true,
        minEndTimeMs: Long = 0L,
        maxStartTimeMs: Long = Long.MAX_VALUE,
        batchSize: Int = 2000,
        onChannels: (List<XmltvChannel>) -> Unit = {},
        onPrograms: (List<ProgramEntity>) -> Unit
    ) {
        val parser = Xml.newPullParser()
        parser.setInput(inputStream, null) // let the XML declaration decide the encoding
        run(parser, storeDescriptions, minEndTimeMs, maxStartTimeMs, batchSize, onChannels, onPrograms)
    }

    fun parse(
        inputStream: InputStream,
        storeDescriptions: Boolean = true,
        minEndTimeMs: Long = 0L
    ): List<ProgramEntity> {
        val all = ArrayList<ProgramEntity>()
        parseStreaming(inputStream, storeDescriptions, minEndTimeMs, onPrograms = { all.addAll(it) })
        return all
    }

    fun parseString(
        xmlContent: String,
        storeDescriptions: Boolean = true,
        minEndTimeMs: Long = 0L
    ): List<ProgramEntity> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xmlContent))
        val all = ArrayList<ProgramEntity>()
        run(parser, storeDescriptions, minEndTimeMs, Long.MAX_VALUE, 2000, {}, { all.addAll(it) })
        return all
    }

    private fun run(
        parser: XmlPullParser,
        storeDescriptions: Boolean,
        minEndTimeMs: Long,
        maxStartTimeMs: Long,
        batchSize: Int,
        onChannels: (List<XmltvChannel>) -> Unit,
        onPrograms: (List<ProgramEntity>) -> Unit
    ) {
        var programs = ArrayList<ProgramEntity>(batchSize)
        var channels = ArrayList<XmltvChannel>(200)

        var inProgramme = false
        var inChannel = false
        var chId = ""
        var chNames = ArrayList<String>(2)
        var chIcon: String? = null

        var pChannel: String? = null
        var pStart = 0L
        var pStop = 0L
        var pTitle = ""
        var pDesc = ""
        var pCategory = ""
        var pIcon: String? = null

        fun flushChannels() {
            if (channels.isNotEmpty()) { onChannels(channels); channels = ArrayList(200) }
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "channel" -> {
                        inChannel = true
                        chId = parser.getAttributeValue(null, "id").orEmpty().trim()
                        chNames = ArrayList(2)
                        chIcon = null
                    }
                    "display-name" -> if (inChannel) {
                        val t = parser.nextText().trim()
                        if (t.isNotEmpty()) chNames.add(t)
                    }
                    "programme" -> {
                        if (!inProgramme) flushChannels() // all <channel> elements precede the programmes
                        inProgramme = true
                        pChannel = parser.getAttributeValue(null, "channel")?.trim()
                        pStart = parseDate(parser.getAttributeValue(null, "start"))
                        pStop = parseDate(parser.getAttributeValue(null, "stop"))
                        pTitle = ""; pDesc = ""; pCategory = ""; pIcon = null
                    }
                    "title" -> if (inProgramme) {
                        val t = parser.nextText().trim()
                        if (pTitle.isEmpty()) pTitle = t // first language wins
                    }
                    "sub-title" -> if (inProgramme) {
                        val t = parser.nextText().trim()
                        if (pTitle.isNotEmpty() && t.isNotEmpty() && pDesc.isEmpty() && storeDescriptions) pDesc = t
                    }
                    "desc" -> if (inProgramme) {
                        val t = parser.nextText().trim()
                        if (storeDescriptions && (pDesc.isEmpty() || t.length > pDesc.length)) pDesc = t
                    }
                    "category" -> if (inProgramme) {
                        val t = parser.nextText().trim()
                        if (pCategory.isEmpty()) pCategory = t
                    }
                    "icon" -> {
                        val src = parser.getAttributeValue(null, "src")?.trim()
                        if (inProgramme) { if (pIcon == null) pIcon = src }
                        else if (inChannel && chIcon == null) chIcon = src
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name.lowercase()) {
                    "channel" -> {
                        if (inChannel && chId.isNotEmpty()) {
                            channels.add(XmltvChannel(chId, chNames, chIcon))
                            if (channels.size >= 500) flushChannels()
                        }
                        inChannel = false
                    }
                    "programme" -> {
                        inProgramme = false
                        // a missing / invalid stop time: assume 30 minutes
                        val stop = if (pStop > pStart) pStop else if (pStart > 0) pStart + 30 * 60_000L else 0L
                        if (!pChannel.isNullOrBlank() && pTitle.isNotBlank() && pStart > 0L &&
                            (minEndTimeMs <= 0L || stop >= minEndTimeMs) && pStart <= maxStartTimeMs
                        ) {
                            programs.add(
                                ProgramEntity(
                                    epgChannelId = pChannel!!,
                                    title = pTitle,
                                    description = if (storeDescriptions) pDesc.ifBlank { null } else null,
                                    category = pCategory.ifBlank { null },
                                    startTimeMs = pStart,
                                    endTimeMs = stop,
                                    iconUrl = pIcon
                                )
                            )
                            if (programs.size >= batchSize) { onPrograms(programs); programs = ArrayList(batchSize) }
                        }
                    }
                }
            }
            event = parser.next()
        }
        flushChannels()
        if (programs.isNotEmpty()) onPrograms(programs)
    }
}
