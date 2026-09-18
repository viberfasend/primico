---
title: Debug sync
description: Read what the app says, inspect the device's sync state, probe Neon Auth and the Data API with curl, reset a device's cursors, and recognise the default-privileges gotcha.
sidebar:
  order: 8
---

Sync failures are rarely mysterious once you know where each piece of state lives. This guide
goes outside-in: what the app shows, what the device has stored, what the server holds, and what
the server answers when you ask it yourself.

## Prerequisites

- A build **with** sync endpoints and a Neon project of your own. See
  [self-hosting sync](../self-hosting.md).
- `sqlite3` for reading a device's database, `curl` for the probes, and `psql` plus
  `CADENCE_NEON_DB_URL` (the direct connection string) for the server-side checks.

:::caution[Keep secrets out of issues and screenshots]
Everything below uses placeholders: `<endpoint>`, `<region>`, `$SESSION_COOKIE`, `$JWT`. The real
values are credentials. The session cookie signs you in until it's revoked, the JWT reads and
writes your rows, and the direct connection string **bypasses row-level security entirely**.
Never paste them into an issue, a log or a chat.
:::

## 1. Read what the app is telling you

The engine reports one of five states (`SyncStatus` in
[`CadenceSyncEngine.kt`](../../core/src/jvmShared/kotlin/de/andi1984/cadence/data/sync/CadenceSyncEngine.kt)),
and every failed round raises a snackbar with one of three reasons (`SyncFailure`):

| What you see | State | What it means | Look at |
|---|---|---|---|
| No sync indicator; Settings says "This build has no sync server configured" | `Unconfigured` | the build was made without both `CADENCE_NEON_*` variables | the build: both must be set in the environment of the Gradle run. One without the other counts as none, with a warning in the Gradle output |
| Sign-in: "That email and password do not match an account." | `SignInResult.WrongCredentials` | Neon Auth answered 400/401 | the account in the Neon console's **Auth** tab (the app has no sign-up) |
| Sign-in: "No connection…" | `SignInResult.Offline` | an `IOException` | the network, and the auth URL itself |
| Sign-in: "The server could not be reached…" | `SignInResult.Failed` | any other Neon Auth answer | probe 1 below |
| "Last sync failed: no connection." | `SyncFailure.OFFLINE` | an `IOException` during the round | the network. Nothing is lost; the next round catches up |
| "Last sync failed: the session expired. Sign in again." | `SyncFailure.SESSION_EXPIRED` | minting a JWT from the session was refused (4xx), or the Data API still said 401 after a fresh JWT | sign out and in; probe 2 |
| "Last sync failed: the server returned an error." | `SyncFailure.SERVER` | the Data API answered with a non-2xx other than 401 | probe 3, a schema mismatch, grants |

The header indicator and the snackbar live in
[`ui/components/SyncControls.kt`](../../ui/src/jvmShared/kotlin/de/andi1984/cadence/ui/components/SyncControls.kt).
Signed out, nothing is shown and no request is made.

Rounds run on app start, on every return to the foreground, two seconds after a local write, and
on the way out. While the app is in front there's also a **60-second poll** (on Android only while
in the foreground; on the desktop for the whole process). "My edit reached the other device a
minute later" is therefore normal. To force a round, pull to refresh on Android, press
`Ctrl`/`Cmd`+`R` on the desktop, or use Settings → Sync now.

## 2. Inspect the device's sync state

Everything sync remembers lives in **one row** of the app's own database, `syncStateRow`
([`SyncState.sq`](../../core/src/commonMain/sqldelight/de/andi1984/cadence/data/db/SyncState.sq)),
not in the settings file, because it has to stay consistent with the rows it describes:

| Column | Meaning |
|---|---|
| `session` | the serialised `NeonSession`: a short-lived Data API JWT plus the long-lived Better Auth session cookie, or `NULL` when signed out. **A credential.** |
| `taskCursor`, `projectCursor`, `sectionCursor`, `tagCursor` | the **server's** `server_updated_at` of the newest row pulled, per table (ISO text). The next pull re-reads from 5 s before this |
| `pushWatermark` | epoch millis: the newest local `updatedAt` pushed so far. Everything above it is pushed next round |
| `lastSyncedAt`, `lastSweepAt` | when the last round finished, and when tombstones were last collected (at most daily) |

On the desktop the database is `cadence.db` in the data directory: `~/.local/share/primico/` on
Linux, `~/Library/Application Support/Primico/` on macOS, `%APPDATA%\Primico\` on Windows. Read
it without printing the session:

```bash
sqlite3 -readonly ~/.local/share/primico/cadence.db "
  SELECT session IS NOT NULL                       AS signed_in,
         taskCursor, projectCursor, sectionCursor, tagCursor,
         datetime(pushWatermark / 1000, 'unixepoch') AS pushed_up_to,
         datetime(lastSyncedAt  / 1000, 'unixepoch') AS last_synced,
         datetime(lastSweepAt   / 1000, 'unixepoch') AS last_sweep
  FROM syncStateRow;"
```

And what's waiting to be pushed, tombstones included:

```bash
sqlite3 -readonly ~/.local/share/primico/cadence.db "
  SELECT count(*) FROM taskRow
  WHERE updatedAt > (SELECT pushWatermark FROM syncStateRow);"
```

A count that stays above zero across several successful-looking rounds means the push is
failing, or the server is dropping the rows as stale (see step 5).

On Android, only a **debug** build can be read this way:

```bash
adb exec-out run-as de.andi1984.cadence.debug cat databases/cadence.db > phone.db
adb exec-out run-as de.andi1984.cadence.debug cat databases/cadence.db-wal > phone.db-wal  # if it exists
sqlite3 -readonly phone.db "SELECT session IS NOT NULL, taskCursor, pushWatermark FROM syncStateRow;"
```

Never hand-edit `syncStateRow` while the app is running. To reset it, sign out (step 6).

## 3. Check what the server holds

`neon/db.sh` connects with the direct connection string and sees **every** account's rows:

```bash
CADENCE_NEON_DB_URL='postgresql://…' bash neon/db.sh status
```

It prints live rows, tombstones and collectable tombstones (older than 90 days) per table, plus
how the rows split across accounts. That split is how you tell your account apart from a
forgotten test login. `sweep` is a dry run until you pass `--yes`, and it refuses to go below 90
days without `--force`.

## 4. Probe the endpoints with curl

Set the two URLs your build was compiled with:

```bash
AUTH='https://<endpoint>.neonauth.<region>.aws.neon.tech/neondb/auth'
DATA='https://<endpoint>.apirest.<region>.aws.neon.tech/neondb/rest/v1'
```

**Probe 1: sign in.** This is the request the app makes, `POST /sign-in/email`. The session comes
back **as a cookie**, not in the body:

```bash
curl -si -X POST "$AUTH/sign-in/email" \
     -H 'Content-Type: application/json' \
     -d '{"email":"you@example.org","password":"…"}' | grep -i '^set-cookie'
```

Look for a `Set-Cookie` whose name ends in `session_token` (over HTTPS,
`__Secure-neon-auth.session_token=…`). Keep the `name=value` part, up to the first `;`:

```bash
SESSION_COOKIE='__Secure-neon-auth.session_token=…'
```

A 401 here is a wrong password or a missing account. A 2xx with no such cookie is what the app
reports as "sign-in answered without a session cookie".

**Probe 2: mint a JWT.** `GET /token` trades the cookie for the short-lived token the Data API
wants:

```bash
curl -s "$AUTH/token" -H "Cookie: $SESSION_COOKIE"
```

```
{"token":"eyJhbGciOi…"}
```

A 4xx here is exactly the app's `SESSION_EXPIRED`: the session was revoked or has expired, and
only signing in again fixes it.

```bash
JWT='eyJhbGciOi…'
```

**Probe 3: read your rows** through the same endpoint a pull uses, newest change first by the
server's clock (a pull reads oldest first, from its cursor):

```bash
curl -s "$DATA/tasks?select=id,title,updated_at,deleted_at,server_updated_at&order=server_updated_at.desc&limit=5" \
     -H "Authorization: Bearer $JWT"
```

You should see only your own account's rows. Row-level security scopes every request to
`auth.user_id()` from the JWT. Compare the newest `server_updated_at` with the device's
`taskCursor`: the device should be at or just behind it.

**Probe 4: signed out, nothing is readable.** The same request with no `Authorization` header
must be refused, not answered with an empty list or rows:

```bash
curl -si "$DATA/tasks?select=id&limit=1" | head -n 1
```

## 5. Recognise the usual server-side causes

**A stale write is dropped silently.** The server's trigger skips any row whose `updated_at` is
not *strictly newer* than the stored one. It does that without failing the batch, and the client
learns about it on the next pull. That's how the push can safely resend everything above the
watermark. It also means **a device whose clock runs behind loses conflicts**: its edits carry
older timestamps and are dropped. If one device's edits "never arrive", compare its clock with
the others'.

**A column the mirror doesn't have.** A client built with a new field pushes a key the `tasks`
table lacks. The Data API answers with an error on every push, and every round reports "the
server returned an error". Apply the pending migration with `bash neon/migrate.sh`. See
[add a column](add-a-column.md) for the order that avoids this.

**The default-privileges gotcha.** Enabling the Data API on a Neon branch leaves a
default-privilege entry behind (`authenticated=arwd`), so **every table the owner creates in
`public` afterwards is readable and writable by any signed-in account**. That already happened to
`public.schema_migrations`. `0002_lock_bookkeeping.sql` repairs it and revokes the default.
Symptoms and checks:

- Neon's advisor flags a table as exposed, or this probe returns rows instead of a permission
  error:

  ```bash
  curl -si "$DATA/schema_migrations" -H "Authorization: Bearer $JWT" | head -n 1
  ```

- The default is still in place:

  ```bash
  psql "$CADENCE_NEON_DB_URL" -c '\ddp'
  ```

  A row granting `authenticated=arwd` on tables in `public` means `0002` hasn't been applied. Run
  `CADENCE_NEON_DB_URL='postgresql://…' bash neon/migrate.sh`.

The flip side, once `0002` is in: a **new** table is *not* reachable through the Data API until a
migration grants it. If you add one and the app's requests to it are refused, that's the rule
working. Grant it explicitly, with row-level security and an owner policy, the way
`0001_cadence_sync.sql` does. The full story is in [`neon/CLAUDE.md`](../../neon/CLAUDE.md), and
`bash neon/tests/run.sh` checks the lock-down.

## 6. Reset a device: sign out, sign in

Signing out (Settings → Sync → Sign out) revokes the session on the server (best effort, so it
works offline too), and clears the session, **every cursor and the push watermark**. It deletes
**no** tasks: the local database is the source of truth.

Signing back in starts from zero. The first round pulls every row the account has and pushes every
row the device has. That's harmless, because the merge keeps the newer version of each row on both
sides and the server drops the pushes that aren't newer. Reach for it when cursors look wrong,
after restoring the device's data directory from a backup, or when a session has expired.

A stored session that no longer decodes (the pre-Neon Supabase session on an old install, for
example) counts as signed out, and the same one re-sign-in fixes it.

## Verify

- `syncStateRow` shows `signed_in = 1`, recent `last_synced`, and the pending-push count drops to
  zero after a round.
- An edit on one device shows up on the other within about a minute.
- `neon/db.sh status` shows the rows under exactly one account per person.

## Related

- [Sync](../concepts/sync.md): the round, the cursors and why there's no `dirty` column.
- [Sync wire reference](../reference/sync-wire.md): every column and the requests.
- [Self-hosting sync](../self-hosting.md): setting up the project, the migrations and the accounts.
- [ADR 0002](../adr/0002-supabase-sync.md) (the protocol) and [ADR 0005](../adr/0005-neon-sync.md)
  (the Neon transport).
