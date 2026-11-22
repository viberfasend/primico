package de.andi1984.cadence.desktop

import de.andi1984.cadence.data.sync.CadenceSyncEngine
import de.andi1984.cadence.data.sync.NeonConfig
import de.andi1984.cadence.data.sync.SignInResult
import de.andi1984.cadence.data.sync.SyncState
import de.andi1984.cadence.data.sync.SyncStatus
import de.andi1984.cadence.data.sync.SyncStore
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.model.Task
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * The exit sequence (`shutdown` in `AppContainer.kt`) against a fake sync client, pinning #216:
 * the last round on the way out must actually run to completion — not be launched on the
 * application scope that the teardown cancels a moment later, which killed it at its first
 * suspension point before it could send anything.
 *
 * The fakes are `:core`'s `SyncFakes.kt` in shape: a hand-written [SyncStore] whose every write
 * the test can see, and Ktor's own [MockEngine] answering every request with what the test says.
 * The engine's scope is a real one that the test's `closeCore` cancels, exactly as
 * `CadenceCore.close()` does, so a launched round would die mid-flight the way it did in the
 * bug.
 */
class AppContainerShutdownTest {

    /** The application scope in the shape `CadenceCore` hands the engine; cancelled by the
     *  test's `closeCore`, like the real teardown. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `shutdown lets one sync round finish before the settings flush and the core close`() {
        val store = FakeSyncStore()
        // A change made just before quitting: above the watermark, so the last round has to
        // push it for it to reach the phone at all.
        val task = Task(
            id = "t1",
            title = "made just before quitting",
            updatedAt = Instant.parse("2026-11-22T10:00:00Z"),
        )
        store.tasks = listOf(task)
        val http = RecordingHttp { request ->
            handleAuth(request) ?: run {
                // A round still in flight when the quit path moves on — the shape the bug hid
                // behind: a launched round is cancelled right here, mid-request.
                delay(100)
                when (request.method) {
                    HttpMethod.Post -> respondJson("")
                    else -> respondJson("[]")
                }
            }
        }
        val engine = engine(store, http)
        runBlocking { assertEquals(SignInResult.Ok, engine.signIn("me@example.org", "pw")) }
        store.stateValue = store.stateValue.copy(lastSweepAt = Instant.now()) // sweep not due
        http.requests.clear()

        val events = mutableListOf<String>()
        var postsAtFlush = -1
        shutdown(
            syncEngine = engine,
            flushSettings = {
                postsAtFlush = http.requests.count { it.method == HttpMethod.Post }
                events += "flush"
            },
            closeCore = {
                events += "close"
                scope.cancel() // what CadenceCore.close() does to the application scope
            },
        )

        // The round ran to completion on the quit path itself: the push went out, the
        // watermark moved to what was sent, the status settled — all before the flush.
        val posts = http.requests.filter { it.method == HttpMethod.Post }
        assertEquals(1, posts.size)
        assertEquals("/tasks", posts.single().url.encodedPath)
        assertTrue((posts.single().body as TextContent).text.contains("\"id\":\"t1\""))
        assertEquals(1, postsAtFlush)
        assertEquals(task.updatedAt, store.stateValue.pushWatermark)
        assertTrue(engine.status.value is SyncStatus.Idle)
        assertEquals(listOf("flush", "close"), events)
    }

    @Test
    fun `a signed-out quit makes no request and still flushes and closes`() {
        val store = FakeSyncStore() // no session stored
        val http = RecordingHttp { request ->
            throw AssertionError("unexpected request: ${request.method.value} ${request.url}")
        }
        val engine = engine(store, http)
        val events = mutableListOf<String>()

        shutdown(
            syncEngine = engine,
            flushSettings = { events += "flush" },
            closeCore = { events += "close"; scope.cancel() },
        )

        assertEquals(0, http.requests.size)
        assertEquals(listOf("flush", "close"), events)
    }

    @Test
    fun `a round that outlives the bound is cut off and the quit still proceeds`() {
        val store = FakeSyncStore()
        val http = RecordingHttp { request ->
            handleAuth(request) ?: run {
                delay(10_000) // a server that never answers within the bound
                respondJson("[]")
            }
        }
        val engine = engine(store, http)
        runBlocking { assertEquals(SignInResult.Ok, engine.signIn("me@example.org", "pw")) }
        store.stateValue = store.stateValue.copy(lastSweepAt = Instant.now())
        http.requests.clear()
        val events = mutableListOf<String>()
        val startedAt = System.nanoTime()

        shutdown(
            syncEngine = engine,
            flushSettings = { events += "flush" },
            closeCore = { events += "close"; scope.cancel() },
            lastSyncTimeout = Duration.ofMillis(100),
        )

        // The bound held: quit did not wait for the server, and the flush and close still ran.
        assertTrue(Duration.ofNanos(System.nanoTime() - startedAt) < Duration.ofSeconds(5))
        assertEquals(listOf("flush", "close"), events)
        // The round was cut off, not completed: it never got past the pull, nothing was
        // pushed, no watermark moved.
        assertTrue(http.requests.none { it.method == HttpMethod.Post })
        assertEquals(Instant.EPOCH, store.stateValue.pushWatermark)
    }

    // ── Fixtures ───────────────────────────────────────────────────────────────────

    private fun engine(store: FakeSyncStore, http: RecordingHttp) = CadenceSyncEngine(
        store = store,
        scope = scope,
        httpClient = HttpClient(http.engine),
        config = NeonConfig("https://data.example", "https://auth.example"),
    )

    /** The two auth calls a sign-in makes, answered the way Neon Auth answers them — the only
     *  requests here that are not part of a round. */
    private fun MockRequestHandleScope.handleAuth(request: HttpRequestData): HttpResponseData? =
        when (request.url.encodedPath) {
            "/sign-in/email" -> respond(
                content = """{"user":{"id":"u1","email":"me@example.org"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    HttpHeaders.SetCookie to listOf(
                        "__Secure-neon-auth.session_token=session-1.sig%3D; Path=/; HttpOnly",
                    ),
                ),
            )
            "/token" -> respondJson("""{"token":"${jwt(FAR_FUTURE)}"}""")
            else -> null
        }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpResponseData = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    /** Records every request and answers with whatever the test's handler says. */
    private class RecordingHttp(
        private val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) {
        val requests: MutableList<HttpRequestData> =
            java.util.Collections.synchronizedList(mutableListOf())
        val engine = MockEngine { request ->
            requests += request
            handler(request)
        }
    }

    /** The store in `:core`'s `SyncFakes.kt`'s shape: plain state, every write visible to the
     *  test, nothing stubbed by name. */
    private class FakeSyncStore : SyncStore {

        var stateValue = SyncState()
        var tasks: List<Task> = emptyList()

        override suspend fun state(): SyncState = stateValue

        override suspend fun setSession(session: String?) {
            stateValue = stateValue.copy(session = session)
        }

        override suspend fun tasksChangedSince(since: Instant): List<Task> =
            tasks.filter { it.updatedAt.isAfter(since) }

        override suspend fun projectsChangedSince(since: Instant): List<Project> = emptyList()

        override suspend fun sectionsChangedSince(since: Instant): List<Section> = emptyList()

        override suspend fun tagsChangedSince(since: Instant): List<Tag> = emptyList()

        override suspend fun mergeAndAdvance(
            projects: List<Project>,
            sections: List<Section>,
            tags: List<Tag>,
            tasks: List<Task>,
            taskCursor: String?,
            projectCursor: String?,
            sectionCursor: String?,
            tagCursor: String?,
        ) {
            stateValue = stateValue.copy(
                taskCursor = taskCursor ?: stateValue.taskCursor,
                projectCursor = projectCursor ?: stateValue.projectCursor,
                sectionCursor = sectionCursor ?: stateValue.sectionCursor,
                tagCursor = tagCursor ?: stateValue.tagCursor,
            )
        }

        override suspend fun setPushWatermark(at: Instant) {
            stateValue = stateValue.copy(pushWatermark = at)
        }

        override suspend fun setLastSyncedAt(at: Instant) {
            stateValue = stateValue.copy(lastSyncedAt = at)
        }

        override suspend fun collectTombstones(before: Instant, at: Instant) {
            stateValue = stateValue.copy(lastSweepAt = at)
        }

        override suspend fun clear() {
            stateValue = SyncState()
        }
    }

    private companion object {
        /** Far enough that the 30-second refresh margin never triggers in a test. */
        const val FAR_FUTURE = 4_102_444_800L // 2100-01-01

        /** A syntactically valid JWT whose payload carries only `exp` — enough for the client,
         *  which reads the claim without verifying anything. */
        fun jwt(expEpochSecond: Long): String {
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray())
            val payload = encoder.encodeToString("""{"exp":$expEpochSecond}""".toByteArray())
            return "$header.$payload.sig"
        }
    }
}
