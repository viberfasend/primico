package de.andi1984.cadence.domain.export

import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.reminder.Reminder
import de.andi1984.cadence.domain.reminder.ReminderTrigger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * RFC 5545 iCalendar encoder for dated tasks.
 *
 * One VEVENT per dated task (has dueDate). Tasks without a due date are skipped.
 * All-day events (no dueTime) use VALUE=DATE and DTEND the next day.
 * Timed events use DTSTART/DTEND with the dueTime, or DTSTART only when dueTime is present.
 * One VALARM per entry in Task.reminders.
 * UID derived from the task id so re-import updates rather than duplicates.
 * STATUS:COMPLETED for completed tasks (with an option to exclude them).
 * Tombstoned tasks never appear.
 *
 * Timezone decision: Use floating local time (no TZID) for simplicity.
 * This means the calendar application will interpret times in its own timezone.
 * This is the most compatible approach and matches how most calendar apps handle
 * events without explicit timezone information.
 */
object IcsCodec {

    /**
     * The PRODID for this application.
     * Format: -//<vendor>//<product>//<version>//<language>
     */
    const val PRODID = "-//Andreas Sander//Primico//1.0//EN"

    /** Line folding threshold: 75 octets as per RFC 5545. */
    private const val LINE_FOLD_THRESHOLD = 75

    /** CRLF line ending as required by RFC 5545. */
    private const val CRLF = "\r\n"

    /** Date formatter for DATE values (YYYYMMDD). */
    private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** Date-time formatter for floating times (YYYYMMDDTHHmmss). */
    private val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /** ISO instant formatter for DTSTAMP. */
    private val ISO_INSTANT_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    /**
     * Encodes a list of tasks to an iCalendar (.ics) string.
     *
     * @param tasks The tasks to encode. Only dated tasks (with dueDate) are included.
     * @param exportedAt The timestamp for the DTSTAMP property.
     * @param includeCompleted Whether to include completed tasks in the output.
     * @return The iCalendar formatted string.
     */
    fun encode(
        tasks: List<Task>,
        exportedAt: Instant,
        includeCompleted: Boolean = true,
    ): String {
        val datedTasks = tasks.filter { it.dueDate != null && it.deletedAt == null }
        val filteredTasks = if (includeCompleted) {
            datedTasks
        } else {
            datedTasks.filter { it.completedAt == null }
        }

        if (filteredTasks.isEmpty()) {
            // Return minimal valid iCalendar with no events
            return buildString {
                append("BEGIN:VCALENDAR").append(CRLF)
                append("VERSION:2.0").append(CRLF)
                append("PRODID:$PRODID").append(CRLF)
                append("CALSCALE:GREGORIAN").append(CRLF)
                append("METHOD:PUBLISH").append(CRLF)
                append("END:VCALENDAR").append(CRLF)
            }
        }

        val builder = StringBuilder()

        // Calendar header
        builder.append("BEGIN:VCALENDAR").append(CRLF)
        builder.append("VERSION:2.0").append(CRLF)
        builder.append("PRODID:").append(PRODID).append(CRLF)
        builder.append("CALSCALE:GREGORIAN").append(CRLF)
        builder.append("METHOD:PUBLISH").append(CRLF)

        // Add each task as a VEVENT
        for (task in filteredTasks) {
            appendEvent(task, exportedAt, builder)
        }

        // Calendar footer
        builder.append("END:VCALENDAR").append(CRLF)

        return foldLines(builder.toString())
    }

    /**
     * Appends a single VEVENT for the given task.
     */
    private fun appendEvent(task: Task, exportedAt: Instant, builder: StringBuilder) {
        val dueDate = task.dueDate ?: return
        val isAllDay = task.dueTime == null

        builder.append("BEGIN:VEVENT").append(CRLF)

        // UID: derived from task id to enable re-import updates
        builder.append("UID:").append(deriveUid(task.id)).append(CRLF)

        // DTSTAMP: when this event was created (the export time)
        builder.append("DTSTAMP:").append(formatIsoInstant(exportedAt)).append(CRLF)

        // SUMMARY: task title
        builder.append("SUMMARY:").append(escapeText(task.title)).append(CRLF)

        // DESCRIPTION: task notes if present
        task.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            builder.append("DESCRIPTION:").append(escapeText(notes)).append(CRLF)
        }

        // STATUS: COMPLETED for completed tasks
        if (task.completedAt != null) {
            builder.append("STATUS:COMPLETED").append(CRLF)
        }

        if (isAllDay) {
            // All-day event: use VALUE=DATE
            builder.append("DTSTART;VALUE=DATE:").append(formatDate(dueDate)).append(CRLF)
            // DTEND is the next day for all-day events
            builder.append("DTEND;VALUE=DATE:").append(formatDate(dueDate.plusDays(1))).append(CRLF)
        } else {
            val dueTime = task.dueTime!!
            val startDateTime = LocalDateTime.of(dueDate, dueTime)

            // For timed events without a specific duration, we use DTSTART only
            // Some calendar apps expect DTEND, so we'll set it to the same as DTSTART
            // for tasks that are due at a specific time (point-in-time events)
            builder.append("DTSTART:").append(formatDateTime(startDateTime)).append(CRLF)
            builder.append("DTEND:").append(formatDateTime(startDateTime)).append(CRLF)
        }

        // Add reminders as VALARM components
        task.reminders.forEach { reminder ->
            appendAlarm(task, reminder, builder)
        }

        builder.append("END:VEVENT").append(CRLF)
    }

    /**
     * Appends a VALARM component for the given reminder.
     *
     * Mapping from ADR 0006 reminder types to iCalendar TRIGGER:
     * - ReminderTrigger.BeforeDueTime(minutes): TRIGGER:-PT<minutes>M (relative to DTSTART)
     * - ReminderTrigger.DaysBeforeDue(days, at): TRIGGER:-P<days>DT<at>H<at>M<at>S (relative to DTSTART date)
     * - ReminderTrigger.At(dateTime): TRIGGER absolute date-time
     */
    private fun appendAlarm(task: Task, reminder: Reminder, builder: StringBuilder) {
        val dueDate = task.dueDate ?: return
        val trigger = when (val trigger = reminder.trigger) {
            is ReminderTrigger.BeforeDueTime -> {
                // Relative trigger: N minutes before DTSTART
                // Negative minutes = after, positive = before
                val minutes = trigger.minutes
                if (minutes >= 0) {
                    "-PT${minutes}M"
                } else {
                    "PT${-minutes}M"
                }
            }
            is ReminderTrigger.DaysBeforeDue -> {
                // Relative trigger: N days before the due date at the specified time
                val days = trigger.days
                val time = trigger.at
                val hours = time.hour
                val minutes = time.minute
                val seconds = time.second
                if (days >= 0) {
                    "-P${days}DT${hours}H${minutes}M${seconds}S"
                } else {
                    "P${-days}DT${hours}H${minutes}M${seconds}S"
                }
            }
            is ReminderTrigger.At -> {
                // Absolute trigger: fixed date-time
                // Convert to ISO format for TRIGGER
                val dateTime = trigger.dateTime
                val zoneId = ZoneId.systemDefault()
                val zoned = ZonedDateTime.of(dateTime, zoneId)
                "${formatIsoInstant(zoned.toInstant())}"
            }
        }

        builder.append("BEGIN:VALARM").append(CRLF)
        builder.append("TRIGGER:").append(trigger).append(CRLF)
        builder.append("ACTION:DISPLAY").append(CRLF)
        builder.append("DESCRIPTION:").append(escapeText(task.title)).append(CRLF)
        builder.append("END:VALARM").append(CRLF)
    }

    /**
     * Derives a UID from the task ID.
     * Uses a simple transformation to create a valid iCalendar UID.
     * iCalendar UIDs should be globally unique and are typically in the form:
     * <uuid>@<domain> or <timestamp>-<random>@<domain>
     */
    private fun deriveUid(taskId: String): String {
        // If taskId looks like a UUIDv7, use it directly with domain suffix
        if (taskId.length >= 20 && taskId.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
            return "$taskId@primico.app"
        }
        // For other formats, prefix with a stable prefix
        return "task-$taskId@primico.app"
    }

    /**
     * Formats a LocalDate as YYYYMMDD.
     */
    private fun formatDate(date: LocalDate): String {
        return date.format(DATE_FORMATTER)
    }

    /**
     * Formats a LocalDateTime as YYYYMMDDTHHmmss (floating time, no timezone).
     */
    private fun formatDateTime(dateTime: LocalDateTime): String {
        return dateTime.format(DATE_TIME_FORMATTER)
    }

    /**
     * Formats an Instant as ISO 8601 basic format with UTC timezone indicator.
     * RFC 5545 requires basic format (without hyphens and colons) for DATE-TIME values.
     */
    private fun formatIsoInstant(instant: Instant): String {
        // Convert to UTC and format as yyyyMMdd'T'HHmmss'Z'
        val utcDateTime = instant.atZone(ZoneId.of("UTC"))
        return String.format("%04d%02d%02dT%02d%02d%02dZ",
            utcDateTime.year,
            utcDateTime.monthValue,
            utcDateTime.dayOfMonth,
            utcDateTime.hour,
            utcDateTime.minute,
            utcDateTime.second)
    }

    /**
     * Folds long lines according to RFC 5545.
     * Lines longer than 75 octets should be folded by inserting CRLF followed by
     * a single space or tab character.
     */
    private fun foldLines(input: String): String {
        val lines = input.split(CRLF)
        val folded = StringBuilder()

        for (line in lines) {
            if (line.isEmpty()) {
                folded.append(CRLF)
                continue
            }

            var remaining = line
            var isFirstChunk = true

            while (remaining.isNotEmpty()) {
                val maxLength = if (isFirstChunk) LINE_FOLD_THRESHOLD else LINE_FOLD_THRESHOLD - 1
                isFirstChunk = false

                // If the remaining fits, use it as-is
                if (remaining.length <= maxLength) {
                    folded.append(remaining)
                    break
                }

                // Find the last space within the threshold for folding
                var foldPosition = maxLength
                while (foldPosition > 0 && remaining[foldPosition - 1] != ' ' && remaining[foldPosition - 1] != '\t') {
                    foldPosition--
                }

                // If we couldn't find a space, fold at the threshold anyway
                // RFC 5545 says: "A long line can also be split between any two characters"
                if (foldPosition == 0) {
                    foldPosition = maxLength
                }

                val chunk = remaining.substring(0, foldPosition)
                remaining = remaining.substring(foldPosition)

                folded.append(chunk).append(CRLF)

                // Add continuation indicator (space) for the next line
                if (remaining.isNotEmpty()) {
                    folded.append(" ")
                }
            }
        }

        // Ensure the string ends with CRLF
        if (!folded.toString().endsWith(CRLF)) {
            folded.append(CRLF)
        }

        return folded.toString()
    }

    /**
     * Escapes text for iCalendar TEXT values according to RFC 5545.
     * The following characters must be escaped:
     * - Backslash (\ -> \\\)
     * - Semicolon (; -> \;)
     * - Comma (, -> \,)
     * - Newline (\n -> \\n)
     * - Carriage return (\r -> \\n)
     * Note: RFC 5545 specifies \n for both LF and CRLF.
     */
    private fun escapeText(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\r\n", "\\n")  // CRLF first
            .replace("\r", "\\n")    // CR alone
            .replace("\n", "\\n")    // LF alone
            .replace(";", "\\;")
            .replace(",", "\\,")
    }

    /**
     * Result of encoding, for testing purposes.
     */
    data class EncodeResult(
        val icsContent: String,
        val eventCount: Int,
        val alarmCount: Int,
    )

    /**
     * Encodes tasks and returns detailed result information.
     * Useful for testing.
     */
    fun encodeWithDetails(
        tasks: List<Task>,
        exportedAt: Instant,
        includeCompleted: Boolean = true,
    ): EncodeResult {
        val datedTasks = tasks.filter { it.dueDate != null && it.deletedAt == null }
        val filteredTasks = if (includeCompleted) {
            datedTasks
        } else {
            datedTasks.filter { it.completedAt == null }
        }

        val alarmCount = filteredTasks.sumOf { it.reminders.size }

        val icsContent = encode(tasks, exportedAt, includeCompleted)

        return EncodeResult(
            icsContent = icsContent,
            eventCount = filteredTasks.size,
            alarmCount = alarmCount,
        )
    }
}
