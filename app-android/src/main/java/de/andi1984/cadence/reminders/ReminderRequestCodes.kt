package de.andi1984.cadence.reminders

import android.content.Context
import de.andi1984.cadence.domain.reminder.ReminderKey
import java.time.Instant

/**
 * The `PendingIntent`/notification request code for a (task id, slot) pair, collision-free
 * by construction: the pair is assigned the next counter value the first time it is seen and that
 * assignment is persisted, rather than derived from a 32-bit [String.hashCode] two different pairs
 * can share. [AlarmReminderScheduler] assigns a code when it schedules an alarm and
 * [ReminderReceiver] looks up the same persisted value when the alarm fires, so both sides always
 * agree.
 *
 * The slot `"0"` — the legacy `reminderTime` alarm that predates slots — keeps the bare task id as
 * its key, exactly as it was before slots existed; every other slot gets `"$taskId:$slot"`, so
 * upgrading changes nothing about a code, or an alarm already armed under one, that predates
 * this format knowing about slots.
 */
object ReminderRequestCodes {

    private const val PREFS_NAME = "cadence_reminder_request_codes"
    private const val KEY_NEXT_CODE = "next_code"
    private const val ACTIVE_SLOTS_PREFIX = "active_slots:"
    /** Written by the pre-slot version of this file; still read, so those alarms are cancelled
     *  rather than left to fire once more after the upgrade. */
    private const val LEGACY_ACTIVE_LEADS_PREFIX = "active_leads:"

    private fun keyFor(taskId: String, slot: String): String =
        if (slot == "0") taskId else "$taskId:$slot"

    @Synchronized
    fun codeFor(context: Context, taskId: String, slot: String): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = keyFor(taskId, slot)
        val existing = prefs.getInt(key, NO_CODE)
        if (existing != NO_CODE) return existing
        val assigned = prefs.getInt(KEY_NEXT_CODE, 1)
        prefs.edit()
            .putInt(key, assigned)
            .putInt(KEY_NEXT_CODE, assigned + 1)
            .apply()
        return assigned
    }

    /** The code already assigned to (taskId, slot), or `null` if it was never scheduled —
     *  never assigns one, so cancelling a slot that was never armed does not grow the store. */
    @Synchronized
    fun existingCodeFor(context: Context, taskId: String, slot: String): Int? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getInt(keyFor(taskId, slot), NO_CODE)
        return if (existing == NO_CODE) null else existing
    }

    /**
     * Every alarm [AlarmReminderScheduler] currently has armed, with the instant each one is set
     * for — the `armed` side of `ReminderReconciler`'s diff, so a sync can tell "still wanted"
     * from "Settings dropped this one" and "moved to another time" without re-deriving history.
     *
     * Persisted per task as `"slot@epochMillis,…"` under `active_slots:`. Entries written by the
     * pre-slot version under `active_leads:` (`"lead@epochMillis,…"`, lead `0` for the old
     * `reminderTime` moment) are read as slots `"0"`, `"20"`, … — which nothing plans any more,
     * so the reconciler cancels them once on upgrade. An entry that carries no `@` was written
     * before the instant was recorded and reads as [Instant.EPOCH]: the reconciler then re-arms
     * it once at its planned instant or, if nothing plans it any more, cancels it.
     */
    @Synchronized
    fun armed(context: Context): Map<ReminderKey, Instant> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val result = LinkedHashMap<ReminderKey, Instant>()
        prefs.all.forEach { (key, value) ->
            if (value !is String) return@forEach
            when {
                key.startsWith(ACTIVE_SLOTS_PREFIX) -> {
                    val taskId = key.removePrefix(ACTIVE_SLOTS_PREFIX)
                    value.split(",").forEach { entry ->
                        val slot = entry.substringBefore('=').takeIf { it.isNotEmpty() } ?: return@forEach
                        val at = entry.substringAfter('=', missingDelimiterValue = "").toLongOrNull()
                            ?.let(Instant::ofEpochMilli) ?: Instant.EPOCH
                        result[ReminderKey(taskId, slot)] = at
                    }
                }
                key.startsWith(LEGACY_ACTIVE_LEADS_PREFIX) -> {
                    val taskId = key.removePrefix(LEGACY_ACTIVE_LEADS_PREFIX)
                    value.split(",").forEach { entry ->
                        val lead = entry.substringBefore('@').toIntOrNull() ?: return@forEach
                        val at = entry.substringAfter('@', missingDelimiterValue = "").toLongOrNull()
                            ?.let(Instant::ofEpochMilli) ?: Instant.EPOCH
                        result[ReminderKey(taskId, lead.toString())] = at
                    }
                }
            }
        }
        return result
    }

    /** Replaces the armed record with [next], writing only the tasks whose entries changed since
     *  [previous] — one edit, however many tasks a sync touched. */
    @Synchronized
    fun setArmed(context: Context, previous: Map<ReminderKey, Instant>, next: Map<ReminderKey, Instant>) {
        val before = previous.entries.groupBy({ it.key.taskId }, { it.key.slot to it.value })
        val after = next.entries.groupBy({ it.key.taskId }, { it.key.slot to it.value })
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        (before.keys + after.keys).forEach { taskId ->
            val entries = after[taskId].orEmpty()
            if (before[taskId].orEmpty().toSet() == entries.toSet()) return@forEach
            if (entries.isEmpty()) {
                editor.remove(ACTIVE_SLOTS_PREFIX + taskId)
            } else {
                val packed = entries.joinToString(",") { (slot, at) -> "$slot=${at.toEpochMilli()}" }
                editor.putString(ACTIVE_SLOTS_PREFIX + taskId, packed)
            }
        }
        editor.apply()
    }

    private const val NO_CODE = 0
}
