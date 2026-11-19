package de.andi1984.cadence

import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.model.WellKnownTags
import de.andi1984.cadence.domain.parse.QuickAddParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The well-known `@outdoor` tag: seeded once by the repository, found by its fixed id however it
 * is renamed, and never resurrected after a delete. Real stores over an in-memory database, the
 * [TestStores] recipe — the seeding rules are the store's tombstone contract as much as the
 * repository's.
 */
class WellKnownTagsTest {

    private val fixedNow: Instant = Instant.parse("2026-11-17T12:00:00Z")
    private val clock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private val stores = TestStores()
    private val tagStore = stores.tagStore
    private val repository = stores.repository(clock = clock)

    @Test
    fun `the fixed id is the name-based UUID it is documented to be`() {
        assertEquals(WellKnownTags.outdoorIdDerivation(), WellKnownTags.OUTDOOR)
    }

    @Test
    fun `seeding inserts the outdoor tag once`() = runTest {
        repository.seedWellKnownTags()
        repository.seedWellKnownTags()

        val tags = repository.tags.first()
        val outdoor = tags.singleOrNull { it.id == WellKnownTags.OUTDOOR }
        assertNotNull(outdoor)
        assertEquals(WellKnownTags.OUTDOOR_NAME, outdoor?.name)
        assertEquals(WellKnownTags.OUTDOOR_COLOR, outdoor?.colorHex)
        assertEquals(fixedNow, outdoor?.updatedAt)
        assertEquals(1, tags.size)
    }

    @Test
    fun `a deleted well-known tag is not resurrected by the next seeding`() = runTest {
        repository.seedWellKnownTags()
        repository.deleteTag(WellKnownTags.OUTDOOR)
        repository.seedWellKnownTags()

        // The live flow hides the tombstone, and the row behind it is still tombstoned —
        // seeding saw it there and left it alone.
        assertTrue(repository.tags.first().none { it.id == WellKnownTags.OUTDOOR })
        assertNotNull(tagStore.byIdIncludingDeleted(WellKnownTags.OUTDOOR)?.deletedAt)
    }

    @Test
    fun `a renamed well-known tag keeps its id, and seeding leaves the rename alone`() = runTest {
        repository.seedWellKnownTags()
        val seeded = tagStore.byIdIncludingDeleted(WellKnownTags.OUTDOOR)!!
        repository.upsertTag(seeded.copy(name = "Draußen"))

        repository.seedWellKnownTags()

        val tags = repository.tags.first()
        assertEquals(1, tags.size)
        assertEquals(WellKnownTags.OUTDOOR, tags.single().id)
        assertEquals("Draußen", tags.single().name)
    }

    @Test
    fun `quick-add @outdoor matches the seeded tag, and @draußen matches it after a rename`() = runTest {
        repository.seedWellKnownTags()
        val seeded = repository.tags.first().single { it.id == WellKnownTags.OUTDOOR }

        val before = QuickAddParser.parse("Mow the lawn @outdoor", tags = listOf(seeded))
        assertEquals(listOf(WellKnownTags.OUTDOOR), before.tagIds)
        assertEquals("Mow the lawn", before.title)
        assertTrue(before.newTagNames.isEmpty())

        val renamed = seeded.copy(name = "Draußen")
        val after = QuickAddParser.parse("Mow the lawn @draußen", tags = listOf(renamed))
        assertEquals(listOf(WellKnownTags.OUTDOOR), after.tagIds)
        assertTrue(after.newTagNames.isEmpty())
    }

    @Test
    fun `seeding an existing foreign tag list appends the outdoor tag at the end`() = runTest {
        tagStore.insert(Tag(id = "user-tag", name = "errand", sortOrder = 0, updatedAt = fixedNow))

        repository.seedWellKnownTags()

        val tags = repository.tags.first()
        assertEquals(listOf("user-tag", WellKnownTags.OUTDOOR), tags.map { it.id })
    }

    @Test
    fun `seeding ticks localWrites exactly once per actual insert`() = runTest {
        val ticks = mutableListOf<Unit>()
        val job = backgroundScope.launch { repository.localWrites.collect { ticks += it } }
        runCurrent()

        repository.seedWellKnownTags()
        repository.seedWellKnownTags()
        runCurrent()
        job.cancel()

        assertEquals(1, ticks.size)
    }
}
