package top.wkbin.zaomeng.feature.sessions

import org.junit.Assert.assertEquals
import org.junit.Test
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.NovelSourceDto
import top.wkbin.zaomeng.data.api.RunManifestDto

class SessionsFilterTest {
    private val runs = listOf(
        run("run-alpha", "Alpha.txt"),
        run("run-beta", "Beta.txt"),
    )
    private val sessions = listOf(
        session(
            id = "older",
            runId = "run-beta",
            updatedAt = "2026-07-20T09:00:00Z",
            participants = listOf("贾宝玉", "林黛玉"),
        ),
        session(
            id = "newer",
            runId = "run-alpha",
            updatedAt = "2026-07-28T09:00:00Z",
            participants = listOf("孙悟空"),
        ),
    )

    @Test
    fun `search matches participants`() {
        val result = filterSessions(
            SessionsUiState(runs = runs, sessions = sessions, searchQuery = "黛玉"),
        )

        assertEquals(listOf("older"), result.map(DialogueSessionDto::sessionId))
    }

    @Test
    fun `recent sort shows latest activity first`() {
        val result = filterSessions(
            SessionsUiState(runs = runs, sessions = sessions, sort = SessionsSort.Recent),
        )

        assertEquals(listOf("newer", "older"), result.map(DialogueSessionDto::sessionId))
    }

    @Test
    fun `title sort uses book title`() {
        val result = filterSessions(
            SessionsUiState(runs = runs, sessions = sessions, sort = SessionsSort.Title),
        )

        assertEquals(listOf("newer", "older"), result.map(DialogueSessionDto::sessionId))
    }

    private fun run(id: String, sourceName: String) = RunManifestDto(
        runId = id,
        novelSources = listOf(NovelSourceDto(sourceName = sourceName)),
    )

    private fun session(
        id: String,
        runId: String,
        updatedAt: String,
        participants: List<String>,
    ) = DialogueSessionDto(
        sessionId = id,
        runId = runId,
        updatedAt = updatedAt,
        participants = participants,
    )
}
