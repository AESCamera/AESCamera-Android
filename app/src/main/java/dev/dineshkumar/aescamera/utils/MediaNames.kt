package dev.dineshkumar.aescamera.utils

import android.content.Context
import android.text.format.DateUtils
import dev.dineshkumar.aescamera.R
import java.io.File
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Reads capture times back out of the IMG-/VID-<timestamp> file names. */
object MediaNames {
    private const val PREFIX_LENGTH = 4 // "IMG-" / "VID-"
    private val FORMAT = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)

    fun isVideo(file: File): Boolean = file.name.endsWith(".encv", ignoreCase = true)

    /** The capture time encoded in [file]'s name, else its modification time. */
    fun captureTime(file: File): Long {
        val name = file.name
        if (name.length > PREFIX_LENGTH && (name.startsWith("IMG-") || name.startsWith("VID-"))) {
            val stamp = name.substring(PREFIX_LENGTH).substringBefore('.')
            try {
                synchronized(FORMAT) { FORMAT.parse(stamp) }?.let { return it.time }
            } catch (e: ParseException) {
                // Not one of ours (imported file with a foreign name): fall through.
            }
        }
        return file.lastModified()
    }

    /** "Today", "Yesterday" or the date, for the gallery's section headers. */
    fun dayLabel(context: Context, timeMillis: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = timeMillis }
        val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
        if (sameYear) {
            val dayDelta = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
            if (dayDelta == 0) return context.getString(R.string.today)
            if (dayDelta == 1) return context.getString(R.string.yesterday)
        }
        var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or
                DateUtils.FORMAT_ABBREV_WEEKDAY
        if (!sameYear) flags = flags or DateUtils.FORMAT_SHOW_YEAR
        return DateUtils.formatDateTime(context, timeMillis, flags)
    }

    /** Day boundary (local midnight) of [timeMillis], for grouping. */
    fun dayStart(timeMillis: Long): Long = Calendar.getInstance().apply {
        this.timeInMillis = timeMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** "4 Nov 2026, 18:44" for the viewer title; the raw name for foreign files. */
    fun viewerTitle(context: Context, file: File): String {
        val name = file.name
        if (!(name.startsWith("IMG-") || name.startsWith("VID-"))) {
            return name
        }
        return DateUtils.formatDateTime(
            context, captureTime(file),
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or
                    DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME
        )
    }
}
