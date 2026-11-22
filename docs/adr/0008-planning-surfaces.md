---
title: "ADR 0008 — Planning surfaces: month grid, recurrence as chains, and locale-aware calendar"
sidebar:
  label: "0008 · Planning surfaces"
  order: 8
---
**Status:** accepted · **Date:** 2026-11-22 · **Supersedes nothing.** Part of epic
[#148](https://github.com/viberfasend/primico/issues/148).

## Context

Primico can tell you what is due. It cannot show you a **week** — Upcoming is a list grouped by
day header, which answers "what is next" but not "is Thursday already full", "what did I miss in
the second half of last month", or "move these five things past the trip". Every one of those
is a spatial question and a list is the wrong shape for it.

[#148](https://github.com/viberfasend/primico/issues/148) proposes three surfaces that answer them:
month grid, week agenda, and plan-my-day. This ADR records the decisions for the first of the
three — the month grid — and the two rules that apply to all of them: how recurrence is drawn,
and where the calendar's first day of week comes from.

The decision space splits cleanly:

- **What the grid *means***: which days it holds for a given month and locale, which tasks land in
  which cell. Pure, JVM-testable, no Compose runtime.
- **What the grid *looks* like**: the cell layout, the density modes, the drop target affordance.
  Composable, desktop-only in its richest form (drag and drop), phone-compatible in a
  simplified one (tap to open).
- **What dropping a task *does***: set its due date. The `:ui/dnd` kernel already carries the
  payload and the intent; this adds one new target kind, not a new mechanism.

## Decisions

### 1. Recurrence renders as the chain it is; the grid never projects a rule forward

A recurring task is **not** one row with a moving date. It is a chain of rows, each a concrete
occurrence with its own id, its own due date, its own `spawnedFromId` linking it back. A daily
task "take pills" at 09:00 is therefore a row per day, every one named "take pills", every one
standing in its own right. `CadenceRepository.setCompleted` keeps the finished row and inserts
a new row for the next occurrence; reopening deletes that new row. A delete tombstones the row
and every row that came from it.

This is the model the app already has (ADR 0002, decision on tombstones). It is also the only
model a spatial view can reasonably draw:

- **Projecting a rule forward would show occurrences that no id answers to.** The grid would
  light up for a drop, the user would drag a task onto a cell that *looks* empty, and the drop
  would fail silently because the due date it tried to write does not match any existing row.
  Worse, it would show occurrences the app has never created, so a query for "what is due on
  this day" would answer with rows that only exist in the view's imagination.
- **The chain model gives every occurrence a home.** Every cell that has a count or a title is
  backed by at least one row with that due date, and every row is drawn exactly where it
  belongs. A drop onto a cell writes that due date and lands on a row that either already
  existed or will be created the next time the rule fires.
- **It is also the cheaper implementation.** The grid iterates over `state.tasks` once, groups
  them by due date, and counts. No rule interpretation, no forward projection, no collision
  with holidays or exceptions. The same index powers Today, Upcoming and the search result —
  the month grid is one more consumer of it, filtered and bucketed differently.

**What it costs:** an infinite chain would be an infinite list, and a daily task from 2020 would
fill the database with rows. This is already mitigated: `RecurrenceRule` carries a `count`
limit when the rule is created, and the recurrence engine stops spawning successors once that
limit is reached (or once the rule's `endDate` is passed). A task that began daily in 2020
with a count of 500 stops spawning after 500 occurrences, and the grid stops drawing after the
last one. That is the same rule every recurrence consumer already follows.

The grid therefore answers "what occurrences exist" and *never* "what would this rule produce".

### 2. First day of week and month names come from `java.time` for `currentLocale()`

A calendar is a cultural artifact. The ISO week starts on Monday; the US and many other
countries start on Sunday; many Middle-Eastern locales start on Saturday. The grid must
respect whichever one the device is configured for.

`java.time.WeekFields.of(Locale.getDefault())` answers this on the JVM; Android's
`java.time` has the same semantics. The same call already backs the quick-add lexicons, so
the rule is a continuation, not a change:

- `WeekFields.of(locale).firstDayOfWeek` for the column the week starts in.
- `YearMonth.of(year, month).atDay(1)` and the above to compute the offset from Sunday.
- `Month.getDisplayName(TextStyle.SHORT, locale)` for the month label.

A test can pin a fixed `Locale` and assert an exact grid layout without touching the UI at all.

**What it means for the grid:** the month "January 2026" may start on a Thursday in a locale
whose week starts on Sunday, and on a Wednesday in a locale whose week starts on Monday. The
grid always shows six weeks when a month spans that many days, seven when it spans seven,
regardless of the locale. The first row is therefore a partial week when the month does not
start on the first day of that week.

### 3. A day cell is a drop target that sets the due date

The `:ui/dnd` kernel already carries everything a calendar needs:

- `DragPayload.TaskDrag` carries one or more task ids.
- `DropTarget.OntoDate(date)` already exists for the day headers in Upcoming.
- `DropIntent.RescheduleTask(taskIds, date)` already writes the due date.

A month-grid cell therefore registers itself as `DropTarget.OntoDate(date)` for that cell's
`LocalDate`. Dropping one or more tasks onto it resolves to `RescheduleTask` with that date.
`resolveDrop` already filters out no-ops — a task already due on that date does not light up
and does not trigger a write — and returns `Rejected` instead.

This is **desktop-only**, the same limitation the Upcoming day headers follow: Android does not
wrap its content in `DragAndDropHost` (ADR 0003, decision 3), and there is therefore no zone
registration and no gesture to drive it. The tap-to-open gesture works on both.

### 4. Month grid is a new top-level destination

- **Android:** a fifth bottom-bar item, `Routes.CALENDAR`, alongside Today, Upcoming, Inbox and
  Projects. Uses `FittedLabel` like the others. The icon is `CalendarMonth`.
- **Desktop:** a Views band entry in the sidebar, same band as Today, Upcoming and Inbox, between
  Upcoming and Inbox. Clicking it switches to `Route.Calendar`.
- **Shortcut:** `Ctrl`/`Cmd`+`Shift`+`C` — the first combined-modifier shortcut, since the
  single-letter slots 1–4 are already taken by the four existing top-level views.
- **Palette:** a "Calendar" command under the Views section.

Navigation into a day: tapping a cell opens that day's list, pushed on a phone, in the detail
pane on a wide window (the same rule Today's date headers follow on the desktop). A day with
no tasks still opens an empty list — a consistent answer to "what is due on this day".

## Consequences

- **No schema change.** The month grid reads from `tasks`, `projects` and `tags`, and none of
  them change. Recurrence is already stored as a chain of rows.
- **No new sync table.** The grid shows existing rows, not projected ones.
- **A new `:ui/calendar/` package:** `CalendarModel.kt` (pure), `MonthGridScreen.kt` (composable),
  and a `CalendarModelTest.kt` in `jvmSharedTest` for the first.
- **One new drop target kind:** `DropTarget.OntoDay` is added as an alias for `DropTarget.OntoDate`
  with the same intent, or the month grid can reuse `OntoDate` directly — both name a
  `LocalDate`, and `resolveDrop` already handles it.
- **Navigation strings:** `nav_calendar` in both `strings.xml` files.
- **Icon:** `CalendarMonth` already exists in `AppIcons` from Material Icons.

## Alternatives rejected

- **Projecting recurrence rules forward in the view.** Rejected in decision 1 — it would
  show occurrences with no backing row and make every drop onto an empty-looking cell a no-op
  or a write that fails to match what the user sees.
- **Hardcoding Monday as the first day of week.** Rejected in decision 2 — a US user expects
  Sunday, and forcing Monday on them is the same class of mistake as hardcoding `en_US`
  everywhere else.
- **A new drag-and-drop mechanism for the calendar.** Rejected in decision 3 — the kernel
  already carries the payload, the intent and the rule; the grid only needs to register a new
  kind of zone.
- **Reusing the Upcoming screen for the month grid.** The two have different layouts and
  different questions to answer: Upcoming is a list grouped by day header, the grid is a
  matrix of cells. They share the same `DropTarget.OntoDate` intent and the same
  `RescheduleTask` write, but the presentation is different enough to warrant a separate screen.
- **Making the grid read-only on the desktop.** Rejected in decision 3 — the drop target
  already exists in Upcoming, and the grid is just another place to reach it.
- **Putting Calendar under Projects or Tags.** Rejected in decision 4 — it is a top-level
  view of work by date, not a place work lives or a label it wears.
