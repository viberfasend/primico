---
title: Concepts
description: Why Primico is built the way it is — the reasoning, the trade-offs and the diagrams behind the code.
sidebar:
  label: Overview
  order: 0
---

These pages explain **why** the code looks the way it does. They are meant to be read top to
bottom, not searched; each one ends with links to the reference pages and ADRs behind it. If you
read only one, read [Architecture at a glance](architecture.md).

## The shape of the app

- **[Architecture at a glance](architecture.md)** — four modules, ports and adapters, one
  ViewModel feeding one state. The map the other pages hang off.
- **[Local-first](local-first.md)** — the SQLite database on each device is the truth, and what
  that forces on ids, deletes and signing out.
- **[Primico, and why the code says Cadence](naming.md)** — what the rename changed and what it
  deliberately left alone.

## Data and how it moves

- **[Sync](sync.md)** — how a round pulls, merges and pushes under last-writer-wins, and when
  rounds run.
- **[Tasks, projects and tags](tasks-projects-tags.md)** — importance first, one project per
  task, labels packed on the task.
- **[Recurrence](recurrence.md)** — a recurring task is a chain of rows, not one row with a
  moving date.
- **[Undo and deletes](undo-and-deletes.md)** — a delete is held back for five seconds, and every
  delete is a tombstone.
- **[Attachments](attachments.md)** — the row is the truth, the bytes are a cache.

## Platforms and quality

- **[Reminders](reminders.md)** — one planner and one reconciler decide every alarm; Android's
  are exact, the desktop polls.
- **[The desktop shell](desktop-shell.md)** — sidebar, two panes, drag and drop and a command
  palette, mostly shared code that Android switches off.
- **[Testing philosophy](testing-philosophy.md)** — real SQLite under every storage test, no
  mocking library, and the one coroutine rule that has cost real time.

Looking for a table, a field or a shortcut instead? That is [Reference](../reference/README.md).
