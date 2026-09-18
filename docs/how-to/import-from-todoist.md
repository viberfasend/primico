---
title: Import your tasks from Todoist
description: Convert a Todoist CSV export into a Primico backup file with tools/todoist_import.py, then import it on a device.
sidebar:
  order: 6
---

Todoist exports one CSV per project. [`tools/todoist_import.py`](../../tools/todoist_import.py)
turns a whole export into a Primico backup file, which the app then **merges** into what's
already on the device. Everything lands in one staging project for you to sort.

## Prerequisites

- **Python 3.9+.** The script uses the standard library only, so there's nothing to install.
- A **Todoist backup**: in Todoist, Settings → Backups, download one and unzip it. You get a
  folder such as `Todoist backup 2026-08-12 2248 UTC/` with one `Name [id].csv` per project.
- Optionally a **Todoist API token** (Todoist → Settings → Integrations → Developer), for exact
  due dates on recurring tasks. See step 2.
- Primico installed on the device you're importing into.

## Steps

### 1. Do a dry run

Parse everything and write nothing, to see what the converter found:

```bash
python3 tools/todoist_import.py ~/Downloads/"Todoist backup 2026-08-12 2248 UTC" --dry-run
```

It prints a summary (projects, sections, tasks, subtasks, notes) and warns on stderr, one line
each, about any date it couldn't read. Those dates aren't dropped: they're kept verbatim in the
task's notes.

### 2. Decide whether to pass a token

A recurring task leaves Todoist as its **rule** only (`jährlich`, `every 2 weeks`), never as its
next occurrence. From the CSV alone, a recurring task can only land on today. With a token the
script asks the Todoist API for the real date and time of every task:

```bash
read -rs TODOIST_API_TOKEN && export TODOIST_API_TOKEN   # typed, not echoed, not in history
python3 tools/todoist_import.py ~/Downloads/"Todoist backup 2026-08-12 2248 UTC"
```

`--todoist-token TOKEN` works too, but leaves the token in your shell history. The report then
says how many dates came back exactly:

```
Todoist API: 149 task(s), 128 with a due date
19 file(s) -> 17 project(s), …
  77 recurring, 33 dated, 128 dated exactly from the Todoist API
```

Without a token nothing breaks: dated tasks still get their dates from the CSV, and only recurring
ones fall back to today.

### 3. Convert: one file, or one per project

By default the whole export becomes **one** `primico-backup.json` in the current directory:

```bash
python3 tools/todoist_import.py ~/Downloads/"Todoist backup 2026-08-12 2248 UTC"
```

To bring projects across a few at a time, `--split` writes one `cadence-<project>.json` per
Todoist project into `primico-import/`, skipping projects with no tasks:

```bash
python3 tools/todoist_import.py ~/Downloads/"Todoist backup 2026-08-12 2248 UTC" --split
```

```
19 file(s) -> 16 project(s), 14 section(s), 145 task(s), 4 subtask(s), 7 note(s)
  cadence-inbox.json: 21 task(s)
  cadence-garten.json: 18 task(s)
  …
wrote 16 file(s) to primico-import/ — import them under Settings -> Backup, one, several or all at once
```

Every split file names the **same** staging project, so importing them in any order or grouping
lands everything in one pile.

Options worth knowing:

| Flag | Effect |
|---|---|
| `-o PATH` | the output file, or the output folder with `--split` |
| `--import-project NAME` | rename the staging project (default `Import <today>`) |
| `--no-import-project` | no staging project: import straight into top-level projects and the Inbox |
| `--strip-labels` | move Todoist `@labels` out of the title into the notes |
| `--bare-year next-occurrence` | read a year-less date (`15 Mar`) as the upcoming one rather than this year's |
| `--recurring-due none` | give recurring tasks no due date at all |
| `--invert-priority` | read `PRIORITY 4` as P1, for exports numbered the API's way round |
| `--inbox-name NAME` | which export file holds the Todoist Inbox (default `Inbox`) |
| `--today YYYY-MM-DD` | the reference date for relative dates, for a reproducible run |

The full mapping (sections become sibling projects, `INDENT ≥ 2` becomes a subtask, `every!` becomes
an after-completion rule) is in [`tools/README.md`](../../tools/README.md).

### 4. Move the file to the device and import it

1. Get the JSON onto the device: any file transfer on a phone, or just the path on the desktop.
2. In Primico open **Settings → Data → Import backup** and pick the file. The picker is
   multi-select on Android and the desktop, so you can pick every `--split` file at once.
3. Confirm the dialog. It says what the import does: the file is **merged** into what's on the
   device, nothing is deleted, and where a task appears in both, the newer version wins.

Settings then reports what was imported, for example "Imported 16 projects and 145 tasks from 16
files."

### 5. Sort the pile

Everything is under one project called **`Import <date>`**. Nothing lands in your Inbox or beside
your existing projects. Todoist projects become subprojects of the pile, and a Todoist section
becomes a *sibling* named `Project · Section`, because Primico nests projects only one level deep.
Drag tasks into your own projects, and delete the pile when it's empty.

## How the merge behaves

- **Importing is idempotent.** Ids are derived from the source file and row, so converting the
  same export twice produces the same ids. Re-exporting from Todoist and importing again
  **updates** the rows from last time instead of duplicating them: greater `updatedAt` wins, and
  ties keep what's stored.
- **An import brings back what you deleted.** A record in the file that lands on a row this device
  has tombstoned is restored, stamped with the import's time so the revival outlives the server's
  tombstone. Sync never does this; only an import does, because a file is you asking for its
  contents. So deleting the staging project and importing the same file again gives you the pile
  back.
- **If you're signed in to sync**, the imported rows travel to your other devices like any other
  edit, on the next round.

## Keep your data out of the repository

A converted export is your real task list, and this repository is public. The default outputs
(`primico-backup.json` and `primico-import/`, at the repository root or under `tools/`) are listed
in `.gitignore`. If you pass `-o` with another name inside the checkout, `git status` **will**
show it. Write to a path outside the repository instead, for example `-o ~/primico-backup.json`.

## Verify

- `python3 tools/todoist_import.py --self-test` passes (the converter's own tests).
- After importing, the `Import <date>` project shows the task count the script reported, and a
  recurring task (with a token) is due on the same day Todoist shows.

## Related

- [Backup format](../reference/backup-format.md): the `cadence.backup` file the script writes.
- [Local-first](../concepts/local-first.md): why importing merges instead of replacing.
- [`tools/README.md`](../../tools/README.md): the complete row-by-row mapping and every repeat
  phrase it understands.
