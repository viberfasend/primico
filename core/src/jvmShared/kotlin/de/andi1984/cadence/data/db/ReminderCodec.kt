package de.andi1984.cadence.data.db

import de.andi1984.cadence.domain.reminder.Reminder
import de.andi1984.cadence.domain.reminder.ReminderFormat

/**
 * The `reminders` column: a task's reminders packed into one TEXT field, `v1;`-prefixed with
 * entries separated by `|` — `v1;d0@09:00|m30|t2026-10-26T09:00~r10x3`. The per-entry encoding is
 * [ReminderFormat]'s, shared with the alarm slots the planner derives, so a column value and a
 * slot always agree on what a reminder is.
 *
 * A private storage detail, exactly like [RecurrenceCodec] and [TagIdsCodec] beside it — the
 * *published* shapes are `BackupTask.reminders` and `RemoteTask.reminders`, both real JSON
 * arrays. Nothing outside this file should look at the raw string.
 *
 * Malformed input decodes to an empty list and never throws: a broken column costs a task its
 * reminders, never the whole task list. Unknown trigger kinds are skipped entry by entry, so a
 * future `Location` kind in a newer version's column does not break an older install.
 */
object ReminderCodec {

    private const val VERSION = "v1"
    private const val ENTRY_SEPARATOR = "|"

    /** The empty string for a task with no reminders, so the NOT NULL column stays clean. */
    fun encode(reminders: List<Reminder>): String {
        if (reminders.isEmpty()) return ""
        return VERSION + ";" + reminders.distinct().joinToString(ENTRY_SEPARATOR) { ReminderFormat.encode(it) }
    }

    /** Blank, wrong-version and unrecognisable input all decode to no reminders. */
    fun decode(raw: String?): List<Reminder> {
        if (raw.isNullOrBlank()) return emptyList()
        if (!raw.startsWith(VERSION + ";")) return emptyList()
        return raw.removePrefix(VERSION + ";")
            .split(ENTRY_SEPARATOR)
            .mapNotNull { ReminderFormat.decode(it.trim()) }
            .distinct()
    }
}
