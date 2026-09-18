---
title: Local-first
description: Why the SQLite database on each device is the source of truth, and what that forces on ids, deletes and signing out.
sidebar:
  order: 2
---

The SQLite database on each device is the source of truth. Primico is fully usable signed out and
offline, forever; an account is an optional extra that keeps *your own* devices in step. Nothing
the server does can take data away from a device, and signing out deletes nothing.

That one decision explains a surprising amount of the code: why ids are UUIDs minted in the app,
why nothing is ever `DELETE`d, why there are no foreign keys on the task table, and why import
merges instead of replacing.

## What "local-first" means here

- **Every read is local.** Screens draw from `CadenceUiState`, which is built from flows over the
  local database. No screen waits for a network.
- **Every write is local first.** A mutation lands in SQLite and the UI updates from that. Sync,
  if there is an account, runs two seconds later in the background ([Sync](sync.md)).
- **The account is optional and signed in from Settings.** Without one the sync engine makes no
  request at all. A build compiled without sync endpoints does not even offer the sign-in form
  (see [Sync](sync.md#a-build-without-a-server)).
- **A fresh install starts empty.** There is no seed data. Screenshots and demos are built by
  importing a backup file.

## Ids are minted by the app, not the database

Every task, project, section and tag has a **UUIDv7 string** id, minted by
[`CadenceRepository`](../../core/src/jvmShared/kotlin/de/andi1984/cadence/data/CadenceRepository.kt)
through [`UuidV7.random()`](../../core/src/jvmShared/kotlin/de/andi1984/cadence/domain/id/UuidV7.kt)
at the moment a row is created. `Task.id` defaults to `""`, and `upsertTask` mints a real id
exactly when it is still blank. The store ports' `insert` methods take a row that already carries
its final id and return nothing.

**Why not autoincrement?** Two devices editing offline would both mint id 7, and merging their
data would silently collapse two different tasks into one. **Why version 7 rather than 4?** It is
time-ordered, so inserts stay local in the SQLite index and "newest first" needs no extra column
([ADR 0001, decision 4](../adr/0001-desktop-app-and-multi-device-sync.md#4-identifiers-become-uuidv7)).

### Successor ids are derived

There is one place the app does *not* mint a random id. Completing a recurring task inserts a row
for the next occurrence, and two devices completing the same occurrence offline must not produce
two different next occurrences. So the successor's id is computed:

```kotlin
UuidV7.successorId(spawnedFromId, occurrenceDate)   // a name-based UUID
```

Both devices compute the same id, and the merge collapses them into one row. A recurring parent's
subtasks derive theirs the same way, with their own id as a discriminant so siblings do not
collide. [Recurrence](recurrence.md) explains the chain this belongs to.

## Deleting is a tombstone

Every row carries `updatedAt` and a nullable `deletedAt`, both epoch milliseconds. **A delete
stamps `deletedAt`; it never runs a `DELETE`.** Every ordinary read in the `.sq` files filters
`deletedAt IS NULL`, so nothing above the store ports can tell the difference.

**Why?** A delete has to travel. A device that merely fails to find a row cannot tell "deleted"
from "never heard of it", and helpfully puts it back on the next merge. A tombstone is a version
like any other: it competes on its `updatedAt` under last-writer-wins, so a delete on one device
and an edit on another resolve by time rather than by which arrived last
([ADR 0002, decision 4](../adr/0002-supabase-sync.md#4-deletion-becomes-a-tombstone)).

Two consequences when you add a query:

- Filter tombstones, unless you are sync.
- Use `selectByIdIncludingDeleted` when you need to compare against a tombstoned row.

Tombstones are collected after **90 days**, by the sync round's daily sweep. A device offline
longer than that will put back what the others deleted — an accepted loss, not a solved problem.

Even the Settings danger zone, *Delete all data*, is a tombstone wipe; see
[Undo and deletes](undo-and-deletes.md#the-danger-zone).

## No write deletes the row it is about to insert

SQLite implements `INSERT OR REPLACE` as *delete the conflicting row, then insert*. With foreign
keys on and cascades on `parentId`, saving an edit to a task used to delete its checklist, and
merging one project row deleted its subprojects. So writes are upserts —
`updateRow` plus `insertIfAbsent` — and the merge is the same pair with last-writer-wins moved
into the UPDATE's `WHERE` (`updateIfOlder`).

The foreign keys went too. When rows arrive in whatever order a pull pages them, a task can land
before its project, and a constraint would fail the whole merge. Links are **repaired on read**
instead: a task naming a missing project shows in the Inbox, a tag id nothing answers to is
dropped by `CadenceUiState.tagsOf`. The one cascade that remains is `attachmentRow → taskRow`,
because attachments are local and never merged. [Database](../reference/database.md) has the
schema.

## Import merges; it never replaces

Importing a backup file runs `BackupStore.mergeAll` in one transaction under the same rule sync
uses: greater `updatedAt` wins, ties keep what is stored. It used to delete both tables and
reinsert, which made importing an old file a data-loss event.

There is one deliberate exception, and it is import's alone: **a record in the file that lands on
a row this device has tombstoned is restored**, stamped with the import's clock so the revival
outlives the tombstone the server still holds. A file is a person asking for its contents, and a
file written *before* the delete it is meant to undo is the ordinary case. The format itself is
in [Backup format](../reference/backup-format.md).

## Why not sync through a shared file?

[ADR 0001](../adr/0001-desktop-app-and-multi-device-sync.md) originally chose a sync that needed
no server: a file in a folder the user already syncs (Syncthing, Nextcloud, iCloud Drive). What
shipped was one `backup.json`, written on every change and read on foreground. It was removed,
for three reasons recorded in [ADR 0002](../adr/0002-supabase-sync.md):

1. **Two devices writing one file is a conflict no program resolves.** The sync client keeps
   both copies and renames one, and the user is left holding two files.
2. **Import was destructive**, so the losing side of a conflict lost everything added since the
   file was written.
3. **Deletes did not travel at all** — nothing wrote `deletedAt` yet — so the other device put
   deleted tasks back.

The per-device-file design meant to fix the first problem was written and never wired in. The
replacement is a small hosted Postgres that is still only a *hub*: each device keeps its full
database and the server only relays rows between them. ADR 0005 moved that hub to Neon.

## What signing out does

`CadenceSyncEngine.signOut()` forgets the session, every pull cursor and the push watermark — and
deletes **nothing** local. Server-side revocation is best-effort, so signing out works on a
plane. Clearing the cursors means signing back in starts from the beginning, which is correct:
this device has no idea what the server did meanwhile. The first round after signing in pushes
the whole local database and pulls everything the account holds.

## Related

- [Sync](sync.md)
- [Recurrence](recurrence.md)
- [Data model](../reference/data-model.md)
- [Database](../reference/database.md)
- [Backup format](../reference/backup-format.md)
- [ADR 0001 — Desktop app and multi-device sync](../adr/0001-desktop-app-and-multi-device-sync.md)
- [ADR 0002 — Supabase sync](../adr/0002-supabase-sync.md)
