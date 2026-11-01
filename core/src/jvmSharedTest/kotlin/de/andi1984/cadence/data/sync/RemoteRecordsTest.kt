package de.andi1984.cadence.data.sync

import de.andi1984.cadence.domain.model.MonthlyMode
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.RecurrenceMode
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.domain.reminder.Reminder
import de.andi1984.cadence.domain.reminder.ReminderRepeat
import de.andi1984.cadence.domain.reminder.ReminderTrigger
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** The wire shape: what leaves the device and what comes back has to be the same record. */
class RemoteRecordsTest {

    private val now = Instant.parse("2026-08-11T09:15:30.123Z")

    @Test
    fun `a task survives the round trip through the wire shape`() {
        val task = Task(
            id = "0198f2a0-0000-7000-8000-000000000001",
            title = "Water the plants",
            notes = "The big one by the window",
            priority = Priority.fromLevel(2),
            projectId = "0198f2a0-0000-7000-8000-000000000002",
            parentId = null,
            spawnedFromId = "0198f2a0-0000-7000-8000-000000000003",
            dueDate = LocalDate.of(2026, 8, 20),
            dueTime = LocalTime.of(9, 30),
            reminders = listOf(
                Reminder(ReminderTrigger.DaysBeforeDue(0, LocalTime.of(9, 0))),
                Reminder(
                    ReminderTrigger.At(LocalDateTime.of(2026, 8, 19, 18, 0)),
                    ReminderRepeat(15, null),
                ),
            ),
            completedAt = now,
            createdAt = now,
            sortOrder = 3,
            recurrence = RecurrenceRule(
                mode = RecurrenceMode.AFTER_COMPLETION,
                interval = 3,
                unit = RecurrenceUnit.MONTH,
                daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY),
                monthlyMode = MonthlyMode.NTH_WEEKDAY,
                dayOfMonth = null,
                nthWeek = 2,
                nthDayOfWeek = DayOfWeek.FRIDAY,
            ),
            updatedAt = now,
            deletedAt = null,
        )

        assertEquals(task, task.toRemote().toDomain())
    }

    @Test
    fun `a project survives the round trip`() {
        val project = Project(
            id = "0198f2a0-0000-7000-8000-000000000002",
            name = "Home",
            colorHex = "#3F51B5",
            parentId = null,
            sortOrder = 1,
            updatedAt = now,
            deletedAt = null,
        )

        assertEquals(project, project.toRemote().toDomain())
    }

    @Test
    fun `a tombstone travels as a version of the record, not as an absence`() {
        val task = Task(id = "t1", title = "Gone", createdAt = now, updatedAt = now, deletedAt = now)

        val wire = task.toRemote()

        assertEquals(now.toString(), wire.deletedAt)
        assertEquals(now, wire.toDomain().deletedAt)
    }

    /**
     * The ping-pong ADR 0002 decision 8 exists to prevent: Postgres keeps microseconds, this
     * database keeps milliseconds, and a row that came back strictly newer than the local copy
     * would be merged over it, restamped, pushed again, forever.
     */
    @Test
    fun `microseconds from the server are truncated to milliseconds on the way in`() {
        val wire = RemoteTask(
            id = "t1",
            title = "Water the plants",
            createdAt = "2026-08-11T09:15:30.123456+00:00",
            updatedAt = "2026-08-11T09:15:30.123456+00:00",
        )

        assertEquals(Instant.parse("2026-08-11T09:15:30.123Z"), wire.toDomain().updatedAt)
        assertEquals(Instant.parse("2026-08-11T09:15:30.123Z"), wire.toDomain().createdAt)
    }

    /** PostgREST renders a `timestamptz` with an offset, which `Instant.parse` alone refuses. */
    @Test
    fun `an offset timestamp is read as the same instant as its Z spelling`() {
        val withOffset = RemoteTask(
            id = "t1",
            title = "Water the plants",
            createdAt = "2026-08-11T11:15:30+02:00",
            updatedAt = "2026-08-11T11:15:30+02:00",
        )

        assertEquals(Instant.parse("2026-08-11T09:15:30Z"), withOffset.toDomain().updatedAt)
    }

    /** One unreadable field must not cost the whole task — the rule the backup codec follows. */
    @Test
    fun `an unreadable date leaves that field empty rather than dropping the task`() {
        val wire = RemoteTask(
            id = "t1",
            title = "Water the plants",
            dueDate = "not a date",
            createdAt = now.toString(),
            updatedAt = now.toString(),
        )

        val task = wire.toDomain()

        assertNull(task.dueDate)
        assertEquals("Water the plants", task.title)
        assertEquals(now, task.updatedAt)
    }

    /**
     * The bug this guards against: `sort_order integer not null` has no database default, and a
     * task whose sort order is the Kotlin default of 0 had the key left out of the payload
     * entirely, so every first push died with "null value in column sort_order violates not-null
     * constraint" and sync never worked at all.
     */
    @Test
    fun `a field still holding its default is written, not left out of the payload`() {
        val task = Task(id = "t1", title = "Water the plants", createdAt = now, updatedAt = now)

        val json = SyncJson.encodeToJsonElement(RemoteTask.serializer(), task.toRemote())

        assertEquals(0, (json as JsonObject)["sort_order"]?.jsonPrimitive?.content?.toInt())
        assertEquals(Priority.DEFAULT.level, json["priority"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `a project's default sort order is written too`() {
        val project = Project(id = "p1", name = "Home", colorHex = "#3F51B5", updatedAt = now)

        val json = SyncJson.encodeToJsonElement(RemoteProject.serializer(), project.toRemote())

        assertEquals(0, (json as JsonObject)["sort_order"]?.jsonPrimitive?.content?.toInt())
    }

    /**
     * An upsert only touches the columns it names, so a cleared field has to arrive as an
     * explicit null — omitted, it would read as "no opinion" and the server would keep the due
     * date the user just removed.
     */
    @Test
    fun `a cleared field travels as an explicit null so the upsert overwrites it`() {
        val task = Task(id = "t1", title = "Water the plants", createdAt = now, updatedAt = now)

        val json = SyncJson.encodeToJsonElement(RemoteTask.serializer(), task.toRemote()) as JsonObject

        assertEquals(JsonNull, json["due_date"])
        assertEquals(JsonNull, json["notes"])
        assertEquals(JsonNull, json["deleted_at"])
    }

    /**
     * PostgREST refuses a bulk insert whose objects disagree about which keys they carry
     * (`PGRST102`), and the push sends up to 500 rows in one statement.
     */
    @Test
    fun `every task in a batch carries the same keys whatever it happens to have filled in`() {
        val bare = Task(id = "t1", title = "Bare", createdAt = now, updatedAt = now)
        val full = Task(
            id = "t2",
            title = "Full",
            notes = "Notes",
            priority = Priority.fromLevel(1),
            dueDate = LocalDate.of(2026, 8, 20),
            dueTime = LocalTime.of(9, 30),
            sortOrder = 7,
            createdAt = now,
            updatedAt = now,
            deletedAt = now,
        )

        val keys = listOf(bare, full).map {
            (SyncJson.encodeToJsonElement(RemoteTask.serializer(), it.toRemote()) as JsonObject).keys
        }

        assertTrue("batch keys differ: ${keys.first() - keys.last()}", keys.first() == keys.last())
    }

    // ── Reminders on the wire ─────────────────────────────────────────────────────

    @Test
    fun `reminders travel as a jsonb array of tagged objects and the legacy reminder_time is kept`() {
        val task = Task(
            id = "t1",
            title = "Water the plants",
            dueDate = LocalDate.of(2026, 8, 20),
            dueTime = LocalTime.of(9, 30),
            reminders = listOf(
                Reminder(ReminderTrigger.DaysBeforeDue(0, LocalTime.of(9, 0))),
                Reminder(ReminderTrigger.DaysBeforeDue(1, LocalTime.of(8, 0))),
            ),
            createdAt = now,
            updatedAt = now,
        )

        val json = SyncJson.encodeToJsonElement(RemoteTask.serializer(), task.toRemote()) as JsonObject

        val reminders = json["reminders"]
        assertTrue(reminders.toString(), reminders.toString().contains("\"kind\":\"daysBeforeDue\""))
        assertTrue(reminders.toString(), reminders.toString().contains("\"days\":1"))
        // Backward compatibility: an older client still fires the same-day reminder.
        assertEquals("\"09:00\"", json["reminder_time"].toString())
    }

    @Test
    fun `a row pushed by an old client, with only reminder_time, maps to a same-day reminder`() {
        val wire = RemoteTask(
            id = "t1",
            title = "Water the plants",
            dueDate = "2026-08-20",
            reminderTime = "09:00",
            createdAt = now.toString(),
            updatedAt = now.toString(),
        )

        assertEquals(
            listOf(Reminder(ReminderTrigger.DaysBeforeDue(0, LocalTime.of(9, 0)))),
            wire.toDomain().reminders,
        )
    }

    @Test
    fun `an unknown reminder kind on the wire is skipped, the rest survive`() {
        val wire = RemoteTask(
            id = "t1",
            title = "Water the plants",
            reminders = listOf(
                RemoteReminder(kind = "location"),
                RemoteReminder(kind = "beforeDueTime", minutes = 30),
            ),
            createdAt = now.toString(),
            updatedAt = now.toString(),
        )

        assertEquals(
            listOf(Reminder(ReminderTrigger.BeforeDueTime(30))),
            wire.toDomain().reminders,
        )
    }

    @Test
    fun `a repeat travels inside its reminder object`() {
        val task = Task(
            id = "t1",
            title = "Water the plants",
            reminders = listOf(
                Reminder(
                    ReminderTrigger.DaysBeforeDue(0, LocalTime.of(9, 0)),
                    ReminderRepeat(10, 3),
                ),
            ),
            createdAt = now,
            updatedAt = now,
        )

        val json = SyncJson.encodeToJsonElement(RemoteTask.serializer(), task.toRemote()).toString()

        assertTrue(json, json.contains("\"repeat\":{\"every_minutes\":10,\"count\":3}"))
        assertEquals(task.reminders, task.toRemote().toDomain().reminders)
    }
}
