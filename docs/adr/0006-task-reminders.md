---
title: "ADR 0006 — Flexible task reminders, packed into one column on the task"
sidebar:
  label: "0006 · Reminders"
  order: 6
---
**Status:** accepted · **Date:** 2026-10-25 · **Supersedes nothing.** Extends
[ADR 0002](0002-supabase-sync.md) (the merge rule) and [ADR 0004](0004-tags.md) (the packed-column
reasoning this reuses).

## Context

A task used to have one optional `reminderTime: LocalTime?`, fired on the due day only, and an
undated task accepted one that never fired at all. The plan behind this ADR
([task-reminders](../plans/task-reminders.md)) researched what Todoist, TickTick and iCalendar
VALARM (RFC 5545/9074) converge on: **a list of reminders per task, each one of several trigger
kinds, each with an optional repeat**. The decisions below record the model and where it lives.

## Decisions

### 1. A task carries a list of reminders, each with one of three trigger kinds and an optional repeat

`Task.reminders: List<Reminder>` replaces `reminderTime` in the domain
(`domain/reminder/Reminder.kt`):

- `BeforeDueTime(minutes)` — N minutes before (negative = after) the due *moment*; needs
  `dueDate` + `dueTime`.
- `DaysBeforeDue(days, at)` — N days before (negative = after) the due *date*, at a wall-clock
  time. `days = 0` is "on the due day", the legacy behaviour. Counting whole days at a wall-clock
  time is what stays correct across a DST change; a minute offset is not.
- `At(dateTime)` — a fixed local date-time ("floating", re-read in the device zone), and the only
  kind that works on an undated task, which also fixes the dead-reminder bug.
- `ReminderRepeat(everyMinutes, count)` — re-fires every N minutes, `count` more times, or until
  the task is done/dismissed when `count` is null; the planner caps the expansion at
  `ReminderPlanner.MAX_NAG` (12) instants.

**Location reminders are left out.** Android would need Play Services geofencing the app does not
use, and the desktop could never fire them. The trigger is a sealed type and every codec skips
unknown kinds, so a `Location` can be added later without breaking the format.

The rules every kind has to answer: snooze/reschedule/"overdue → today" need no code — relative
triggers are computed from the due date at plan time and follow it, while `At` stays fixed because
that is what absolute means. Recurrence carries relative triggers over with the row copy and
shifts `At` by the same day span as the due date (the rule subtask dates already follow), dropping
an `At` that is in the past after the shift. Done tasks fire nothing. Identical reminders on one
task collapse to one. The per-device `reminderLeadMinutes` / `remindersEnabled` stay as they are:
"automatic reminders" layered on top of the task's own list.

### 2. Reminders are a packed column on the task, not a table

The same reasoning as ADR 0004 decision 1 for tags. A reminder belongs to exactly one task, is
never shared, and should travel with the row: the recurrence copy, backup merge, tombstones and
last-writer-wins on the task all come free, and sync needs no fifth table (RLS, trigger, cursor,
pull page, sweep).

- **SQLite:** `taskRow.reminders TEXT NOT NULL DEFAULT ''`, the last column. Schema 5→6 via
  `5.sqm`: `ADD COLUMN`, then `UPDATE taskRow SET reminders = 'v1;d0@'||… WHERE reminderTime IS NOT
  NULL`, turning a legacy reminder into `DaysBeforeDue(0, t)`. `reminderTime` stays in the table
  (SQLite 3.19 on minSdk 26 has no `DROP COLUMN`) and is still written — with the first same-day
  reminder's time, the value the wire carries — but no longer read.
- **Codec:** `ReminderCodec` packs the list as `v1;d1@09:00|m30|t2026-10-26T09:00~r10x3`
  (`v1;`-prefixed, entries `|`-separated, optional `~r…` repeat suffix). Malformed input decodes to
  empty and never throws; unknown kinds are skipped. Conversion only in `Task.toRow()`/`toTask`.
- **Backup:** an optional `reminders` JSON array of tagged objects
  (`{"kind":"daysBeforeDue","days":1,"at":"09:00","repeat":null}`). No `VERSION` bump — an older
  reader ignores the key. On read, a v2 file with only `reminderTime` maps to `DaysBeforeDue(0)`.
  The legacy key is still written alongside, so an older install fires something.
- **Wire:** `tasks.reminders jsonb not null default '[]' check (jsonb_typeof(reminders) =
  'array')` in `neon/migrations/0003_task_reminders.sql`. Same JSON shape as the backup, separate
  DTOs (`RemoteReminder`), per the existing rule. `reminder_time` keeps being written (the first
  `DaysBeforeDue(0)`'s time) so older clients still fire something; old-client pushes omit
  `reminders` and the upsert leaves the server's value untouched. Rollout order is mandatory:
  `SyncJson.encodeDefaults = true` means new clients always send the column, so the Neon migration
  must be applied before the client ships.

**What it costs, stated plainly:** two devices editing *different* reminders of one task offline
lose one edit under last-writer-wins — the same trade the app already accepts for tags and titles.

### 3. An alarm's identity is a deterministic slot, not a stored id

`ReminderKey(taskId, slot)` where the slot is the reminder's packed encoding plus the repeat index
(`d1@09:00#0`), and `lead:N` for a device lead. Editing a reminder therefore cancels the old key and
arms the new one, with no stored ids. Legacy keys (`0`, bare positive leads) are simply no longer
planned, so the reconciler cancels them once on upgrade; Android's armed record reads the old
`active_leads:` entries as those legacy slots for exactly that.

## Consequences

- `ReminderPlanner` (pure, `:core`) answers "when" for both shells: leads × `dueTime`, plus each
  reminder entry expanded over its repeat. It still does not filter by "now" — Android and the
  desktop disagree about past instants and each handles them itself.
- The notification's content line is worded from the trigger at arm time: "Due in 2 days" / "Due
  tomorrow" / "Due today" / "Overdue" for days-before, the lead plurals for minutes-before, a plain
  "Reminder" for a fixed date-time — plurals in `res/values{,-de}` on Android and in the compose
  resources on the desktop.
- Phase 2 adds notification actions (Done / Snooze) and a per-reminder `acknowledgedAt` for
  cross-device dismissal (the RFC 9074 idea); phase 3 adds quick-add syntax. Neither changes the
  storage format decided here.

## Alternatives rejected

- **A `reminderRow` table, synced like the other four.** The normalised answer, and wrong for the
  same reasons as a tag join table (ADR 0004): every synced record is a tombstone forever, the sync
  machinery doubles for one feature, and deleting or editing one reminder would rewrite rows. The
  packed column's cost — losing one of two concurrent offline edits to *different* reminders of
  the same task — is the same last-writer-wins trade titles already make.
- **One reminder per task with a kind field.** That is the model being replaced: it cannot express
  "1 day before at 09:00 *and* 30 minutes before" without a list anyway.
- **Location reminders now.** Rejected in decision 1 — Play Services geofencing on Android, nothing
  on the desktop; the sealed trigger and the skipping codecs leave the door open.
- **Minute offsets instead of days-before.** A minute offset slips an hour across a DST change;
  the days-before kind exists precisely so an all-day task's 09:00 stays 09:00.
