---
title: Backup file format
description: The published cadence.backup JSON contract — every object and field, version handling, how an import merges, and how broken links are repaired.
sidebar:
  order: 4
---

A backup is one UTF-8 JSON document. It is a **published contract**: other programs read and
write it (a future web client, and [`tools/todoist_import.py`](../../tools/todoist_import.py)
writes it today), so it is deliberately not the database's storage shape. Dates are ISO-8601
strings, tag ids are an array, recurrence is a nested object. The codec is
[`BackupCodec.kt`](../../core/src/jvmShared/kotlin/de/andi1984/cadence/domain/backup/BackupCodec.kt);
the file I/O is `BackupIo` on Android (Storage Access Framework) and `DesktopBackupIo` on the
desktop.

## Document

| Key | JSON type | Written | Meaning |
|---|---|---|---|
| `format` | string | always `"cadence.backup"` | Identifies the file. Any other value is refused. |
| `version` | number | always `2` (`BackupCodec.VERSION`) | Format version. A higher number is refused. |
| `exportedAt` | string \| null | ISO instant of the export | The file's own timestamp. Unreadable reads as absent. |
| `projects` | array of [Project](#project) | every live project | |
| `sections` | array of [Section](#section) | every live section | Added without a version bump. |
| `tags` | array of [Tag](#tag) | every live tag | Added without a version bump. |
| `tasks` | array of [Task](#task) | every live task | |
| `settings` | [Settings](#settings) \| null | the exporting device's settings | |

What an export does **not** contain: tombstones (the snapshot reads only live rows), attachments
(neither rows nor bytes), sync state, the reminder settings, and the desktop's window layout.

## Version handling

| Situation | Result |
|---|---|
| Not parseable as this document (not JSON, or not an object of this shape) | refused — `NOT_JSON` |
| `format` is present and is not `"cadence.backup"` | refused — `NOT_A_BACKUP` |
| `version` greater than `2` | refused — `NEWER_VERSION` |
| `format` or `version` missing | read as `"cadence.backup"` / `2` (the declared defaults) |
| Unknown keys, anywhere | ignored (`ignoreUnknownKeys`) |
| File larger than 32 MiB | refused before parsing — `FILE_TOO_LARGE` (`MAX_FILE_BYTES` in both shells) |

The rule for changing the format: an **additive, optional** field leaves `VERSION` alone, because
a bump would make older installs refuse the whole file over one key they could ignore
(`sections`, `tags`, `parentId`, `spawnedFromId` and `sectionId` were all added that way). A
changed meaning bumps `VERSION` and keeps the old shape readable.

## Objects

Every field is written on export (`encodeDefaults = true`), `null`s included. The **Read default**
column is what a missing key decodes to. Timestamps are ISO instants such as
`2026-08-05T07:12:00Z`; a timestamp that does not parse reads as absent, and a missing or unreadable
`updatedAt`/`createdAt` reads as `1970-01-01T00:00:00Z`.

### Project

| Key | JSON type | Read default | Notes |
|---|---|---|---|
| `id` | string | `""` | A project with a blank id is dropped. |
| `name` | string | `""` | |
| `colorHex` | string | `"#006A60"` | |
| `parentId` | string \| null | `null` | Blank reads as `null`. Not checked against the file. |
| `sortOrder` | number | `0` | |
| `updatedAt` | string \| null | `null` → epoch | |
| `deletedAt` | string \| null | `null` | Non-null is a tombstone. |

### Section

| Key | JSON type | Read default | Notes |
|---|---|---|---|
| `id` | string | `""` | Blank → dropped. |
| `projectId` | string | `""` | Must name a project in the file, or the section is dropped. |
| `name` | string | `""` | Blank → dropped. |
| `sortOrder` | number | `0` | |
| `updatedAt` | string \| null | `null` → epoch | |
| `deletedAt` | string \| null | `null` | |

### Tag

| Key | JSON type | Read default | Notes |
|---|---|---|---|
| `id` | string | `""` | Blank → dropped. |
| `name` | string | `""` | Blank → dropped. |
| `colorHex` | string | `"#3E6373"` | |
| `sortOrder` | number | `0` | |
| `updatedAt` | string \| null | `null` → epoch | |
| `deletedAt` | string \| null | `null` | |

### Task

| Key | JSON type | Read default | Notes |
|---|---|---|---|
| `id` | string | `""` | Blank → a fresh UUIDv7 is minted on read. |
| `title` | string | `""` | Blank → the task is dropped. |
| `notes` | string \| null | `null` | Blank reads as `null`. |
| `priority` | number | `3` | 1–4 (`Priority.level`); anything else reads as 3. |
| `projectId` | string \| null | `null` | `null` = Inbox. |
| `sectionId` | string \| null | `null` | |
| `tagIds` | array of string | `[]` | A real array, not the packed column. Blanks and duplicates dropped. |
| `parentId` | string \| null | `null` | The task this one is a subtask of. |
| `spawnedFromId` | string \| null | `null` | The recurring occurrence this row replaces. |
| `dueDate` | string \| null | `null` | ISO local date, `2026-08-05`. |
| `dueTime` | string \| null | `null` | ISO local time, `09:30`. |
| `reminderTime` | string \| null | `null` | ISO local time. |
| `completedAt` | string \| null | `null` | ISO instant; `null` while open. |
| `createdAt` | string \| null | `null` → epoch | ISO instant. |
| `sortOrder` | number | `0` | |
| `recurrence` | [Recurrence](#recurrence) \| null | `null` | |
| `updatedAt` | string \| null | `null` → epoch | |
| `deletedAt` | string \| null | `null` | |

### Recurrence

Enum values are written as their Kotlin names. Decoding goes through `RecurrenceRule.fromNames`:
an unknown name falls back to the default below, an unknown weekday is dropped, `interval` is
clamped to at least 1. A `keepMissed` key written by older versions is ignored.

| Key | JSON type | Read default | Values |
|---|---|---|---|
| `mode` | string | `"SCHEDULE"` | `SCHEDULE`, `AFTER_COMPLETION` |
| `interval` | number | `1` | ≥ 1 |
| `unit` | string | `"WEEK"` | `DAY`, `WEEK`, `MONTH`, `YEAR` |
| `daysOfWeek` | array of string | `[]` | `MONDAY` … `SUNDAY`, written Monday first |
| `monthlyMode` | string | `"DAY_OF_MONTH"` | `DAY_OF_MONTH`, `LAST_DAY`, `LAST_WEEKDAY`, `NTH_WEEKDAY` |
| `dayOfMonth` | number \| null | `null` | 1–31 |
| `nthWeek` | number \| null | `null` | 1–5, 5 = last |
| `nthDayOfWeek` | string \| null | `null` | `MONDAY` … `SUNDAY` |

What each value means: [Data model](data-model.md#recurrencerule).

### Settings

| Key | JSON type | Written | Applied on import |
|---|---|---|---|
| `theme` | string \| null | `SYSTEM`, `LIGHT` or `DARK` | yes, when it names a `ThemeChoice` |
| `density` | string \| null | `COMFORTABLE` or `COMPACT` | yes, when it names a `Density` |
| `sortMode` | string \| null | `IMPORTANCE`, `DATE` or `MANUAL` | yes, when it names a `SortMode` |
| `showCompleted` | boolean \| null | the current value | yes, when present |
| `locale` | string \| null | always `null` | no |

## Example

```json
{
    "format": "cadence.backup",
    "version": 2,
    "exportedAt": "2026-09-19T08:15:30.123Z",
    "projects": [
        {
            "id": "01996a3e-1f2b-7c3d-8e4f-5a6b7c8d9e0f",
            "name": "Home",
            "colorHex": "#A1560A",
            "parentId": null,
            "sortOrder": 0,
            "updatedAt": "2026-09-01T10:00:00Z",
            "deletedAt": null
        }
    ],
    "sections": [
        {
            "id": "01996a3e-2a3b-7c4d-9e5f-6a7b8c9d0e1f",
            "projectId": "01996a3e-1f2b-7c3d-8e4f-5a6b7c8d9e0f",
            "name": "Kitchen",
            "sortOrder": 0,
            "updatedAt": "2026-09-01T10:01:00Z",
            "deletedAt": null
        }
    ],
    "tags": [
        {
            "id": "01996a3e-3b4c-7d5e-af60-7b8c9d0e1f2a",
            "name": "errand",
            "colorHex": "#3E6373",
            "sortOrder": 0,
            "updatedAt": "2026-09-02T09:00:00Z",
            "deletedAt": null
        }
    ],
    "tasks": [
        {
            "id": "01996a3e-4c5d-7e6f-b071-8c9d0e1f2a3b",
            "title": "Take out recycling",
            "notes": null,
            "priority": 2,
            "projectId": "01996a3e-1f2b-7c3d-8e4f-5a6b7c8d9e0f",
            "sectionId": "01996a3e-2a3b-7c4d-9e5f-6a7b8c9d0e1f",
            "tagIds": ["01996a3e-3b4c-7d5e-af60-7b8c9d0e1f2a"],
            "parentId": null,
            "spawnedFromId": null,
            "dueDate": "2026-09-24",
            "dueTime": "07:30",
            "reminderTime": null,
            "completedAt": null,
            "createdAt": "2026-09-03T18:20:00Z",
            "sortOrder": 0,
            "recurrence": {
                "mode": "SCHEDULE",
                "interval": 2,
                "unit": "WEEK",
                "daysOfWeek": ["THURSDAY"],
                "monthlyMode": "DAY_OF_MONTH",
                "dayOfMonth": null,
                "nthWeek": null,
                "nthDayOfWeek": null
            },
            "updatedAt": "2026-09-10T07:31:12.004Z",
            "deletedAt": null
        },
        {
            "id": "01996a3e-5d6e-7f70-8182-9d0e1f2a3b4c",
            "title": "Rinse the bottles",
            "notes": null,
            "priority": 3,
            "projectId": "01996a3e-1f2b-7c3d-8e4f-5a6b7c8d9e0f",
            "sectionId": null,
            "tagIds": [],
            "parentId": "01996a3e-4c5d-7e6f-b071-8c9d0e1f2a3b",
            "spawnedFromId": null,
            "dueDate": null,
            "dueTime": null,
            "reminderTime": null,
            "completedAt": null,
            "createdAt": "2026-09-03T18:21:00Z",
            "sortOrder": 0,
            "recurrence": null,
            "updatedAt": "2026-09-03T18:21:00Z",
            "deletedAt": null
        }
    ],
    "settings": {
        "theme": "SYSTEM",
        "density": "COMFORTABLE",
        "sortMode": "IMPORTANCE",
        "showCompleted": true,
        "locale": null
    }
}
```

## Link repair on read

`BackupCodec.decode` repairs links rather than trusting them, in this order:

| # | Rule |
|---|---|
| 1 | Projects with a blank `id` are dropped. |
| 2 | Sections with a blank `id` or `name`, or whose `projectId` is not a project in the file, are dropped. |
| 3 | Tags with a blank `id` or `name` are dropped. |
| 4 | Tasks with a blank `title` are dropped; a blank task `id` is replaced by a fresh UUIDv7. |
| 5 | A task whose `projectId` is not in the file moves to the Inbox (`projectId = null`). |
| 6 | A `sectionId` is kept only when that section is in the file **and** belongs to the task's (possibly just reset) project; otherwise the task lands in the ungrouped band. |
| 7 | `tagIds` naming a tag the file does not define are dropped. A tag the file defines as a tombstone is kept. |
| 8 | `spawnedFromId` is dropped unless it names a different task in the file. |
| 9 | `parentId` is normalised: a missing parent sets the task free, a chain deeper than one level is flattened onto its root, and a task that is its own ancestor (directly or around a cycle) is set free. |

Tombstones in the file are kept: they are versions of a record like any other.

## Merge on import

Importing **merges** into the database; it never replaces it
([`BackupStore.mergeAll`](../../core/src/jvmShared/kotlin/de/andi1984/cadence/data/Stores.kt),
implemented by `SqlDelightBackupStore`). All of it runs in one transaction.

| Incoming record | Stored row | Result |
|---|---|---|
| any | none with that id | inserted as-is, tombstone or not |
| `updatedAt` greater than stored | exists | incoming wins the whole record (no field-level merge) |
| `updatedAt` equal or smaller | exists | stored row kept |
| **live** (no `deletedAt`) | **tombstoned** | **revived**: the file's fields, `deletedAt = null`, `updatedAt` = the import's own clock |
| tombstone | live, older | the incoming tombstone wins, like any newer version |

Consequences worth knowing:

- Ids come from the file, so task→project links need no remapping, and importing the same file
  twice changes nothing.
- A version-1 file, or any record without `updatedAt`, decodes to the epoch and loses every
  conflict against a row the device already has.
- The revival is importing's exception only; sync never revives. It is stamped with the import's
  clock so it outlives the tombstone the server still holds. See
  [Undo and deletes](../concepts/undo-and-deletes.md).
- Attachment rows are never touched by the merge. Afterwards the repository sweeps blobs no row
  names any more.
- Settings are applied after the data, by the shell's `BackupIo`.

## Related

- [Data model](data-model.md)
- [Sync wire format](sync-wire.md) — the other published shape, deliberately a separate set of DTOs
- [ADR 0001](../adr/0001-desktop-app-and-multi-device-sync.md) — import as a merge
