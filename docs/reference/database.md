---
title: Database schema
description: The local SQLite schema at version 5 — every table and column in declared order, the migration chain, the storage encodings, the rules for changing it, and where the file lives.
sidebar:
  order: 3
---

Each device keeps one SQLite database, `cadence.db`, and it is the source of truth
([Local-first](../concepts/local-first.md)). The schema is written in SQLDelight files under
[`core/src/commonMain/sqldelight/de/andi1984/cadence/data/db/`](../../core/src/commonMain/sqldelight/de/andi1984/cadence/data/db/),
which generate the `CadenceDatabase` class and one `*Queries` class per file. Every conversion
between a column and a domain value lives in
[`SqlDelightStores.kt`](../../core/src/jvmShared/kotlin/de/andi1984/cadence/data/db/SqlDelightStores.kt)
(`Task.toRow()` / `toTask` and the same pair per entity), and nowhere else.

**Schema version: 5.** Tables are named `…Row` so the generated row classes do not collide with
the domain types (`TaskRow` vs `Task`).

## Tables

Columns are listed in their declared order. That order matters: every read is `SELECT *` and the
generated mapper takes columns positionally, so a fresh database and a migrated one must agree
column for column.

### taskRow

[`Task.sq`](../../core/src/commonMain/sqldelight/de/andi1984/cadence/data/db/Task.sq)

| # | Column | SQLite type | Null | Encoding |
|---|---|---|---|---|
| 1 | `id` | `TEXT` | no, `PRIMARY KEY` | UUIDv7 |
| 2 | `title` | `TEXT` | no | |
| 3 | `notes` | `TEXT` | yes | |
| 4 | `priority` | `INTEGER` | no | `Priority.level`, 1–4 |
| 5 | `projectId` | `TEXT` | yes | project id; `NULL` = Inbox. No `REFERENCES` |
| 6 | `parentId` | `TEXT` | yes | parent task id. No `REFERENCES` |
| 7 | `spawnedFromId` | `TEXT` | yes | the occurrence this row replaces. No `REFERENCES` |
| 8 | `dueDate` | `INTEGER` | yes | epoch day |
| 9 | `dueTime` | `INTEGER` | yes | second of day |
| 10 | `reminderTime` | `INTEGER` | yes | second of day |
| 11 | `completedAt` | `INTEGER` | yes | epoch millis; `NULL` while open |
| 12 | `createdAt` | `INTEGER` | no | epoch millis |
| 13 | `sortOrder` | `INTEGER` | no | |
| 14 | `recurrence` | `TEXT` | yes | packed by `RecurrenceCodec` — see [Encodings](#encodings) |
| 15 | `updatedAt` | `INTEGER` | no | epoch millis |
| 16 | `deletedAt` | `INTEGER` | yes | epoch millis; tombstone |
| 17 | `sectionId` | `TEXT` | yes | section id; added by `3.sqm` |
| 18 | `tagIds` | `TEXT` | yes | comma-separated tag ids by `TagIdsCodec`, `NULL` for none; added by `4.sqm` |

Indexes: `idx_task_project` (`projectId`), `idx_task_section` (`sectionId`), `idx_task_parent`
(`parentId`), `idx_task_spawned_from` (`spawnedFromId`), `idx_task_due` (`dueDate`),
`idx_task_completed` (`completedAt`), `idx_task_sort` (`sortOrder`), `idx_task_updated`
(`updatedAt`, what the push reads).

### projectRow

[`Project.sq`](../../core/src/commonMain/sqldelight/de/andi1984/cadence/data/db/Project.sq)

| # | Column | SQLite type | Null | Encoding |
|---|---|---|---|---|
| 1 | `id` | `TEXT` | no, `PRIMARY KEY` | UUIDv7 |
| 2 | `name` | `TEXT` | no | |
| 3 | `colorHex` | `TEXT` | no | `#RRGGBB` |
| 4 | `parentId` | `TEXT` | yes | parent project id. No `REFERENCES` |
| 5 | `sortOrder` | `INTEGER` | no | |
| 6 | `updatedAt` | `INTEGER` | no | epoch millis |
| 7 | `deletedAt` | `INTEGER` | yes | epoch millis; tombstone |

Indexes: `idx_project_parent`, `idx_project_sort`, `idx_project_updated`.

### sectionRow

[`Section.sq`](../../core/src/commonMain/sqldelight/de/andi1984/cadence/data/db/Section.sq) — added by `3.sqm`.

| # | Column | SQLite type | Null | Encoding |
|---|---|---|---|---|
| 1 | `id` | `TEXT` | no, `PRIMARY KEY` | UUIDv7 |
| 2 | `projectId` | `TEXT` | no | owning project id. No `REFERENCES` |
| 3 | `name` | `TEXT` | no | |
| 4 | `sortOrder` | `INTEGER` | no | |
| 5 | `updatedAt` | `INTEGER` | no | epoch millis |
| 6 | `deletedAt` | `INTEGER` | yes | epoch millis; tombstone |

Indexes: `idx_section_project`, `idx_section_sort`, `idx_section_updated`.

### tagRow

[`Tag.sq`](../../core/src/commonMain/sqldelight/de/andi1984/cadence/data/db/Tag.sq) — added by
`4.sqm`. Identity only; there is deliberately no join table.

| # | Column | SQLite type | Null | Encoding |
|---|---|---|---|---|
| 1 | `id` | `TEXT` | no, `PRIMARY KEY` | UUIDv7 |
| 2 | `name` | `TEXT` | no | |
| 3 | `colorHex` | `TEXT` | no | `#RRGGBB` |
| 4 | `sortOrder` | `INTEGER` | no | |
| 5 | `updatedAt` | `INTEGER` | no | epoch millis |
| 6 | `deletedAt` | `INTEGER` | yes | epoch millis; tombstone |

Indexes: `idx_tag_sort`, `idx_tag_updated`.

### attachmentRow

[`Attachment.sq`](../../core/src/commonMain/sqldelight/de/andi1984/cadence/data/db/Attachment.sq)
— local only: never synced, never tombstoned, deleted with a real `DELETE`.

| # | Column | SQLite type | Null | Encoding |
|---|---|---|---|---|
| 1 | `id` | `TEXT` | no, `PRIMARY KEY` | UUIDv7 |
| 2 | `taskId` | `TEXT` | no | `REFERENCES taskRow(id) ON DELETE CASCADE` — the schema's only foreign key |
| 3 | `kind` | `TEXT` | no | `AttachmentKind` by name (`LINK`, `FILE`) |
| 4 | `name` | `TEXT` | no | |
| 5 | `mimeType` | `TEXT` | no | |
| 6 | `sha256` | `TEXT` | yes | lowercase hex; `NULL` for a link |
| 7 | `sizeBytes` | `INTEGER` | no | |
| 8 | `url` | `TEXT` | yes | `NULL` for a file |
| 9 | `createdAt` | `INTEGER` | no | epoch millis |
| 10 | `sortOrder` | `INTEGER` | no | |

Indexes: `idx_attachment_task` (`taskId`), `idx_attachment_sha` (`sha256`, read by blob reclaim on
every delete). An unknown `kind` reads back as `FILE` when `sha256` is set, else `LINK`.

### syncStateRow

[`SyncState.sq`](../../core/src/commonMain/sqldelight/de/andi1984/cadence/data/db/SyncState.sq)
— exactly one row, inserted with the table. Added by `1.sqm`.

| # | Column | SQLite type | Null | Meaning |
|---|---|---|---|---|
| 1 | `id` | `INTEGER` | no, `PRIMARY KEY CHECK (id = 1)` | always 1 |
| 2 | `session` | `TEXT` | yes | serialised `NeonSession`; `NULL` = signed out |
| 3 | `taskCursor` | `TEXT` | yes | ISO-8601 `server_updated_at` of the newest task pulled |
| 4 | `projectCursor` | `TEXT` | yes | same, for projects |
| 5 | `pushWatermark` | `INTEGER` | no, `DEFAULT 0` | epoch millis of the newest `updatedAt` pushed |
| 6 | `lastSyncedAt` | `INTEGER` | yes | epoch millis |
| 7 | `lastSweepAt` | `INTEGER` | yes | epoch millis |
| 8 | `sectionCursor` | `TEXT` | yes | added by `3.sqm` |
| 9 | `tagCursor` | `TEXT` | yes | added by `4.sqm` |

`clearSync` (sign-out) resets every column but `id` to `NULL` / `0` and touches no other table.

## Encodings

| Domain value | Column value | Converted in |
|---|---|---|
| `LocalDate` | epoch day (`LocalDate.toEpochDay()`) | `SqlDelightStores.kt` |
| `LocalTime` | second of day (`LocalTime.toSecondOfDay()`) | `SqlDelightStores.kt` |
| `Instant` | epoch millis | `SqlDelightStores.kt` |
| `Priority` | `level` (1–4); anything else reads as `P3` | `SqlDelightStores.kt` |
| `RecurrenceRule` | `v1;key=value;…` text | [`RecurrenceCodec`](../../core/src/jvmShared/kotlin/de/andi1984/cadence/data/db/RecurrenceCodec.kt) |
| `List<String>` tag ids | `id1,id2,…`, or `NULL` when empty | [`TagIdsCodec`](../../core/src/jvmShared/kotlin/de/andi1984/cadence/data/db/TagIdsCodec.kt) |

**`RecurrenceCodec` format.** Segments joined by `;`. The first is always `v1`; then `mode`,
`interval`, `unit` and `monthly` always; `dows`, `dom`, `nthWeek`, `nthDow` only when set.

| Key | Value | Example |
|---|---|---|
| `mode` | `RecurrenceMode` name — required; missing or unknown decodes the whole rule to `null` | `mode=SCHEDULE` |
| `interval` | integer ≥ 1 | `interval=2` |
| `unit` | `RecurrenceUnit` name, default `WEEK` | `unit=WEEK` |
| `monthly` | `MonthlyMode` name, default `DAY_OF_MONTH` | `monthly=DAY_OF_MONTH` |
| `dows` | comma-separated `DayOfWeek` names, Monday first | `dows=MONDAY,THURSDAY` |
| `dom` | day of month | `dom=15` |
| `nthWeek` | 1–5 | `nthWeek=2` |
| `nthDow` | `DayOfWeek` name | `nthDow=MONDAY` |

Example: `v1;mode=SCHEDULE;interval=2;unit=WEEK;monthly=DAY_OF_MONTH;dows=THURSDAY`. A value that
does not start with `v1`, or is blank, decodes to `null`. Unknown keys are ignored — which is how a
stored `keepMissed` segment from older installs is now skipped.

**`TagIdsCodec` format.** Blank ids are dropped and duplicates removed on the way in and out;
order is preserved.

## Migration chain

A schema change edits the `.sq` file **and** adds the next `N.sqm`, which migrates from version
*N* to *N + 1*. Step by step: [Add a column](../how-to/add-a-column.md).

| File | From → to | What it does |
|---|---|---|
| — | 1 | The version-1 schema: `taskRow` and `projectRow` (with foreign keys and cascades at the time) and `attachmentRow`. Declared fresh when SQLDelight replaced Room. |
| [`1.sqm`](../../core/src/commonMain/sqldelight/de/andi1984/cadence/data/db/1.sqm) | 1 → 2 | Creates `syncStateRow` (seven columns) and inserts its one row. |
| [`2.sqm`](../../core/src/commonMain/sqldelight/de/andi1984/cadence/data/db/2.sqm) | 2 → 3 | Rebuilds `taskRow` and `projectRow` without their foreign keys (copy to `…New`, drop, rename) and recreates every index, adding `idx_task_updated` and `idx_project_updated`. |
| [`3.sqm`](../../core/src/commonMain/sqldelight/de/andi1984/cadence/data/db/3.sqm) | 3 → 4 | Sections: `ALTER TABLE taskRow ADD COLUMN sectionId`, `idx_task_section`, `CREATE TABLE sectionRow` and its three indexes, `ALTER TABLE syncStateRow ADD COLUMN sectionCursor`. |
| [`4.sqm`](../../core/src/commonMain/sqldelight/de/andi1984/cadence/data/db/4.sqm) | 4 → 5 | Tags: `ALTER TABLE taskRow ADD COLUMN tagIds`, `CREATE TABLE tagRow` and its two indexes, `ALTER TABLE syncStateRow ADD COLUMN tagCursor`. No join table. |

How each platform runs the chain:

| Platform | Driver | Version tracking | Foreign keys switched on |
|---|---|---|---|
| Android | `AndroidSqliteDriver` with `CadenceDatabase.Schema` | SQLite's own, via the driver's `onCreate`/`onUpgrade` callback | in `onOpen`, which runs after `onUpgrade` |
| Desktop | `JdbcSqliteDriver("jdbc:sqlite:<file>")` | `PRAGMA user_version`, by hand in [`DatabaseDriverFactory`](../../core/src/jvmMain/kotlin/de/andi1984/cadence/data/db/DatabaseDriverFactory.kt). A new file is created at the current version; an existing file with `user_version = 0` is read as version 1 | `PRAGMA foreign_keys = ON` after migrating |

## Gotchas checklist

- [ ] **Append new columns last.** `ALTER TABLE … ADD COLUMN` can only append and every read is
      `SELECT *`, so a column declared anywhere else in the `.sq` file would give a fresh database a
      different column order than a migrated one. `sectionId`, `tagIds`, `sectionCursor` and
      `tagCursor` all sit last for this reason.
- [ ] **Convert in exactly two places.** A new column is converted in `toRow()` and in the read
      mapper in `SqlDelightStores.kt`, never in a query wrapper's argument list.
- [ ] **Upsert, never `INSERT OR REPLACE`.** SQLite runs `REPLACE` as delete-then-insert. A write
      is `updateRow` followed by `insertIfAbsent` (`INSERT OR IGNORE INTO … VALUES ?`) in one
      transaction — minSdk 26 ships SQLite 3.19, before `ON CONFLICT … DO UPDATE` (3.24). A merge is
      the same pair with last-writer-wins in the `WHERE` (`updateIfOlder … AND updatedAt <
      :updatedAt`). `attachmentRow` alone keeps `insertOrReplace`: nothing references it.
- [ ] **No foreign keys between synced tables.** Rows arrive in whatever order a pull pages them;
      a task landing before its project would fail the merge. The one foreign key is
      `attachmentRow.taskId → taskRow(id) ON DELETE CASCADE`. Foreign keys are switched on only
      after migrating, or `2.sqm`'s `DROP TABLE taskRow` would cascade into every attachment.
- [ ] **Delete by tombstone.** Set `deletedAt` (and `updatedAt`) instead of `DELETE`, with
      `AND deletedAt IS NULL` so a second delete does not restamp it. Every ordinary read filters
      `deletedAt IS NULL`; only `selectByIdIncludingDeleted`, `selectChangedSince`,
      `selectAllIncludingDeleted`, `updateIfOlder`, `reviveIfDeleted` and `collectTombstones` see
      tombstones. `collectTombstones` is the one real `DELETE` of a synced row, run by sync for
      tombstones older than 90 days.
- [ ] **Dates are epoch days, times are seconds of day, instants are epoch millis** — only inside
      `SqlDelightStores.kt`.
- [ ] **Chunk every `IN :list` query.** SQLDelight binds one parameter per element and
      `SQLITE_MAX_VARIABLE_NUMBER` is 999 on the SQLite of Android API 26–29. The attachment
      store chunks at `SQL_VARIABLE_LIMIT = 900` and de-duplicates across chunks. The desktop driver
      and recent phones allow 32766, so no test reproduces the crash.
- [ ] **`sortOrder != :sortOrder` in `updateSortOrder` is load-bearing.** It keeps a reorder from
      restamping `updatedAt` on rows that did not move, and so from pushing a whole list.
- [ ] **`completeIfOpen` / `reopenIfDone` report through `changes()`,** read in the same
      transaction, so the store — not the caller's snapshot — decides whether this call changed
      the row.
- [ ] **The desktop tracks its version in `PRAGMA user_version`,** where 0 on an existing file
      means version 1.

## Where the file lives

| Platform | Database | Attachment blobs | Staging for blob copies |
|---|---|---|---|
| Android | the app's database directory: `databases/cadence.db` under `/data/data/de.andi1984.cadence/` (`…cadence.debug/` for the debug build) | `files/attachments/<2 hex>/<sha256>` | `files/attachments-tmp/` |
| Linux | `$XDG_DATA_HOME/primico/cadence.db`, default `~/.local/share/primico/cadence.db` | `…/primico/attachments/` | `…/primico/attachments-tmp/` |
| macOS | `~/Library/Application Support/Primico/cadence.db` | `…/Primico/attachments/` | `…/Primico/attachments-tmp/` |
| Windows | `%APPDATA%\Primico\cadence.db`, default `~\AppData\Roaming\Primico\cadence.db` | `…\Primico\attachments\` | `…\Primico\attachments-tmp\` |

The file name is `CADENCE_DATABASE_FILE_NAME` in
[`DatabaseDriverFactory.kt`](../../core/src/jvmShared/kotlin/de/andi1984/cadence/data/db/DatabaseDriverFactory.kt);
the desktop directory comes from
[`PlatformDirs.dataDir()`](../../app-desktop/src/main/kotlin/de/andi1984/cadence/desktop/platform/PlatformDirs.kt).
On first start after the rename, an existing `cadence`/`Cadence` directory with no
`primico`/`Primico` beside it is renamed in place; if the rename fails, the old directory is used.

Android's auto-backup includes `cadence.db` (with `-shm`/`-wal`) and the shared preferences, and
leaves the attachment blobs out of cloud backup
([`data_extraction_rules.xml`](../../app-android/src/main/res/xml/data_extraction_rules.xml),
[`backup_rules.xml`](../../app-android/src/main/res/xml/backup_rules.xml)).

## Related

- [Data model](data-model.md) — the domain types behind these columns
- [Add a column](../how-to/add-a-column.md)
- [Undo and deletes](../concepts/undo-and-deletes.md) — tombstones, the danger zone
- [ADR 0002](../adr/0002-supabase-sync.md) — why deletes are tombstones
