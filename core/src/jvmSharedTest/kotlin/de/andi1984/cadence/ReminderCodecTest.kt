package de.andi1984.cadence

import de.andi1984.cadence.data.db.ReminderCodec
import de.andi1984.cadence.domain.reminder.Reminder
import de.andi1984.cadence.domain.reminder.ReminderRepeat
import de.andi1984.cadence.domain.reminder.ReminderTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime

/** The packed `reminders` column: one `v1;`-prefixed string, entries separated by `|`. */
class ReminderCodecTest {

    private val daysBefore = Reminder(ReminderTrigger.DaysBeforeDue(1, LocalTime.of(9, 0)))
    private val minutesBefore = Reminder(ReminderTrigger.BeforeDueTime(30))
    private val at = Reminder(
        ReminderTrigger.At(LocalDateTime.of(2026, 10, 26, 9, 0)),
        ReminderRepeat(10, 3),
    )

    @Test
    fun `a round trip keeps every kind, in order`() {
        val reminders = listOf(daysBefore, minutesBefore, at)
        assertEquals(reminders, ReminderCodec.decode(ReminderCodec.encode(reminders)))
    }

    @Test
    fun `the packed shape is the documented one`() {
        assertEquals(
            "v1;d1@09:00|m30|t2026-10-26T09:00~r10x3",
            ReminderCodec.encode(listOf(daysBefore, minutesBefore, at)),
        )
    }

    @Test
    fun `a repeat without a count encodes the until-done form`() {
        val r = Reminder(ReminderTrigger.DaysBeforeDue(0, LocalTime.of(8, 0)), ReminderRepeat(5, null))
        assertEquals("v1;d0@08:00~r5", ReminderCodec.encode(listOf(r)))
        assertEquals(listOf(r), ReminderCodec.decode("v1;d0@08:00~r5"))
    }

    @Test
    fun `no reminders encodes to the empty string, the NOT NULL column's clean value`() {
        assertEquals("", ReminderCodec.encode(emptyList()))
    }

    @Test
    fun `null, blank and wrong-version input decodes to no reminders and never throws`() {
        assertEquals(emptyList<Reminder>(), ReminderCodec.decode(null))
        assertEquals(emptyList<Reminder>(), ReminderCodec.decode(""))
        assertEquals(emptyList<Reminder>(), ReminderCodec.decode("   "))
        assertEquals(emptyList<Reminder>(), ReminderCodec.decode("v2;d0@09:00"))
        assertEquals(emptyList<Reminder>(), ReminderCodec.decode("d0@09:00"))
    }

    @Test
    fun `malformed entries are skipped, the rest survive`() {
        assertEquals(
            listOf(minutesBefore),
            ReminderCodec.decode("v1;nonsense|m30|dX@09:00|"),
        )
    }

    @Test
    fun `an unknown trigger kind is skipped, so a newer column does not break an older install`() {
        assertEquals(
            listOf(daysBefore),
            ReminderCodec.decode("v1;l42.3,7.9|d1@09:00"),
        )
    }

    @Test
    fun `identical reminders collapse to one on encode and on decode`() {
        assertEquals(
            listOf(daysBefore),
            ReminderCodec.decode(ReminderCodec.encode(listOf(daysBefore, daysBefore))),
        )
        assertEquals(listOf(daysBefore), ReminderCodec.decode("v1;d1@09:00|d1@09:00"))
    }

    @Test
    fun `the legacy migration value decodes to a same-day reminder`() {
        // What `5.sqm` writes for an old `reminderTime` column.
        assertEquals(
            listOf(Reminder(ReminderTrigger.DaysBeforeDue(0, LocalTime.of(9, 30)))),
            ReminderCodec.decode("v1;d0@09:30"),
        )
    }

    @Test
    fun `an acknowledged reminder encodes and decodes the acknowledgedAt timestamp`() {
        val now = Instant.parse("2026-08-05T12:00:00Z")
        val acknowledgedReminder = Reminder(
            ReminderTrigger.DaysBeforeDue(0, LocalTime.of(9, 0)),
            acknowledgedAt = now,
        )
        val encoded = ReminderCodec.encode(listOf(acknowledgedReminder))
        val decoded = ReminderCodec.decode(encoded)
        assertEquals(listOf(acknowledgedReminder), decoded)
    }

    @Test
    fun `a reminder without acknowledgedAt still encodes correctly`() {
        val reminder = Reminder(ReminderTrigger.DaysBeforeDue(0, LocalTime.of(9, 0)))
        val encoded = ReminderCodec.encode(listOf(reminder))
        assertEquals("v1;d0@09:00", encoded)
    }

    @Test
    fun `old encoded reminders without acknowledgedAt still decode correctly`() {
        val decoded = ReminderCodec.decode("v1;d0@09:00")
        assertEquals(
            listOf(Reminder(ReminderTrigger.DaysBeforeDue(0, LocalTime.of(9, 0)), acknowledgedAt = null)),
            decoded,
        )
    }

    @Test
    fun `a repeat with acknowledgedAt encodes both`() {
        val now = Instant.parse("2026-08-05T12:00:00Z")
        val reminder = Reminder(
            ReminderTrigger.DaysBeforeDue(0, LocalTime.of(9, 0)),
            ReminderRepeat(10, 3),
            now,
        )
        val encoded = ReminderCodec.encode(listOf(reminder))
        val decoded = ReminderCodec.decode(encoded)
        assertEquals(listOf(reminder), decoded)
    }
}
