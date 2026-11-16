package dev.picasso.recovery.core

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.picasso.recovery.core.Episodes.INSTANCE
import dev.picasso.recovery.core.Episodes.T0
import dev.picasso.recovery.core.Episodes.policy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Export schema 10 (picasso §15.329, main 6c4143d): the same incident and search lines as schema 9, a third register
 * `revision-removals.jsonl` and the manifest's `counts.revisionRemovals`. A removal is no symptom — it changes no
 * diagnosis, no candidate and no closure, and it closes no incident it may be linked to.
 */
class RevisionRemovalsTest {
    private val run = PicassoRework10

    /** picasso's fixed line (`RevisionRemovalTest`, `LedgerExport.revisionRemovalLine`). */
    private val picassoLine = """{"executionId":"exec-1","jobOrderId":"SEQ-1","robotId":"hum-04","fromVersion":1,"toVersion":3,""" +
        """"removed":[{"unitId":"A","state":"PENDING"},{"unitId":"B","state":"FAILED"}],"at":"2026-11-16T01:02:03Z","wallClockAt":"2026-11-16T01:02:08Z"}"""

    /** run-3's manifest as picasso writes it after [n] removals: only the count differs. */
    private fun manifestAfter(n: Int): String =
        mapper.writeValueAsString((mapper.readTree(run.manifest) as ObjectNode).apply { (get("counts") as ObjectNode).put("revisionRemovals", n) })

    private fun problems(line: String) = revisionRemovalProblems(mapper.readTree(line))

    @Test fun `picasso's schema 10 bundles read as written - an empty register counted 0`() {
        val s = run.snapshot(listOf("incident-1", "incident-2", "incident-3", "incident-4"))
        assertEquals("10", s.manifest.text("schemaVersion"))
        assertEquals(0, s.manifest.get("counts").get("revisionRemovals").intValue())
        PicassoRestart10Before.snapshot(listOf("incident-1"), listOf("search-1", "search-2", "search-3"))
        PicassoRestart10After.snapshot(listOf("incident-1"))
        // the schema 8 and 9 checks hold in 10: the key link, and reworkBasis on every resolution
        assertTrue(incidentLink("10", mapper.readTree(run.incident("incident-1"))) is IncidentLink.Keyed)
        assertEquals(true, (incidentLink("10", mapper.readTree(PicassoRestart10After.incident("incident-1"))) as IncidentLink.Keyed).reopened)
        val noBasis = mapper.writeValueAsString((mapper.readTree(run.incident("incident-1")) as ObjectNode).apply { (get("resolution") as ObjectNode).remove("reworkBasis") })
        assertTrue("reworkBasis must be present" in assertFailsWith<IllegalArgumentException> { Snapshot.parse(run.manifest, listOf(noBasis), emptyList()) }.message.orEmpty())
    }

    @Test fun `a schema 10 manifest must count its removals - an older one is not asked`() {
        val missing = mapper.writeValueAsString((mapper.readTree(run.manifest) as ObjectNode).apply { (get("counts") as ObjectNode).remove("revisionRemovals") })
        val e = assertFailsWith<IllegalArgumentException> { Snapshot.parse(missing, listOf(run.incident("incident-1")), emptyList()) }
        assertTrue("counts.revisionRemovals" in e.message.orEmpty(), e.message)
        val negative = manifestAfter(-1)
        assertFailsWith<IllegalArgumentException> { Snapshot.parse(negative, listOf(run.incident("incident-1")), emptyList()) }
        // schema 9 has no register: its manifest carries no such count and is read as before
        Snapshot.parse(PicassoRework.manifest, listOf(PicassoRework.incident("incident-1")), emptyList())
    }

    @Test fun `a removal line is checked for its shape only`() {
        assertEquals(emptyList(), problems(picassoLine))
        // a revision may skip versions: the difference is not checked
        assertEquals(emptyList(), problems(picassoLine.replace("\"toVersion\":3", "\"toVersion\":9")))
        fun rejected(line: String, why: String) = assertTrue(problems(line).any { why in it }, "$why: ${problems(line)}")
        rejected(picassoLine.replace("\"executionId\":\"exec-1\",", ""), "executionId")
        rejected(picassoLine.replace("\"robotId\":\"hum-04\"", "\"robotId\":\"\""), "robotId")
        rejected(picassoLine.replace("\"fromVersion\":1", "\"fromVersion\":\"1\""), "fromVersion")
        rejected(picassoLine.replace(",\"wallClockAt\":\"2026-11-16T01:02:08Z\"", ""), "wallClockAt")
        rejected(picassoLine.replace(Regex("\"removed\":\\[.*?]]?,"), "\"removed\":[],"), "removed must be a non-empty array")
        // a unit picasso issued, finished or holds is never removed: a state outside PENDING and FAILED is a changed format
        rejected(picassoLine.replace("\"state\":\"FAILED\"", "\"state\":\"DONE\""), "removed[1]")
        rejected(picassoLine.replace("{\"unitId\":\"A\",", "{"), "removed[0]")
    }

    @Test fun `a removal changes no diagnosis, candidate or closure`() {
        // a skipped successor of incident-3's unit (exec-2) removed by a revision: the bundle now counts one removal
        val after = manifestAfter(1)
        val sets = listOf(listOf("incident-1"), listOf("incident-3"), listOf("incident-1", "incident-3"), listOf("incident-1", "incident-2"), listOf("incident-4"))
        for (ids in sets) {
            val before = Snapshot.parse(run.manifest, ids.map(run::incident), emptyList())
            val removed = Snapshot.parse(after, ids.map(run::incident), emptyList())
            assertEquals(projectCandidates(before, emptyList()), projectCandidates(removed, emptyList()), "$ids")
            assertEquals(reworkClosure(before), reworkClosure(removed), "$ids")
            // and schema 10 reads as schema 9 did: the same candidates, a closure exactly where 9 closed
            val nine = Snapshot.parse(PicassoRework.manifest, ids.map(PicassoRework::incident), emptyList())
            assertEquals(projectCandidates(nine, emptyList()).map { it.candidateId }, projectCandidates(removed, emptyList()).map { it.candidateId }, "$ids")
            assertEquals(reworkClosure(nine)?.map { it.incidentId to it.basis }, reworkClosure(removed)?.map { it.incidentId to it.basis }, "$ids")
        }
    }

    @Test fun `an open incident stays open after a removal - the episode is diagnosed, never closed as resolved`() {
        fun episode(manifest: String, id: String) = startEpisode(EpisodeEvent.Opened(INSTANCE, manifest,
            Symptom("incident:$id", SymptomKind.INCIDENT, mapper.readTree(run.incident(id)) as ObjectNode, T0)), policy(), T0).expireState()
        val before = episode(run.manifest, "incident-3")
        val removed = episode(manifestAfter(1), "incident-3")
        assertEquals(Phase.DIAGNOSING, removed.state.phase)
        assertNull(removed.state.escalation)
        assertEquals(before.request.candidates, removed.request.candidates)
        assertTrue(removed.records(RecordKind.REWORKED).isEmpty())
        // a reworked incident closes by rework as in schema 9, removal or not
        assertEquals(Phase.CLOSED, episode(manifestAfter(1), "incident-1").state.phase)
    }
}
