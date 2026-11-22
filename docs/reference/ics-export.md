---
title: ICS export format
description: RFC 5545 iCalendar export of dated tasks for use in calendar applications.
sidebar:
  order: 5
---

Primico exports dated tasks as an [RFC 5545](https://datatracker.ietf.org/doc/html/rfc5545)
iCalendar (`.ics`) file, so you can view your tasks beside your meetings in any calendar
application. The codec is [`IcsCodec.kt`](../../core/src/jvmShared/kotlin/de/andi1984/cadence/domain/export/IcsCodec.kt);
the file I/O is `IcsIo` on Android (Storage Access Framework) and `DesktopIcsGateway` on the
desktop.

## Document structure

Every export is a valid iCalendar document with:

- `VERSION:2.0`
- `PRODID:-//Andreas Sander//Primico//1.0//EN`
- `CALSCALE:GREGORIAN`
- `METHOD:PUBLISH`

## Event mapping

| Primico concept | iCalendar property | Notes |
|---|---|---|
| Task with `dueDate` | one `VEVENT` | Tasks without a due date are **omitted** from the export. |
| `id` | `UID` | Derived: `<id>@primico.app` for UUID-like ids, otherwise `task-<id>@primico.app`. Stable across exports so re-importing updates rather than duplicates. |
| export time | `DTSTAMP` | ISO instant in UTC, e.g. `20260101T120000Z`. |
| `title` | `SUMMARY` | Escaped per RFC 5545 (backslashes, semicolons, commas, newlines). |
| `notes` | `DESCRIPTION` | Escaped. Only included if present and non-blank. |
| `completedAt != null` | `STATUS:COMPLETED` | Completed tasks are included by default; they can be excluded via a setting. |
| Tombstoned (`deletedAt != null`) | **omitted** | Tombstoned tasks never appear in the export. |

### All-day vs timed events

| Primico state | iCalendar properties |
|---|---|
| `dueDate` present, `dueTime = null` | `DTSTART;VALUE=DATE:<dueDate>`, `DTEND;VALUE=DATE:<dueDate+1>` |
| `dueDate` and `dueTime` present | `DTSTART:<dueDateTdueTime>`, `DTEND:<dueDateTdueTime>` |

Both use **floating local time** (no `TZID`). The calendar application will interpret times in
its own timezone. This is the most compatible approach and matches how most calendar apps handle
events without explicit timezone information.

### Recurrence

Recurring tasks are exported as **individual VEVENT instances** for each occurrence that exists
in the database. The export never emits an `RRULE`; if you need the recurrence rule itself, use
the [backup export](backup-format.md) and inspect the task's `recurrence` field.

## Reminders: VALARM mapping

Each entry in `Task.reminders` becomes one `VALARM` component attached to the corresponding
`VEVENT`. The mapping from [ADR 0006](../adr/0006-reminder-planning.md) reminder triggers to RFC
5545 `TRIGGER` values:

| ADR 0006 trigger | TRIGGER value | Meaning |
|---|---|---|
| `BeforeDueTime(+N minutes)` | `-PT<abs(N)>M` | N minutes **before** `DTSTART` |
| `BeforeDueTime(-N minutes)` | `PT<abs(N)>M` | N minutes **after** `DTSTART` |
| `DaysBeforeDue(days, at)` | `-P<days>DT<HH>H<MM>M<SS>S` | Trigger at `at` time, `days` days before the due date |
| `At(dateTime)` | `<ISO-8601 date-time>` | Absolute trigger at the specified date-time, converted to UTC ISO instant |

Every `VALARM` has:

- `ACTION:DISPLAY`
- `DESCRIPTION:<task title>` (escaped)

Example with a 10-minute advance reminder:

```
BEGIN:VALARM
TRIGGER:-PT10M
ACTION:DISPLAY
DESCRIPTION:Water the plants
END:VALARM
```

## RFC 5545 compliance

- **Line endings**: CRLF (`\r\n`) as required by RFC 5545.
- **Line folding**: Long lines (longer than 75 octets) are folded by inserting CRLF followed
  by a single space, as specified in RFC 5545 section 3.1.
- **Character escaping**: All TEXT values (SUMMARY, DESCRIPTION, etc.) have backslashes,
  semicolons, commas, and newlines escaped as per RFC 5545 section 3.3.11.

## Example

A task:

- id: `01996a3e-1f2b-7c3d-8e4f-5a6b7c8d9e0f`
- title: `Water the plants`
- dueDate: `2026-08-06` (all-day, no dueTime)
- completed: no
- reminders: 10 minutes before

Exports as:

```
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Andreas Sander//Primico//1.0//EN
CALSCALE:GREGORIAN
METHOD:PUBLISH
BEGIN:VEVENT
UID:01996a3e-1f2b-7c3d-8e4f-5a6b7c8d9e0f@primico.app
DTSTAMP:20260101T120000Z
SUMMARY:Water the plants
DTSTART;VALUE=DATE:20260806
DTEND;VALUE=DATE:20260807
BEGIN:VALARM
TRIGGER:-PT10M
ACTION:DISPLAY
DESCRIPTION:Water the plants
END:VALARM
END:VEVENT
END:VCALENDAR
```

## Limitations

- The export contains only **dated tasks** (those with `dueDate`). Tasks without a due date
  (e.g., pure notes or action items in the Inbox) do not appear.
- Recurrence is represented by individual instances, not by `RRULE`. This means editing the
  series in your calendar app will not update Primico.
- The timezone is floating local time. If you need calendar entries in a specific timezone,
  consider importing into a calendar that supports timezone interpretation, or adjust the task
  times in Primico before exporting.

## Related

- [Backup file format](backup-format.md) — the other published export format
- [ADR 0006](../adr/0006-reminder-planning.md) — the reminder model this export maps from
- [Data model](data-model.md) — the domain types behind the export
