---
title: Quick-add grammar
description: Everything the quick-add line understands — priority, project, tags, recurrence, dates and times — with the English and German keywords, plus the search and command-palette prefixes.
sidebar:
  order: 6
---

The quick-add line turns one sentence into a task: `Pay rent every 1st !p2 #Home` is a task
called *Pay rent*, priority P2, filed under *Home*, repeating on the 1st of every month. Everything
is optional; whatever the grammar does not claim stays in the title.

The grammar is [`QuickAddParser`](../../core/src/jvmShared/kotlin/de/andi1984/cadence/domain/parse/QuickAddParser.kt),
its regular expressions are [`QuickAddPatterns`](../../core/src/jvmShared/kotlin/de/andi1984/cadence/domain/parse/QuickAddPatterns.kt),
and every keyword lives in [`QuickAddLexicon`](../../core/src/jvmShared/kotlin/de/andi1984/cadence/domain/parse/QuickAddLexicon.kt).
Adding a language is covered in [Localise the app](../how-to/localise.md).

## How a line is read

1. Tokens are matched in a fixed order: **priority → project → tags → recurrence → due date →
   time**. Each kind takes its **first** match that does not overlap a token already taken;
   tags are the one kind that repeats.
2. Matching is case-insensitive, and a keyword must stand alone as a word (no letter, digit or
   `_` directly before or after it).
3. Every matched token is removed from the title. The rest is the title, with runs of whitespace
   collapsed and trailing `,`, `·` or `-` trimmed. An empty title adds nothing.
4. Matched tokens are reported as spans (`TokenKind`: `DATE`, `TIME`, `RECURRENCE`, `PRIORITY`,
   `PROJECT`, `TAG`) so the sheet can highlight them as you type.

## Priority

| Input | Result |
|---|---|
| `!p1` … `!p4`, `!P1` … `!P4` | Priority P1 … P4 |
| `!1` … `!4` | Priority P1 … P4 |
| no token | P3 (`Priority.DEFAULT`) |

## Project — `#`

`#` followed by letters, digits, `_` or `-`. Matched against existing projects, in this order,
case-insensitively:

1. the exact project name — `#home` finds *Home*;
2. the name with its spaces removed — `#Q3Launch` finds *Q3 Launch*;
3. the first project whose name starts with it — `#Ho` finds *Home*.

`#` only ever **selects**. A `#word` that matches no project is still removed from the title and
shown as a chip, but no project is created; the task goes to the project the sheet was opened in,
or the Inbox.

## Tags — `@`

`@` followed by letters, digits, `_` or `-`, and the `@` must start a word — so
`mail@example.com` is left alone. Any number of tags; duplicates collapse. Each handle is matched
with `matchingHandle`, case-insensitively:

1. the exact tag name;
2. the tag's *handle* — its name with spaces removed (`@deepwork` finds *Deep Work*);
3. a handle prefix — **only when exactly one tag matches** (with *workout* and *workshop* both
   present, `@work` matches neither).

A handle that matches nothing **creates** a tag of that name before the task is saved. That is the
difference from `#`, which never creates.

## Recurrence

Patterns are tried in this order, and the first that matches wins. `<n>` is a number written as
digits or as a word (see [Numbers](#spelled-out-numbers)).

| # | Shape | Example | Rule |
|---|---|---|---|
| 1 | `<n> <unit> <after-completion phrase>` | `3 days after done`, `2 Wochen nach Erledigung` | `AFTER_COMPLETION`, interval *n*, that unit |
| 2 | `<every> <nth> <weekday>[s] [<of the month>]`, where `<nth>` is `<last>`, `<digits>[<ordinal>]` or a number word | `every 2nd monday`, `every second monday`, `every last friday`, `jeden 2. Montag`, `jeden zweiten Montag des Monats` | monthly, `NTH_WEEKDAY`; `last` is 5 (the last one); others clamped to 1–5 |
| 3 | `<every> <digits><ordinal> [<of the month>]` or `<every> <number word> <of the month>` | `every 1st`, `every 15th of the month`, `jeden 1.`, `jeden ersten des Monats` | monthly, `DAY_OF_MONTH`, day clamped to 1–31 |
| 4 | `<every> <last> <workday>` / `<every> <last> <day>` | `every last weekday`, `jeden letzten Werktag`, `jeden letzten Tag` | monthly, `LAST_WEEKDAY` / `LAST_DAY` |
| 5 | `<every> [<doubled> \| <n>] <unit> [<on> <weekday>[s]]` | `every 2 weeks on thu`, `every other day`, `alle 2 Wochen am Donnerstag`, `jeden Tag`, `jeden dritten Tag` | interval *n* (2 for `other`/`zweiten`, else 1), that unit, optionally one weekday |
| 6 | `<every> <weekday>[s] [, \| <and> <weekday>[s]]…` | `every monday`, `every mon and thu`, `jeden Mo und Do` | weekly, those days |
| 7 | a period word | `daily`, `weekly`, `täglich`, `monatlich` | interval 1, that unit |

A spelled-out ordinal only makes a day-of-month rule when the month phrase follows:
`jeden ersten des Monats` is the 1st of the month, `jeden dritten Tag` is every third day.

## Due date

Tried in this order; `today` is the device's date.

| # | Shape | Example | Date |
|---|---|---|---|
| 1 | a today word | `today`, `tonight`, `heute`, `heute abend` | today |
| 2 | a day-after-tomorrow word | `day after tomorrow`, `übermorgen` | today + 2 |
| 3 | a tomorrow word | `tomorrow`, `morgen` | today + 1 |
| 4 | `<in> <n> <unit>` | `in 3 days`, `in one week`, `in drei Tagen` | today + *n* days/weeks/months/years |
| 5 | ISO date `YYYY-MM-DD` | `2026-12-24` | that date |
| 6 | `[<on>/<at>] D.M.` or `D.M.YYYY` | `24.12.`, `am 24.12.2026` | that date; without a year, this year, or next year if it has passed |
| 7 | `[<on>/<at>] D[.] <month>` | `24 Dec`, `24. Dez` | as above |
| 8 | `<month> D` | `Dec 24` | as above |
| 9 | `[<next>/<on>] <weekday>` | `friday`, `next friday`, `on fri`, `nächsten Freitag`, `am Freitag` | the next such day **after** today (a Friday typed on a Friday is a week out) |
| 10 | `<next> <week>` | `next week`, `nächste Woche` | today + 7 |

An impossible date (`31.02.`) is still consumed but sets no date. Weekday names of two letters
(`Mo`, `Di`, `So`) count only inside a recurrence, because they are ordinary German words.

## Time

| # | Shape | Example | Time |
|---|---|---|---|
| 1 | `[<at>] H:MM [<clock>]` | `17:00`, `at 17:00`, `um 17:00 Uhr` | that time; hour > 23 or minute > 59 sets none |
| 2 | `[<at>] H <clock>` | `18 Uhr`, `um 9 Uhr` | H:00 — needs the German `Uhr`, so English never reaches it |
| 3 | `[<at>] H am` / `H pm` | `9am`, `at 9 pm`, `12am` | 1–12 only; `12am` is 00:00, `12pm` is 12:00 |

## Which due date wins

| Line contains | Due date |
|---|---|
| an explicit date | that date — even alongside a recurrence |
| a recurrence and no date | `SCHEDULE`: `RecurrenceEngine.nextAfter(rule, yesterday)` — for a weekday or day-of-month rule, the first matching day from today on; for a plain interval, yesterday plus one interval (`daily` is today, `every 3 days` is today + 2, `weekly` is today + 6). `AFTER_COMPLETION`: today |
| only a time | **today** — `Kochen 18 Uhr` is today at 18:00 |
| nothing | no due date |

A recurrence beats the bare-time fallback: `every monday at 9am` is due next Monday (or today, on
a Monday) at 09:00, not today.

## Keywords

The line is read with `QuickAddLexicon.forLocale(currentLocale())`. **English is always folded
in**, so `every 2 weeks` works in a German install. Weekday and month names are not listed
anywhere: they come from `java.time` for the English locale, German (in a German install) and the
app's current locale — full and short forms, lowercased, trailing dot removed — which is why a
French install still reads `vendredi`.

| Slot | English | German (added on top) |
|---|---|---|
| unit | `day` `days` `week` `weeks` `month` `months` `year` `years` | `tag` `tage` `tagen` `woche` `wochen` `monat` `monate` `monaten` `jahr` `jahre` `jahren` |
| period | `daily` `weekly` `monthly` `yearly` `annually` | `täglich` `taeglich` `wöchentlich` `woechentlich` `monatlich` `jährlich` `jaehrlich` |
| every | `every` | `jeden` `jede` `jedes` `alle` |
| doubled (= 2) | `other` | `zwei` `zweiten` `zweite` `zweites` |
| after completion | `after done` `after completion` `after finishing` `after i finish` `after i'm done` `after im done` `after it's done` `after its done` | `nach erledigung` `nach der erledigung` `nach abschluss` `nach fertigstellung` `nach dem erledigen` `nachdem ich fertig bin` `nach erledigt` |
| today | `today` `tonight` | `heute` `heute abend` |
| tomorrow | `tomorrow` | `morgen` |
| day after tomorrow | `day after tomorrow` | `übermorgen` `uebermorgen` |
| in | `in` | `in` |
| next | `next` | `nächsten` `nächste` `nächstes` `naechsten` `naechste` `kommenden` `kommende` |
| week | `week` | `woche` |
| last | `last` | `letzten` `letzte` `letztes` |
| workday | `weekday` | `werktag` `werktags` `arbeitstag` |
| day | `day` | `tag` |
| of the month | `of the month` | `des monats` `im monat` |
| at | `at` | `um` `am` `an` |
| clock | — | `uhr` |
| and | `and` | `und` |
| on | `on` | `am` `an` |
| ordinal suffix | `st` `nd` `rd` `th` | `.` |

Spaces inside a phrase match any run of whitespace. Umlauts match in either case on every
platform (see the ICU note in [Localise the app](../how-to/localise.md)).

### Spelled-out numbers

Anywhere the grammar accepts digits it accepts these words, cardinal and ordinal alike.

| Value | English | German |
|---|---|---|
| 1 | `one` `first` | `ein` `eine` `einen` `einem` `einer`, `erst-` |
| 2 | `two` `second` | `zwei`, `zweit-` |
| 3 | `three` `third` | `drei`, `dritt-` |
| 4 | `four` `fourth` | `vier`, `viert-` |
| 5 | `five` `fifth` | `fünf` `fuenf`, `fünft-` `fuenft-` |
| 6 | `six` `sixth` | `sechs`, `sechst-` |
| 7 | `seven` `seventh` | `sieben`, `siebt-` `siebent-` |
| 8 | `eight` `eighth` | `acht`, `acht-` |
| 9 | `nine` `ninth` | `neun`, `neunt-` |
| 10 | `ten` `tenth` | `zehn`, `zehnt-` |
| 11 | `eleven` `eleventh` | `elf`, `elft-` |
| 12 | `twelve` `twelfth` | `zwölf` `zwoelf`, `zwölft-` `zwoelft-` |

A German ordinal stem (`-`) takes any of the endings `e`, `en`, `es`, `er`, `em`: `dritte`,
`dritten`, `drittes`, `dritter`, `drittem`.

## Examples

All dates assume today is **Wednesday 12 August 2026**, the date `QuickAddParserTest` pins; most
rows are that test's own cases. German rows are read with the German lexicon.

| Input | Title | Due | Time | Other |
|---|---|---|---|---|
| `Pay rent every 1st !p2 #Home` | Pay rent | 2026-09-01 | — | P2, project *Home*, monthly on day 1 |
| `Ship the notes #Q3Launch` | Ship the notes | — | — | project *Q3 Launch* |
| `Call the dentist tomorrow at 17:00 !p1` | Call the dentist | 2026-08-13 | 17:00 | P1 |
| `Chase the invoice in 3 days` | Chase the invoice | 2026-08-15 | — | |
| `Follow up in one week` | Follow up | 2026-08-19 | — | |
| `Steuer 24.12.` | Steuer | 2026-12-24 | — | |
| `Water the plants daily at 9am` | Water the plants | 2026-08-12 | 09:00 | daily |
| `Water the plants 3 days after done` | Water the plants | 2026-08-12 | — | 3 days after completion |
| `Take out recycling every 2 weeks on thu` | Take out recycling | 2026-08-13 | — | every 2 weeks on Thursday |
| `Weekly review every friday` | Weekly review | 2026-08-14 | — | weekly on Friday |
| `Team sync every 2nd monday` | Team sync | 2026-09-14 | — | 2nd Monday of the month |
| `Team sync every second monday` | Team sync | 2026-09-14 | — | same rule, spelled out |
| `Retro every last friday` | Retro | 2026-08-28 | — | last Friday of the month (`nthWeek` 5) |
| `Water plants every three days` | Water plants | 2026-08-14 | — | every 3 days |
| `Buy milk` | Buy milk | — | — | nothing parsed |
| `Kochen 18 Uhr` | Kochen | 2026-08-12 | 18:00 | bare time → today |
| `Kochen morgen 19 Uhr` | Kochen | 2026-08-13 | 19:00 | |
| `Zahnarzt anrufen morgen um 17:00` | Zahnarzt anrufen | 2026-08-13 | 17:00 | |
| `Rückruf übermorgen um 9 Uhr` | Rückruf | 2026-08-14 | 09:00 | |
| `Bericht nächsten Freitag` | Bericht | 2026-08-14 | — | |
| `Rechnung nachfassen in drei Tagen` | Rechnung nachfassen | 2026-08-15 | — | |
| `jeden Tag Frühstück zubereiten` | Frühstück zubereiten | 2026-08-12 | — | daily |
| `Müll rausbringen alle 2 Wochen am Donnerstag` | Müll rausbringen | 2026-08-13 | — | every 2 weeks on Thursday |
| `Miete zahlen jeden 1.` | Miete zahlen | 2026-09-01 | — | monthly on day 1 |
| `Miete zahlen jeden ersten des Monats` | Miete zahlen | 2026-09-01 | — | monthly on day 1 |
| `Blumen gießen jeden dritten Tag` | Blumen gießen | 2026-08-14 | — | every 3 days |
| `Blumen gießen 3 Tage nach Erledigung` | Blumen gießen | 2026-08-12 | — | 3 days after completion |
| `Bericht jeden letzten Werktag` | Bericht | 2026-08-31 | — | last weekday of the month |
| `Jour fixe jeden 2. Montag` | Jour fixe | 2026-09-14 | — | 2nd Monday of the month |
| `Payer le loyer vendredi` (French locale) | Payer le loyer | 2026-08-14 | — | weekday name from `java.time` |
| `Post the parcel @errand` | Post the parcel | — | — | tag *Errand* (existing) |
| `Chase the invoice @waiting` | Chase the invoice | — | — | creates tag *waiting* |
| `Post it @errand @deepwork @errand` | Post it | — | — | tags *Errand*, *Deep Work*, once each |
| `Reply to mail@example.com` | Reply to mail@example.com | — | — | not a tag |

## Search syntax

The Search screen ([`TaskLists.kt`](../../ui/src/jvmShared/kotlin/de/andi1984/cadence/ui/TaskLists.kt),
`searchList`) matches every task, completed ones included, sorted by the current sort mode.

| Query | Matches |
|---|---|
| blank | nothing |
| `@` alone | nothing |
| `@text` | tasks wearing a live tag whose name **or** handle *contains* `text` (case-insensitive) |
| `text` | tasks whose title or notes contain `text`, or that wear a tag whose name or handle contains it |

## Command palette prefixes

Desktop only, `Ctrl`/`Cmd`+`K` ([`CommandPaletteModel.kt`](../../ui/src/jvmShared/kotlin/de/andi1984/cadence/ui/palette/CommandPaletteModel.kt),
[`CommandPaletteDialog.kt`](../../app-desktop/src/main/kotlin/de/andi1984/cadence/desktop/ui/CommandPaletteDialog.kt)).

| First character | Restricts to | Entries |
|---|---|---|
| `>` | commands | New task, New project, Sync now, Switch light and dark, Switch comfortable and compact, Show or hide the sidebar, Keyboard shortcuts, and *Go to* Today / Upcoming / Inbox / Projects / Search / Settings |
| `#` | projects | every project, by name |
| `@` | tags | every tag, as `@handle` |
| anything else | everything | commands, projects, tags and open tasks (a task's project path is matched too, weaker than its title) |

Scoring is a subsequence match that rewards the first character (+8), a word start (+4) and
consecutive letters (+3), and penalises gaps. Results are grouped by kind in the order commands,
projects, tags, tasks, with at most 6 per kind. An empty query lists the first 6 of each kind.
`↑`/`↓` move, `Enter` runs, `Esc` closes.

## Related

- [Keyboard shortcuts](keyboard-shortcuts.md)
- [Recurrence](../concepts/recurrence.md) — how the next date is computed once a task exists
- [Tasks, projects and tags](../concepts/tasks-projects-tags.md) — why `@` creates and `#` does not
