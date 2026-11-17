package de.andi1984.cadence.domain.model

import java.util.UUID

/**
 * The tags the app itself looks for, by **fixed id** — the one place an id is not minted
 * (ADR 0001, decision 4): a feature that finds its tasks by id cannot survive the tag being
 * renamed ("Draußen" is still `@outdoor`'s tag) or recreated with a fresh id on another device,
 * so the id is derived once from a name and every device computes the same one
 * (`UUID.nameUUIDFromBytes`, the same function recurrence successors use).
 *
 * The repository seeds these once per install ([de.andi1984.cadence.data.CadenceRepository.seedWellKnownTags])
 * if no row with the id exists — tombstone included, so deleting a well-known tag is respected
 * and never resurrected. Because the id is fixed, everything else about the tag stays ordinary:
 * the name is renameable and localisable, `@outdoor` in quick-add reaches it through
 * [matchingHandle], and sync carries it like any other row.
 */
object WellKnownTags {

    /**
     * The `@outdoor` tag — the label the Today screen's outdoor section filters on. Name-based
     * (v3) UUID of `"de.andi1984.cadence.well-known-tag:outdoor"`:
     * `0931c194-5a78-3251-9186-afbba2827ee5`.
     */
    const val OUTDOOR: String = "0931c194-5a78-3251-9186-afbba2827ee5"

    /** The name it is seeded with — renameable afterwards, the id is what matters. */
    const val OUTDOOR_NAME: String = "outdoor"

    /** Sun-amber, distinct from the default tag slate. */
    const val OUTDOOR_COLOR: String = "#E8A33D"

    /** The derivation [OUTDOOR] is pinned to in tests. */
    fun outdoorIdDerivation(): String =
        UUID.nameUUIDFromBytes("de.andi1984.cadence.well-known-tag:outdoor".toByteArray()).toString()
}
