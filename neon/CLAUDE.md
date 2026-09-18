# The server half of sync

Loaded when working under `neon/`. The client half — how the endpoints reach a build, and how
`CadenceSyncEngine` behaves without them — stays in the root `CLAUDE.md`.

The server half of sync is `neon/migrations/*.sql` ([ADR 0005](docs/adr/0005-neon-sync.md)) —
four tables, forced RLS over `auth.user_id()`, and the stale-write trigger. It is committed
rather than left in the console because it is the one part of the system the Kotlin suite cannot
reach. There is deliberately no `pg_cron` job: Neon's compute scales to zero and cron only fires
while it is awake, so the tombstone sweep is client-driven — after a successful push, at most
daily, the engine `DELETE`s its own tombstones past the 90-day horizon through the Data API (by
`server_updated_at`, the server's clock, since `deleted_at` is a device's).

**Apply it with `bash neon/migrate.sh`**, which needs `CADENCE_NEON_DB_URL` (the direct Postgres
connection string from the Neon console) and `psql`. Bookkeeping is one table,
`public.schema_migrations`, keyed by *filename* — a file is applied inside one transaction with
the row that records it, and re-running the script skips what is recorded. The files are
idempotent besides, so pasting one into the console's SQL editor is also safe. **Enable the Data
API for the branch before migrating**: it provisions `auth.user_id()` (pg_session_jwt) and the
`authenticated`/`anonymous` roles the migration leans on, and the baseline refuses with a clear
message when they are missing.

**Enabling the Data API grants every future table away, and that has already bitten once.** It
leaves a default-privilege entry behind — `neondb_owner | public | r | authenticated=arwd` — so a
table the owner creates in `public` from then on is readable *and writable* by any signed-in
account the moment it exists, with nobody granting anything. `schema_migrations`, created by
`migrate.sh` rather than by a migration, picked that up and was served over HTTP until Neon's own
advisor pointed at it; a forged row in it would have made the next `migrate.sh` skip a migration.
`0002_lock_bookkeeping.sql` revokes it, puts RLS on the table with no policy at all, and revokes
the default itself, so **a new table is not in the Data API until a migration grants it** — which
is how `0001` already works. The rule to keep: state grants outright, and never assume a table is
private because nothing granted it.

Maintenance from a laptop is `bash neon/db.sh` (same `CADENCE_NEON_DB_URL`, same never-stored
rule): `status` shows live rows, tombstones, how many are collectable and how the rows split
across accounts; `sweep` prints what it would collect and changes nothing until `--yes`;
`vacuum` hands the space back. It exists *beside* the client's own sweep rather than instead of
it — the client collects only the account it is signed in as, so rows belonging to an account
that no longer signs in (an old test login) are unreachable from any device. The connection it
uses carries BYPASSRLS on Neon, which is the point and also the danger, hence the dry-run
default, the tombstone-only predicate and the 90-day floor (`--force` to go below, with the
reason printed: a device offline longer than the horizon puts back what the others deleted).

No Gradle task will tell you any of it is wrong, so it has a test of its own:
`bash neon/tests/run.sh` applies every migration to a throwaway `postgres:16` container (through
`migrate.sh` once, then directly a second time for idempotence) with `auth.user_id()`, the Data
API roles **and Neon's default privileges** stubbed — that last one is not decoration: without it
the harness passed while the live project was handing `schema_migrations` to every signed-in
account. It checks the trigger semantics, the RLS isolation, the client-shaped sweep statement,
the bookkeeping lock-down and `db.sh` itself. Needs docker and nothing else. Run it after editing
anything under `neon/`.
