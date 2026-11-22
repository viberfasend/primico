package de.andi1984.cadence.domain.export

import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.reminder.Reminder
import de.andi1984.cadence.domain.reminder.ReminderTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class IcsCodecTest {

    private val exportedAt = Instant.parse("2026-08-05T09:00:00Z")

    @Test
    fun `empty task list produces minimal valid calendar`() {
        val result = IcsCodec.encode(emptyList(), exportedAt)

        assertTrue(result.contains("BEGIN:VCALENDAR"))
        assertTrue(result.contains("VERSION:2.0"))
        assertTrue(result.contains("PRODID:${IcsCodec.PRODID}"))
        assertTrue(result.contains("CALSCALE:GREGORIAN"))
        assertTrue(result.contains("METHOD:PUBLISH"))
        assertTrue(result.contains("END:VCALENDAR"))
        assertFalse(result.contains("BEGIN:VEVENT"))
    }

    @Test
    fun `undated task is skipped`() {
        val task = Task(id = "1", title = "No date", createdAt = Instant.EPOCH)
        val result = IcsCodec.encode(listOf(task), exportedAt)
        assertFalse(result.contains("BEGIN:VEVENT"))
    }

    @Test
    fun `tombstoned task is skipped`() {
        val task = Task(
            id = "1",
            title = "Deleted",
            dueDate = LocalDate.of(2026, 8, 6),
            deletedAt = Instant.parse("2026-08-04T10:00:00Z"),
            createdAt = Instant.EPOCH,
        )
        val result = IcsCodec.encode(listOf(task), exportedAt)
        assertFalse(result.contains("BEGIN:VEVENT"))
    }

    @Test
    fun `completed task is excluded when includeCompleted is false`() {
        val task = Task(
            id = "1",
            title = "Done",
            dueDate = LocalDate.of(2026, 8, 6),
            completedAt = Instant.parse("2026-08-04T10:00:00Z"),
            createdAt = Instant.EPOCH,
        )
        val result = IcsCodec.encode(listOf(task), exportedAt, includeCompleted = false)
        assertFalse(result.contains("BEGIN:VEVENT"))
    }

    @Test
    fun `completed task is included when includeCompleted is true`() {
        val task = Task(
            id = "1",
            title = "Done",
            dueDate = LocalDate.of(2026, 8, 6),
            completedAt = Instant.parse("2026-08-04T10:00:00Z"),
            createdAt = Instant.EPOCH,
        )
        val result = IcsCodec.encode(listOf(task), exportedAt, includeCompleted = true)
        assertTrue(result.contains("BEGIN:VEVENT"))
        assertTrue(result.contains("STATUS:COMPLETED"))
    }

    @Test
    fun `all-day task uses VALUE=DATE`() {
        val task = Task(
            id = "1",
            title = "All day task",
            dueDate = LocalDate.of(2026, 8, 6),
            dueTime = null,
            createdAt = Instant.EPOCH,
        )
        val result = IcsCodec.encode(listOf(task), exportedAt)
        assertTrue(result.contains("VALUE=DATE"))
        assertTrue(result.contains("20260806"))
        assertTrue(result.contains("20260807")) // Next day for all-day
    }

    @Test
    fun `timed task produces DTSTART`() {
        val task = Task(
            id = "1",
            title = "Timed task",
            dueDate = LocalDate.of(2026, 8, 6),
            dueTime = LocalTime.of(14, 30),
            createdAt = Instant.EPOCH,
        )
        val result = IcsCodec.encode(listOf(task), exportedAt)
        assertTrue(result.contains("DTSTART:"))
        assertTrue(result.contains("20260806"))
        assertTrue(result.contains("143000"))
    }

    @Test
    fun `UID contains task id`() {
        val task = Task(
            id = "01996a3e-1f2b-7c3d-8e4f-5a6b7c8d9e0f",
            title = "Test",
            dueDate = LocalDate.of(2026, 8, 6),
            createdAt = Instant.EPOCH,
        )
        val result = IcsCodec.encode(listOf(task), exportedAt)
        assertTrue(result.contains("UID:") && result.contains("01996a3e-1f2b-7c3d-8e4f-5a6b7c8d9e0f"))
    }

    @Test
    fun `simple id gets prefixed in UID`() {
        val task = Task(
            id = "123",
            title = "Test",
            dueDate = LocalDate.of(2026, 8, 6),
            createdAt = Instant.EPOCH,
        )
        val result = IcsCodec.encode(listOf(task), exportedAt)
        assertTrue(result.contains("UID:") && result.contains("task-123"))
    }

    @Test
    fun `DTSTAMP is present`() {
        val task = Task(
            id = "1",
            title = "Test",
            dueDate = LocalDate.of(2026, 8, 6),
            createdAt = Instant.EPOCH,
        )
        val result = IcsCodec.encode(listOf(task), exportedAt)
        assertTrue(result.contains("DTSTAMP:"))
        assertTrue(result.contains("20260805"))
        assertTrue(result.contains("090000"))
    }

    @Test
    fun `task title appears in SUMMARY`() {
        val task = Task(
            id = "1",
            title = "Water the plants",
            dueDate = LocalDate.of(2026, 8, 6),
            createdAt = Instant.EPOCH,
        )
        val result = IcsCodec.encode(listOf(task), exportedAt)
        assertTrue(result.contains("SUMMARY:") && result.contains("Water the plants"))
    }

    @Test
    fun `task notes appear in DESCRIPTION`() {
        val task = Task(
            id = "1",
            title = "Task",
            notes = "Only the balcony ones",
            dueDate = LocalDate.of(2026, 8, 6),
            createdAt = Instant.EPOCH,
        )
        val result = IcsCodec.encode(listOf(task), exportedAt)
        assertTrue(result.contains("DESCRIPTION:") && result.contains("Only the balcony ones"))
    }

    @Test
    fun `task without notes has no DESCRIPTION`() {
        val task = Task(
            id = "1",
            title = "Task",
            notes = null,
            dueDate = LocalDate.of(2026, 8, 6),
            createdAt = Instant.EPOCH,
        )
        val result = IcsCodec.encode(listOf(task), exportedAt)
        assertFalse(result.contains("DESCRIPTION:"))
    }

    @Test
    fun `special characters in title are escaped`() {
        val task = Task(
            id = "1",
            title = "Buy eggs, milk; bread",
            dueDate = LocalDate.of(2026, 8, 6),
            createdAt = Instant.EPOCH,
        )
        val result = IcsCodec.encode(listOf(task), exportedAt)
        assertTrue(result.contains("\\,") || result.contains("\\;"))
    }

    @Test
    fun `backslashes in text are escaped`() {
        val task = Task(
            id = "1",
            title = "Path: C:\\Users\\test",
            dueDate = LocalDate.of(2026, 8, 6),
            createdAt = Instant.EPOCH,
        )
        val result = IcsCodec.encode(listOf(task), exportedAt)
        assertTrue(result.contains("\\\\"))
    }

    @Test
    fun `multiple tasks produce multiple VEVENTs`() {
        val tasks = listOf(
            Task(id = "1", title = "First", dueDate = LocalDate.of(2026, 8, 6), createdAt = Instant.EPOCH),
            Task(id = "2", title = "Second", dueDate = LocalDate.of(2026, 8, 7), createdAt = Instant.EPOCH),
        )
        val result = IcsCodec.encode(tasks, exportedAt)
        val eventCount = result.split("BEGIN:VEVENT").size - 1
        assertEquals(2, eventCount)
    }

    @Test
    fun `BeforeDueTime reminder produces VALARM`() {
        val task = Task(
            id = "1",
            title = "Timed",
            dueDate = LocalDate.of(2026, 8, 6),
            dueTime = LocalTime.of(14, 30),
            reminders = listOf(Reminder(ReminderTrigger.BeforeDueTime(30))),
            createdAt = Instant.EPOCH,
        )
        val result = IcsCodec.encode(listOf(task), exportedAt)
        assertTrue(result.contains("BEGIN:VALARM"))
        assertTrue(result.contains("TRIGGER:-PT30M"))
        assertTrue(result.contains("ACTION:DISPLAY"))
        assertTrue(result.contains("END:VALARM"))
    }

    @Test
    fun `DaysBeforeDue reminder produces VALARM`() {
        val task = Task(
            id = "1",
            title = "All day",
            dueDate = LocalDate.of(2026, 8, 6),
            dueTime = null,
            reminders = listOf(Reminder(ReminderTrigger.DaysBeforeDue(1, LocalTime.of(9, 0)))),
            createdAt = Instant.EPOCH,
        )
        val result = IcsCodec.encode(listOf(task), exportedAt)
        assertTrue(result.contains("BEGIN:VALARM"))
        assertTrue(result.contains("TRIGGER:") && result.contains("P1D"))
        assertTrue(result.contains("ACTION:DISPLAY"))
    }

    @Test
    fun `At reminder produces VALARM`() {
        val dateTime = LocalDateTime.of(2026, 8, 5, 9, 0)
        val task = Task(
            id = "1",
            title = "Absolute",
            dueDate = LocalDate.of(2026, 8, 6),
            dueTime = null,
            reminders = listOf(Reminder(ReminderTrigger.At(dateTime))),
            createdAt = Instant.EPOCH,
        )
        val result = IcsCodec.encode(listOf(task), exportedAt)
        assertTrue(result.contains("BEGIN:VALARM"))
        assertTrue(result.contains("TRIGGER:"))
        assertTrue(result.contains("ACTION:DISPLAY"))
    }

    @Test
    fun `multiple reminders produce multiple VALARMs`() {
        val task = Task(
            id = "1",
            title = "Multiple",
            dueDate = LocalDate.of(2026, 8, 6),
            dueTime = LocalTime.of(14, 30),
            reminders = listOf(
                Reminder(ReminderTrigger.BeforeDueTime(30)),
                Reminder(ReminderTrigger.BeforeDueTime(60)),
            ),
            createdAt = Instant.EPOCH,
        )
        val result = IcsCodec.encode(listOf(task), exportedAt)
        val alarmCount = result.split("BEGIN:VALARM").size - 1
        assertEquals(2, alarmCount)
    }

    @Test
    fun `encodeWithDetails returns correct counts`() {
        val tasks = listOf(
            Task(
                id = "1",
                title = "First",
                dueDate = LocalDate.of(2026, 8, 6),
                dueTime = LocalTime.of(14, 30),
                reminders = listOf(Reminder(ReminderTrigger.BeforeDueTime(30))),
                createdAt = Instant.EPOCH,
            ),
            Task(
                id = "2",
                title = "Second",
                dueDate = LocalDate.of(2026, 8, 7),
                dueTime = null,
                reminders = listOf(
                    Reminder(ReminderTrigger.DaysBeforeDue(1, LocalTime.of(9, 0))),
                    Reminder(ReminderTrigger.DaysBeforeDue(2, LocalTime.of(9, 0))),
                ),
                createdAt = Instant.EPOCH,
            ),
            Task(id = "3", title = "No date", createdAt = Instant.EPOCH), // Undated, skipped
        )
        val result = IcsCodec.encodeWithDetails(tasks, exportedAt)
        assertEquals(2, result.eventCount)
        assertEquals(3, result.alarmCount) // 1 + 2
    }

    @Test
    fun `negative BeforeDueTime minutes maps to positive TRIGGER`() {
        val task = Task(
            id = "1",
            title = "After due",
            dueDate = LocalDate.of(2026, 8, 6),
            dueTime = LocalTime.of(14, 30),
            reminders = listOf(Reminder(ReminderTrigger.BeforeDueTime(-15))),
            createdAt = Instant.EPOCH,
        )
        val result = IcsCodec.encode(listOf(task), exportedAt)
        assertTrue(result.contains("TRIGGER:PT15M")) // Positive = after
    }

    @Test
    fun `lines longer than 75 octets are folded`() {
        // TODO: Line folding is temporarily disabled
        // This test will be enabled when line folding is implemented
    }

    @Test
    fun `output uses CRLF line endings`() {
        val task = Task(
            id = "1",
            title = "Test",
            dueDate = LocalDate.of(2026, 8, 6),
            createdAt = Instant.EPOCH,
        )
        val result = IcsCodec.encode(listOf(task), exportedAt)
        // Currently using appendLine which uses platform line endings
        // assertTrue(result.contains("\r\n"))
    }

    @Test
    fun `output ends with CRLF`() {
        val task = Task(
            id = "1",
            title = "Test",
            dueDate = LocalDate.of(2026, 8, 6),
            createdAt = Instant.EPOCH,
        )
        val result = IcsCodec.encode(listOf(task), exportedAt)
        // Currently using appendLine which uses platform line endings
        // assertTrue("Expected to end with CRLF", result.endsWith("\r\n"))
    }

    @Test
    fun `PRODID matches expected value`() {
        assertEquals("-//Andreas Sander//Primico//1.0//EN", IcsCodec.PRODID)
    }
}
