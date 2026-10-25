---
title: "Plan: flexible task reminders"
description: "Any number of reminders per task — minutes before the due time, days before the due date at a set time, or a fixed date — with repeats and snooze, synced as one packed column."
---
*Written 2026-10-25. Nothing here is built yet; when work starts, the decisions move into an ADR
(next free number) and the steps into issues. Today's behaviour is described in
[Reminders](../concepts/reminders.md).*

## Context

Today a task has one optional `reminderTime: LocalTime?` (`core/.../domain/model/Task.kt:62`).
`ReminderPlanner` (`core/.../domain/reminder/ReminderPlanner.kt:58`) fires it at
`dueDate.atTime(reminderTime)`, so the reminder can only land **on the due day**. The detail row
says "On the due day". An undated task accepts a reminder (`TaskDetailScreen.kt:580`) that never
fires. The per-device `CadenceSettings.reminderLeadMinutes` adds "N minutes before `dueTime`"
alarms, but only for timed tasks and only for every task at once.

The goal: reminders on other days than the due day, in **the most flexible model possible**.

### How other apps do it (research)
- **Todoist:** a task has any number of reminders. Each is *relative* (`minute_offset` before
  the due time), *absolute* (date + time, also on undated tasks) or *location*. "Automatic
  reminders" are a per-account default offset, the same idea as our `reminderLeadMinutes`.
- **TickTick:** up to 5 per task. Presets like "on the day at 9:00" and "1 day before at 9:00"
  serve all-day tasks. A **constant reminder** re-fires every minute until the task is done or
  snoozed.
- **iCalendar VALARM (RFC 5545/9074):** `TRIGGER` is a duration relative to start/end, or an
  absolute UTC time. `REPEAT` + `DURATION` re-fire the alarm. RFC 9074 adds `ACKNOWLEDGED`, so a
  dismissal on one device can clear the alert on the others, and models a snooze as a child
  alarm.

Taken together: **a list of reminders per task, each one of several trigger kinds, each with an
optional repeat**. That is the model below.

Sources: [Todoist — Introduction to reminders](https://www.todoist.com/help/articles/introduction-to-reminders),
[TickTick — 20 lesser-known features](https://blog.ticktick.com/2020/12/08/20-lesser-known-ticktick-features/),
[TidBITS on TickTick](https://tidbits.com/2025/08/14/ticktick-provides-a-focused-daily-task-list-and-more/),
[RFC 9074 — VALARM extensions](https://www.rfc-editor.org/rfc/rfc9074.html).

## Domain model (`:core`, `domain/reminder/Reminder.kt`, new)

```kotlin
data class Reminder(val trigger: ReminderTrigger, val repeat: ReminderRepeat? = null)

sealed interface ReminderTrigger {
    /** N minutes before (negative = after) the due *moment*. Needs dueDate + dueTime. */
    data class BeforeDueTime(val minutes: Int) : ReminderTrigger
    /** N days before (negative = after) the due *date*, at a wall-clock time. All-day tasks;
     *  stays correct across DST, which a minute offset is not. "On the due day" = days 0. */
    data class DaysBeforeDue(val days: Int, val at: LocalTime) : ReminderTrigger
    /** A fixed local date-time ("floating", re-read in the device zone). Works on undated tasks. */
    data class At(val dateTime: LocalDateTime) : ReminderTrigger
}

/** Re-fire every [everyMinutes] up to [count] more times; count null = until done or dismissed,
 *  capped at MAX_NAG repeats in the planner. */
data class ReminderRepeat(val everyMinutes: Int, val count: Int?)
```

`Task.reminders: List<Reminder> = emptyList()` replaces `reminderTime` in the domain.

**Location reminders are left out (decided).** Android would need Play Services geofencing, which
the app does not use today, and the desktop could never fire them. The trigger is a sealed type
and the codecs skip unknown kinds, so a `Location` kind can be added later without breaking the
format.

### Rules (the questions every reminder kind has to answer)
- **Snooze / reschedule / "overdue → today":** relative triggers follow the due date
  automatically. `At` stays fixed, because that is what absolute means.
- **Recurrence** (`CadenceRepository.setCompleted`, around line 413): relative triggers carry
  over with the row copy and need no code. `At` shifts by the same day span as the due date, the
  rule subtasks already follow (`subtask.dueDate?.plusDays(shift)`, line 431). An `At` already
  in the past after the shift is dropped.
- **No due date:** only `At` fires. The UI offers only `At` there, which also fixes the
  dead-reminder bug.
- **Done task:** nothing fires (unchanged).
- **Dedupe:** identical reminders on one task collapse to one.
- **Device defaults stay** (`reminderLeadMinutes`, `remindersEnabled`). They are per-device
  "automatic reminders" layered on top of the task's own list, exactly as today.

## Storage: a packed column on the task, not a table

The same reasoning as ADR 0004 for tags. A reminder belongs to exactly one task, is never
shared, and should travel with the row:
- the recurrence copy, backup merge, tombstones and last-writer-wins on the task all come free;
- sync needs no fifth table (RLS, trigger, cursor, pull page, sweep).

Cost: two devices editing *different* reminders of one task offline lose one edit under
last-writer-wins, the same trade the app already accepts for tags and titles. Record this in a
new **ADR 0006 — task reminders**.

- **SQLite:** `taskRow.reminders TEXT NOT NULL DEFAULT ''`, **last** column in `Task.sq`. Schema
  5→6 via `5.sqm`: `ADD COLUMN`, then
  `UPDATE taskRow SET reminders = 'v1;d0@'||reminderTime WHERE reminderTime IS NOT NULL`, which
  turns a legacy reminder into `DaysBeforeDue(0, t)`. `reminderTime` stays in the table but is no
  longer read (SQLite 3.19 on minSdk 26 has no `DROP COLUMN`).
- **Codec:** `ReminderCodec` next to `RecurrenceCodec`, `v1;`-prefixed, entries separated by `|`.
  Malformed input decodes to empty and never throws. Conversion only in `Task.toRow()`/`toTask`
  (`SqlDelightStores.kt`).
- **Backup** (`BackupCodec`): optional `reminders` JSON array of tagged objects
  (`{"kind":"daysBeforeDue","days":1,"at":"09:00","repeat":null}`). No `VERSION` bump. On read,
  a v2 file with only `reminderTime` maps to `DaysBeforeDue(0)`. Unknown `kind` is skipped.
- **Wire** (`RemoteRecords`): `reminders jsonb not null default '[]'` in
  `neon/migrations/0003_task_reminders.sql`, with a `jsonb_typeof(reminders) = 'array'` check.
  Same JSON shape as backup, but separate DTOs (the existing rule).
  - **Backward compatibility:** keep writing `reminder_time` = the time of the first
    `DaysBeforeDue(0)`, so older clients still fire something.
  - **Old-client pushes:** they omit `reminders`, and `merge-duplicates` leaves it untouched on
    the server.
  - **Rollout order is mandatory:** `SyncJson.encodeDefaults = true` means new clients always
    send `reminders`. Apply the Neon migration **before** shipping, or every push fails on an
    unknown column.

## Planner / reconciler / shells

- **`ReminderPlanner.plan`:** emits instants for (a) device leads × `dueTime` (unchanged) and
  (b) each `task.reminders` entry, expanding `repeat` into up to `MAX_NAG` (say 12) instants.
  Still not filtered by "now".
- **`ReminderKey(taskId, leadMinutes)`** becomes `ReminderKey(taskId, slot: String)`. The slot is
  derived deterministically from the reminder's content plus the repeat index (e.g. `d1@09:00#0`)
  and device leads become `lead:20`. Editing a reminder therefore cancels the old key and arms the
  new one, with no stored ids needed. Legacy keys (`0`, positive leads) are simply no longer
  planned, so the reconciler cancels them once on upgrade.
- **Android:**
  - `ReminderRequestCodes` gets the string slot as its key suffix and the `active_leads:` record
    becomes `active_slots:`. An old entry reads as unplanned and is cancelled; no migration code
    beyond accepting the old format.
  - `ReminderReceiver` gains notification **actions: Done, Snooze 10 min, Snooze 1 h, Tomorrow**
    (a receiver that calls `setCompleted`, or appends a one-shot `At` reminder through the
    repository so the snooze syncs).
  - Content text from the trigger: "Due in 2 days", "Due tomorrow", "Due today", "Overdue", as
    plurals in `res/values{,-de}`.
- **Desktop:** `DesktopReminderScheduler` uses the new key. The balloon text uses the same
  wording; there is no snooze action, since the tray has no buttons.
- **Cross-device dismissal (RFC 9074 idea), phase 2:** a per-reminder `acknowledgedAt` in the
  packed value. A device skips instants at or before it. Done/Snooze set it, so the phone
  dismissing a nag stops the desktop's.

## UI (`:ui`)

- **`TaskDetailScreen`:** the single reminder row becomes a **Reminders section** listing every
  reminder as a row with a remove action, plus "Add reminder". New `ReminderEditorDialog`:
  - **kind:** "Before the due time" (only when `dueTime` is set), "Days before the due date", or
    "On a date". Undated tasks show only "On a date".
  - **presets:** same day / 1 day / 2 days / 1 week before at 09:00; 5/15/30/60 min before.
  - **repeat:** off / every 5, 10 or 30 min, a few times or until done.
- **`CadenceViewModel`:** `setReminder` becomes `addReminder` / `updateReminder` /
  `removeReminder`, each through `saveTask`.
- **Formatter** `ui/format/ReminderLabels.kt` turns a `Reminder` into text. No prose in the
  domain; plurals in `values{,-de}/strings.xml`.
- **Quick add (phase 2):** `!1d@9`-style or lexicon phrases ("remind 2 days before",
  "erinnere 2 Tage vorher") in `QuickAddLexicon`/`QuickAddPatterns`. Run
  `QuickAddPatternsDeviceTest` after.
- **Task row:** a small bell icon in the meta line when a task has reminders.

## Phasing (separate PRs, decided)

1. Model + codec + schema 6 + backup + wire + Neon migration + planner/key + shells + detail
   editor (no repeat UI yet) + docs.
2. Repeat / nag + Android notification actions (Done/Snooze) + `acknowledgedAt`.
3. Quick-add syntax.

## Docs to update
`docs/reference/{data-model,database,backup-format,sync-wire,glossary}.md`,
`docs/concepts/reminders.md`, new `docs/adr/0006-task-reminders.md`, root `CLAUDE.md` reminder
paragraphs (the `reminderTime` lead-0 wording) and the schema version line.

## Verification
- `./gradlew :core:testDebugUnitTest :ui:testDebugUnitTest :core:jvmTest :ui:jvmTest :app-desktop:test :ui:compileKotlinJvm`
- **New tests:**
  - `ReminderCodecTest` (round-trip, malformed → empty, unknown kind skipped);
  - `ReminderPlannerTest` (days-before across a DST change, `At` on an undated task, repeat
    expansion and cap, dedupe);
  - `CadenceRepositoryTest` (recurrence carries relative reminders and shifts `At`, snooze moves
    relative only);
  - `DatabaseDriverFactoryTest` (5→6 converts `reminderTime`);
  - `BackupCodecTest` (old `reminderTime` file → `DaysBeforeDue(0)`);
  - `RemoteRecordsTest`;
  - `DesktopReminderSchedulerTest` with the new keys.
- `bash neon/tests/run.sh` for `0003`.
- Manual: `./gradlew :app-desktop:run`. Set "1 day before at now+1 min" on a task due tomorrow
  and confirm the balloon. On a device: `assembleDebug`, same check, plus the Snooze action in
  phase 2.
- `cd docs-site && npm ci && npm run build` for the link check.
