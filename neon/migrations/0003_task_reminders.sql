-- Flexible task reminders (docs/adr/0006-task-reminders.md): one new column on `public.tasks`
-- holding the task's whole reminder list as a jsonb array of tagged objects, the same JSON shape
-- the backup file carries. A packed column on the task, not a table of its own — the same
-- reasoning as `tag_ids` (ADR 0004): a reminder belongs to exactly one task, and the recurrence
-- copy, the merge, the tombstones and last-writer-wins all come free with the row.
--
-- Rollout order matters and is deliberate: `SyncJson.encodeDefaults` is on, so every *new* client
-- always sends the `reminders` column and a push against a database without it would fail on an
-- unknown column. Apply this migration **before** shipping the client. An *old* client omits the
-- column, and a PostgREST upsert only updates the columns it mentions, so its push leaves the
-- server's value untouched — no code needed for that half.
--
-- `reminder_time` stays: new clients keep writing it (the time of the first same-day reminder),
-- so an older install still fires something for the row.

alter table if exists public.tasks
    add column if not exists reminders jsonb not null default '[]'
    check (jsonb_typeof(reminders) = 'array');

-- The table-level grant from 0001 already covers every column, and 0002's revoked default
-- privileges mean nothing new is exposed: this file grants nothing, by design.
