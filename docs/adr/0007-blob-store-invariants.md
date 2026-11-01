---
title: "ADR 0007 — Blob store invariants: cancellation unwinds, and reclaim is gated against filing"
sidebar:
  label: "0007 · Blob store"
  order: 7
---
**Status:** accepted · **Date:** 2026-11-01 · **Supersedes nothing.** Extends
[ADR 0001](0001-desktop-app-and-multi-device-sync.md) (local-first storage: SQLite is the source of
truth, the filesystem next to it is a cache the rows point at).

## Context

Attachments phases 0–2 put each file's bytes in a content-addressed blob store
(`BlobStore`, `<root>/<2 hex>/<sha256>`, addressed by its own SHA-256) and only the metadata in
SQLite (`attachmentRow`). The attachments table *is* the refcount — there is no stored counter —
and two different tasks can name the same hash. That splits one logical write across two stores no
single transaction can span: a SQL transaction can commit the row, but it cannot commit a
`renameTo`/`delete` on the filesystem. [#112](https://github.com/viberfasend/primico/issues/112)
found two defects that come exactly from that split, and this ADR records the two invariants that
fix them, because both are invisible from any single function's code.

## Decisions

### 1. Cancellation is not a failure — a cancelled copy unwinds the coroutine

`BlobStore.store()` is a plain blocking function, but its callers run inside
`withContext(ioDispatcher)` in `CadenceRepository.addFileAttachment`/`relocateAttachment`, and the
ViewModel's scope can be torn down mid-copy (screen navigated away). `store()` therefore re-throws
`CancellationException` before its generic `catch (t: Throwable)` — after deleting the staged temp
file — instead of wrapping it in `StoreResult.Failed`. This is the same rule every other suspend
boundary in `:core` already follows (`CadenceRepository`'s and `CadenceSyncEngine`'s
`catch (e: CancellationException) { throw e }` blocks): a `StoreResult.Failed` means "the copy
failed", and cancellation is the caller unwinding, which must reach the coroutine as a
cancellation or the UI reports a phantom error for a screen that no longer exists.

### 2. Filing a row and reclaiming a blob are serialized by one mutex, not by a transaction

`reclaim(hashes)` used to read `stillReferenced(hashes)` from SQL and then delete the complement
from disk with nothing spanning the two. Because blobs are content-addressed, a concurrent
`addFileAttachment` storing the *same bytes* lands on the *same hash*, and if its row insert fell
in that window the reclaim deleted the blob out from under the row that had just claimed it — the
attachment then rendered as permanently missing.

The fix is a single `Mutex` (`blobGate`) in `CadenceRepository` spanning both critical sections:

- **filing** — `addFileAttachment` and `relocateAttachment` hold it across `blobStore.store(...)`
  *and* the `insert`/`update` that names the hash;
- **reclaiming** — `reclaim` and `sweepOrphanBlobs` hold it across the reference read *and* the
  filesystem delete.

With both under one lock the two orders are both safe: either the reclaim's read sees the new row
and keeps the blob, or it deletes first and the add's `store` re-writes the bytes afterwards. The
mutex is not reentrant, so a caller already holding it (relocate, reclaiming the hash it stopped
naming) uses `reclaimLocked` rather than `reclaim`. All blob mutation goes through the repository —
verified: no other caller of `BlobStore.store`/`deleteAll`/`sweepOrphans` exists outside
`:core`'s repository and the tests.

## Consequences

- A blob copy of up to 25 MB holds the gate for its duration, so a reclaim triggered meanwhile
  waits for it. That is the correct order — the alternative is deleting a blob mid-copy — and the
  copy is already on `Dispatchers.IO`, so the UI thread is never blocked by it.
- `attachmentIndex`'s presence reads (`blobStore.present`) stay *outside* the gate on purpose:
  a stale presence bit renders for one emission at worst and every write re-emits the flow. Taking
  the gate per emission would stat the blob directory per distinct hash per frame while holding a
  lock the filing paths need.
- `sweepOrphanBlobs` (cold start, after a backup restore) takes the same gate, so a sweep can
  never delete a blob a concurrent add has stored but not yet filed.
- If a store succeeds and the row insert throws (including cancellation), the blob is orphaned on
  disk — unchanged behaviour, healed by the next `sweepOrphanBlobs`, which is the designed backstop
  for exactly a blob the reclaim step never reached.

## Alternatives rejected

- **Span the read and the delete in a SQL transaction (`storeTransaction.run`).** A transaction
  commits rows; it cannot commit a filesystem delete, so the window would still be open against
  any writer that does not take the write lock — and the test fakes model no locking at all, which
  is why the row deletes are explicit everywhere in the repository already.
- **Re-check references right before each delete, without locking the filing side.** The race is
  the *insert* landing between the check and the delete; re-checking inside reclaim alone does
  not move that window, because the insert is the other half of the race. The lock has to be
  shared by both sides, which is what decision 2 does.
- **A stored refcount counter on the blob.** It drifts the moment a process dies between the
  counter decrement and the delete (or vice versa); reading the table back (`stillReferenced`) is
  what keeps the attachments table the single source of the count.
