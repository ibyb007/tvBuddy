package com.example.ui.components

import com.example.data.local.entity.ProgramEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

const val HOUR_MS = 3_600_000L
const val HALF_HOUR_MS = 1_800_000L

/**
 * One block of a channel's schedule. When the channel has no EPG data for a period the gap is
 * filled with hour-long placeholder slots ([program] == null) that read "No information",
 * so the guide, banner and channel list always have something to show and a progress bar to draw.
 */
data class EpgSlot(val startMs: Long, val endMs: Long, val program: ProgramEntity?) {
    val title: String get() = program?.title ?: NO_INFO
    val description: String? get() = program?.description?.takeIf { it.isNotBlank() }

    fun contains(now: Long) = now in startMs until endMs

    fun progress(now: Long): Float {
        val d = (endMs - startMs).toFloat()
        return if (d <= 0f) 0f else ((now - startMs) / d).coerceIn(0f, 1f)
    }

    fun minutesLeft(now: Long): Long = ((endMs - now) / 60_000L).coerceAtLeast(0)

    companion object {
        const val NO_INFO = "No information"
    }
}

fun floorToHour(ms: Long): Long = Calendar.getInstance().run {
    timeInMillis = ms
    set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    timeInMillis
}

fun floorToHalfHour(ms: Long): Long {
    val h = floorToHour(ms)
    return if (ms - h >= HALF_HOUR_MS) h + HALF_HOUR_MS else h
}

private fun nextHour(ms: Long): Long = Calendar.getInstance().run {
    timeInMillis = floorToHour(ms)
    add(Calendar.HOUR_OF_DAY, 1)
    timeInMillis
}

/**
 * Builds a gap-free list of slots covering [fromMs, toMs).
 * [programs] must be sorted by start time. Gaps become placeholders that end on each hour boundary
 * (10:00-11:00, 11:00-12:00, ...).
 */
fun buildSlots(programs: List<ProgramEntity>, fromMs: Long, toMs: Long): List<EpgSlot> {
    val out = ArrayList<EpgSlot>()
    var cursor = fromMs

    fun fillGap(until: Long) {
        while (cursor < until) {
            val end = minOf(nextHour(cursor), until)
            out += EpgSlot(cursor, end, null)
            cursor = end
        }
    }

    for (p in programs) {
        if (p.endTimeMs <= cursor) continue
        if (p.startTimeMs >= toMs) break
        if (p.startTimeMs > cursor) fillGap(p.startTimeMs)
        val start = if (out.isEmpty()) p.startTimeMs else maxOf(p.startTimeMs, out.last().endMs)
        if (p.endTimeMs > start) {
            out += EpgSlot(start, p.endTimeMs, p)
            cursor = p.endTimeMs
        }
    }
    fillGap(toMs)
    return out
}

fun List<EpgSlot>.indexAt(timeMs: Long): Int {
    val i = indexOfFirst { timeMs >= it.startMs && timeMs < it.endMs }
    return if (i >= 0) i else if (isNotEmpty() && timeMs < first().startMs) 0 else lastIndex
}

private val clockFormat get() = SimpleDateFormat("hh:mm a", Locale.getDefault())

/** "10:00 am" */
fun fmtClock(ms: Long): String = clockFormat.format(Date(ms)).lowercase(Locale.getDefault())
