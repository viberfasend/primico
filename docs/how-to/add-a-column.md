---
title: Add a column to a table
description: Change the schema end to end — the SQLDelight table and its migration, the row mappers, the domain model, the backup file, the sync wire and the Neon mirror — in the order that keeps every install working.
sidebar:
  order: 2
---

A new field on a task touches seven places, from the SQLite file on every phone to the Postgres
mirror. This guide is a checklist in the order that keeps old installs, old backup files and old
clients working. It uses a hypothetical **`estimateMinutes: Int?`** on `Task` as the running
example. Substitute your own field.

## Prerequisites

- You've read the persistence notes in [the database reference](../reference/database.md). The
  three rules that matter most are restated below.
- You can run the [test suite](run-tests.md), and docker for `neon/tests/run.sh` if the field
  syncs.
- Decide first: **is this a column at all?** A new recurrence-rule field is *not* a column. It
  extends `RecurrenceCodec`'s packed `v1;key=value;…` string (and its `VERSION` handling) instead.
  Device-only state, like window bounds or per-device settings, belongs in a settings store, not
  in a synced table.

:::caution[Three rules that are not style]
1. **A new column goes last** in its `CREATE TABLE`. `ALTER TABLE … ADD COLUMN` can only append,
   every read is `SELECT *`, and the generated mapper takes columns *by position*. A fresh install
   and a migrated one must agree on column order exactly.
2. **Every schema change ships with a migration** (`N.sqm`). Installs of every older version exist.
3. **The backup file and the sync wire are published contracts.** Adding an *optional* field is
   fine. Renaming or retyping one is not.
:::

## Steps

### 1. Append the column to the table

- [ ] In [`core/src/commonMain/sqldelight/.../Task.sq`](../../core/src/commonMain/sqldelight/de/andi1984/cadence/data/db/Task.sq),
  add the column **at the end** of `CREATE TABLE taskRow`, after `tagIds`, with a comment
  saying which migration adds it:

  ```sql
      tagIds TEXT,
      -- Estimated effort in minutes, or null. Appended by `5.sqm` — last for the reason
      -- `sectionId` explains.
      estimateMinutes INTEGER
  );
  ```

- [ ] In the same file, add the column to the `SET` list of **all three** full-row updates:
  `updateRow`, `updateIfOlder` and `reviveIfDeleted`. `insertIfAbsent` binds the whole row with
  `VALUES ?`, so it needs no edit.

### 2. Add the migration

- [ ] Create the next migration file beside the `.sq` files. The chain today is `1.sqm` through
  `4.sqm` (schema **version 5**), so yours is **`5.sqm`**, which migrates 5 → 6:

  ```sql
  -- 5 → 6: a task's estimate. One appended column, nothing rebuilt.
  ALTER TABLE taskRow ADD COLUMN estimateMinutes INTEGER;
  ```

  Nothing else is needed to wire it up. SQLDelight derives `CadenceDatabase.Schema.version`
  from the migration files. Android's `AndroidSqliteDriver` runs the migration from its upgrade
  callback. The desktop's
  [`DatabaseDriverFactory`](../../core/src/jvmMain/kotlin/de/andi1984/cadence/data/db/DatabaseDriverFactory.kt)
  compares `PRAGMA user_version` with the schema version and migrates.

:::tip
Prefer migrations that only `ALTER TABLE … ADD COLUMN` or `CREATE TABLE`, the way `3.sqm` and
`4.sqm` do. Rebuilding a table (as `2.sqm` had to, to drop constraints) is where the attachment
cascade once nearly deleted every attachment.
:::

### 3. Map it in exactly one place each way

In [`core/src/jvmShared/.../data/db/SqlDelightStores.kt`](../../core/src/jvmShared/kotlin/de/andi1984/cadence/data/db/SqlDelightStores.kt):

- [ ] `Task.toRow()`: domain → column. Every conversion (epoch day, seconds of day, packing)
  happens here and nowhere else:

  ```kotlin
      tagIds = TagIdsCodec.encode(tagIds),
      estimateMinutes = estimateMinutes?.toLong(),
  )
  ```

- [ ] `toTask(...)`: column → domain. Add the parameter **last**, since `SELECT *` hands the
  mapper columns in table order:

  ```kotlin
      tagIds: String?,
      estimateMinutes: Long?,
  ) = Task(
      // …
      estimateMinutes = estimateMinutes?.toInt(),
  ```

- [ ] Forward the new row field in the three wrappers that call the named-parameter updates:
  `TaskQueries.upsertRow` (`updateRow`) and `TaskQueries.mergeRow` (`reviveIfDeleted` and
  `updateIfOlder`). These only forward `row.estimateMinutes` untouched. No conversion belongs in
  a wrapper's argument list.

### 4. Add the field to the domain model

- [ ] In [`domain/model/Task.kt`](../../core/src/jvmShared/kotlin/de/andi1984/cadence/domain/model/Task.kt),
  add `val estimateMinutes: Int? = null`. A default keeps every existing `Task(...)` call
  compiling.

Remember that smart casts don't cross a module boundary. In `:ui`, write
`task.estimateMinutes?.let { … }` or bind a local, not `if (task.estimateMinutes != null)
task.estimateMinutes + 1`.

### 5. Carry it in the backup file (optional field, no version bump)

In [`domain/backup/BackupCodec.kt`](../../core/src/jvmShared/kotlin/de/andi1984/cadence/domain/backup/BackupCodec.kt):

- [ ] Add `val estimateMinutes: Int? = null` to `BackupTask`.
- [ ] Set it in `Task.toBackup()` and read it in `BackupTask.toDomain()`.
- [ ] **Leave `BackupCodec.VERSION` alone.** Older readers ignore unknown keys
  (`ignoreUnknownKeys = true`), and a higher `version` makes them refuse the *whole file*. Bump
  it only for a change an older reader would misread, and keep the old shape readable when you
  do.

### 6. Carry it on the sync wire

In [`data/sync/RemoteRecords.kt`](../../core/src/jvmShared/kotlin/de/andi1984/cadence/data/sync/RemoteRecords.kt):

- [ ] Add `@SerialName("estimate_minutes") val estimateMinutes: Int? = null` to `RemoteTask`
  (snake_case, matching the Postgres column).
- [ ] Set it in `Task.toRemote()` and read it in `RemoteTask.toDomain()`.

`SyncJson` has `encodeDefaults = true`, so the key is always sent, even as `null`. That's
load-bearing: an upsert only updates the columns it names, and PostgREST rejects a batch whose
objects don't all carry the same keys (`PGRST102`).

### 7. Add the column to the Neon mirror

- [ ] Add the next file in [`neon/migrations/`](../../neon/migrations/). Today's last is
  `0002_lock_bookkeeping.sql`, so yours is `0003_task_estimate.sql`. Make it **idempotent**,
  because these files also get pasted into the Neon console's SQL editor:

  ```sql
  -- A task's estimate in minutes (see core's 5.sqm). Nullable, so an install that predates it
  -- can keep upserting rows without the key.
  alter table public.tasks add column if not exists estimate_minutes integer;
  ```

- [ ] **No grant is needed** for a column on an existing table: `0001` grants the four tables at
  table level, and that covers new columns. A **new table** is different. Since
  `0002_lock_bookkeeping.sql` revoked Neon's default privileges, a table added to `public` is
  *not* reachable through the Data API until your migration grants it (and enables row-level
  security with an owner policy, the way `0001` does for the four tables). The
  [`neon/CLAUDE.md`](../../neon/CLAUDE.md) notes explain the default-privileges gotcha that made
  this necessary.
- [ ] Apply it: `CADENCE_NEON_DB_URL='postgresql://…' bash neon/migrate.sh`.

:::danger[Migrate the server before you ship the client]
A client that sends `estimate_minutes` to a mirror without the column gets an HTTP 400 from the
Data API on every push. Every round fails with "the server returned an error", and nothing that
device writes syncs until the migration lands. Apply the Neon migration first, then release the
app. The reverse order is safe: older installs never send the key, the column stays null for
their inserts, and `SyncJson`'s `ignoreUnknownKeys` lets them read rows that carry it.
:::

## Tests to add

- [ ] **The migration**: a case in
  [`DatabaseDriverFactoryTest`](../../core/src/jvmTest/kotlin/de/andi1984/cadence/data/db/DatabaseDriverFactoryTest.kt)
  modelled on `the 4 to 5 migration adds tags without disturbing the columns before them`:
  build a version-5 file, open it, and check the row survives with the new column null.
- [ ] **The round trip through SQLite**: extend
  [`SqlDelightStoreTest`](../../core/src/jvmSharedTest/kotlin/de/andi1984/cadence/SqlDelightStoreTest.kt)'s
  `a task survives an insert and read back` with the new field set. This catches a column added
  to `toRow` but not to `toTask`, or not forwarded in `updateRow`.
- [ ] **The merge path**: make sure an edit arriving through `mergeRow` (a pull or an import)
  carries the field. [`SqlDelightSyncStoreTest`](../../core/src/jvmSharedTest/kotlin/de/andi1984/cadence/data/sync/SqlDelightSyncStoreTest.kt)
  is the place.
- [ ] **The backup file**: a round trip in
  [`BackupCodecTest`](../../core/src/jvmSharedTest/kotlin/de/andi1984/cadence/BackupCodecTest.kt),
  plus decoding a file *without* the key.
- [ ] **The wire**: extend `a task survives the round trip through the wire shape` in
  [`RemoteRecordsTest`](../../core/src/jvmSharedTest/kotlin/de/andi1984/cadence/data/sync/RemoteRecordsTest.kt).
- [ ] **The server**: `bash neon/tests/run.sh`. It applies your migration twice, which proves
  it's idempotent. Add a check if the column carries a rule (a default, a constraint).

## Verify

```bash
./gradlew :core:testDebugUnitTest :ui:testDebugUnitTest \
          :core:jvmTest :ui:jvmTest :app-desktop:test :ui:compileKotlinJvm
bash neon/tests/run.sh
```

Then check an upgrade by hand. Run the desktop app from `main` once, so its database is at version
5, switch to your branch and run it again. Your existing tasks should still be there, and the
database should now be at version 6:

```bash
sqlite3 ~/.local/share/primico/cadence.db 'PRAGMA user_version;'   # Linux path
```

```
6
```

## Related

- [Database reference](../reference/database.md): every table, column and query.
- [Backup format](../reference/backup-format.md) and [sync wire](../reference/sync-wire.md): the
  two published shapes.
- [Self-hosting sync](../self-hosting.md): applying Neon migrations to your own project.
- [ADR 0002](../adr/0002-supabase-sync.md) and [ADR 0005](../adr/0005-neon-sync.md): why the
  mirror has no foreign keys and why timestamps are milliseconds.
