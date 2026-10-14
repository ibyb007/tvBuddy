package com.example.data.parser

import android.util.Xml
import com.example.data.local.entity.ProgramEntity
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

object XmltvParser {

    private val xmltvDateFormats = listOf(
        SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
        SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    )

    fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        val clean = dateStr.trim()
        for (format in xmltvDateFormats) {
            try {
                val date = format.parse(clean)
                if (date != null) return date.time
            } catch (_: Exception) {}
        }
        return 0L
    }

    fun parse(inputStream: InputStream): List<ProgramEntity> {
        val parser = Xml.newPullParser()
        parser.setInput(inputStream, "UTF-8")
        return parseXmlPull(parser)
    }

    fun parseString(xmlContent: String): List<ProgramEntity> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xmlContent))
        return parseXmlPull(parser)
    }

    private fun parseXmlPull(parser: XmlPullParser): List<ProgramEntity> {
        val programs = mutableListOf<ProgramEntity>()
        var eventType = parser.eventType

        var currentChannelId: String? = null
        var currentStart: Long = 0L
        var currentStop: Long = 0L
        var currentTitle = ""
        var currentDesc = ""
        var currentCategory = ""
        var currentIcon: String? = null

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase()) {
                        "programme" -> {
                            currentChannelId = parser.getAttributeValue(null, "channel")
                            currentStart = parseDate(parser.getAttributeValue(null, "start"))
                            currentStop = parseDate(parser.getAttributeValue(null, "stop"))
                            currentTitle = ""
                            currentDesc = ""
                            currentCategory = ""
                            currentIcon = null
                        }
                        "title" -> {
                            currentTitle = parser.nextText().trim()
                        }
                        "desc" -> {
                            currentDesc = parser.nextText().trim()
                        }
                        "category" -> {
                            currentCategory = parser.nextText().trim()
                        }
                        "icon" -> {
                            currentIcon = parser.getAttributeValue(null, "src")
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("programme", ignoreCase = true)) {
                        if (!currentChannelId.isNullOrBlank() && currentTitle.isNotBlank()) {
                            programs.add(
                                ProgramEntity(
                                    epgChannelId = currentChannelId,
                                    title = currentTitle,
                                    description = currentDesc.ifBlank { null },
                                    category = currentCategory.ifBlank { null },
                                    startTimeMs = currentStart,
                                    endTimeMs = currentStop,
                                    iconUrl = currentIcon
                                )
                            )
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        return programs
    }
}
