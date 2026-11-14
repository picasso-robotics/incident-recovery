package dev.picasso.recovery.core

import com.fasterxml.jackson.databind.node.ObjectNode
import dev.picasso.recovery.core.Episodes.INSTANCE
import dev.picasso.recovery.core.Episodes.T0
import dev.picasso.recovery.core.Episodes.policy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Export schema 9 (picasso §15.323, f1dfd94): `resolution.reworkBasis`, an episode closed by a person's rework, and the
 * REWORK_RECHECK incident. run-3 is picasso's handoff bundle: incident-1 (attempt 0) reworked as a hold — its basis is
 * null — and incident-2, the same unit's next attempt under a new key, confirmed done.
 */
class ReworksTest {
    private val run = PicassoRework

    private fun edited(line: String, edit: ObjectNode.() -> Unit): String =
        mapper.writeValueAsString((mapper.readTree(line) as ObjectNode).apply(edit))

    private fun resolution(line: ObjectNode): ObjectNode = line.get("resolution") as ObjectNode

    /** incident-3 of run-3 as picasso writes an attempted failure that a person took for rework, with [basis] as raw JSON. */
    private fun attemptedRework(basis: String, decision: String = "REWORK"): String = edited(run.incident("incident-3")) {
        put("unresolved", false)
        set<ObjectNode>("resolution", mapper.readTree(
            """{"decision":"$decision","at":"2026-09-06T00:00:05Z","wallClockAt":"2026-11-14T16:01:47Z","decidedBy":{"id":"op-1","kind":"PERSON"},"reworkBasis":$basis}""",
        ))
    }

    private val observed = """{"robotId":"hum-04","hold":{"kind":"HOLD_KIND_EMPTY","objectRef":null},""" +
        """"source":{"slotId":"SEQ-IN-02.BIN-A","occupied":true,"identity":"ENGINE-COVER-A","observedAt":"2026-09-06T00:00:03Z"},""" +
        """"destination":{"slotId":"RACK-204.S02","occupied":false,"identity":null,"observedAt":null},"readAt":"2026-09-06T00:00:04Z"}"""
    private val marker = """{"robotId":"hum-04","hold":null,"source":null,"destination":null,"readAt":"2026-09-06T00:00:04Z"}"""

    /**
     * The first-issue recheck's failure as picasso opens it (`Rework.halts`, `IncidentLog.sealIncidents`): the same line
     * shape as any incident, after `reworkAsNewAttempt` reset the unit — no task, no fault, nothing verified or reached,
     * no expected hold, not unresolved — on the rework attempt (1), under a new key, at 00:00:06.
     */
    private fun recheck(unresolved: Boolean = false): String = edited(run.incident("incident-3")) {
        put("incidentId", "incident-5")
        put("incidentKey", "mw-2122c075-803d-4c91-a3b5-39f841d7cb4a/incident-5")
        put("firstOpenedAt", "2026-09-06T00:00:06Z")
        put("attempt", 1)
        put("at", "2026-09-06T00:00:06Z")
        put("failureClass", "REWORK_RECHECK")
        putNull("fault")
        put("unresolved", unresolved)
        putArray("preconditionSubjects")
        putNull("expectedHold")
        putNull("effectMismatch")
        put("reachedEvidence", "E0")
        put("verification", "NOT_REQUESTED")
        put("digest", "digest-rework-recheck")
        putNull("resolution")
    }

    /** A FOUND remedy search of incident-3's robot and order (hum-04, SEQ-2), made at [at]. */
    private fun found(id: String, at: String): String = edited(PicassoRun1.search("search-1")) {
        put("searchId", id)
        put("robotId", "hum-04")
        put("jobOrderId", "SEQ-2")
        put("at", at)
    }

    private fun parse(vararg incidents: String) = Snapshot.parse(run.manifest, incidents.toList(), emptyList())

    // --- shape ---

    @Test fun `picasso's schema 9 bundles read as written - every resolution carries the key reworkBasis`() {
        val s = run.snapshot(listOf("incident-1", "incident-2", "incident-3", "incident-4"))
        assertEquals("9", s.manifest.text("schemaVersion"))
        val decided = s.incidents.filter { it.get("resolution").isObject }
        assertEquals(listOf("REWORK", "CONFIRM_DONE"), decided.map { resolution(it).text("decision") })
        assertTrue(decided.all { resolution(it).has("reworkBasis") && resolution(it).get("reworkBasis").isNull })
        PicassoRestart9Before.snapshot(listOf("incident-1"), listOf("search-1", "search-2", "search-3"))
        PicassoRestart9After.snapshot(listOf("incident-1"))
        parse(attemptedRework(observed), attemptedRework(marker))
    }

    @Test fun `schema 9 keeps every schema 8 check and links incidents by key`() {
        val line = mapper.readTree(run.incident("incident-1")) as ObjectNode
        assertEquals(IncidentLink.Keyed("mw-2122c075-803d-4c91-a3b5-39f841d7cb4a/incident-1", reopened = false), incidentLink("9", line))
        val reopened = mapper.readTree(PicassoRestart9After.incident("incident-1"))
        assertEquals(true, (incidentLink("9", reopened) as IncidentLink.Keyed).reopened)
        val e = assertFailsWith<IllegalArgumentException> { parse(edited(run.incident("incident-1")) { remove("openingKind") }) }
        assertTrue("openingKind" in e.message.orEmpty(), e.message)
    }

    @Test fun `reworkBasis must be present on a given resolution, and only a REWORK carries a value`() {
        fun rejected(line: String, why: String) {
            val e = assertFailsWith<IllegalArgumentException> { parse(line) }
            assertTrue(why in e.message.orEmpty(), e.message)
        }
        rejected(edited(run.incident("incident-1")) { resolution(this).remove("reworkBasis") }, "resolution.reworkBasis must be present")
        rejected(attemptedRework(marker, decision = "CONFIRM_DONE"), "only on a REWORK decision")
        rejected(attemptedRework("[]"), "must be null or an object")
        rejected(attemptedRework(marker.replace(""""robotId":"hum-04",""", "")), "reworkBasis.robotId")
        rejected(attemptedRework(marker.replace(""","readAt":"2026-09-06T00:00:04Z"""", "")), "reworkBasis.readAt")
        rejected(attemptedRework(marker.replace(""""hold":null,""", "")), "reworkBasis.hold")
        rejected(attemptedRework(observed.replace(""""kind":"HOLD_KIND_EMPTY",""", "")), "reworkBasis.hold")
        rejected(attemptedRework(observed.replace(""""occupied":true""", """"occupied":"yes"""")), "reworkBasis.source")
        rejected(attemptedRework(observed.replace(""","identity":null,"observedAt":null}""", "}")), "reworkBasis.destination")
        // the slot the equipment was silent about: every key, null values
        parse(attemptedRework(observed.replace(""""occupied":false""", """"occupied":null""")))
        // a schema 8 resolution has no reworkBasis key: not asked there
        val eight = PicassoRestartAfter.manifest
        Snapshot.parse(eight, listOf(edited(PicassoRestartAfter.incident("incident-1")) {
            putObject("resolution").put("decision", "REWORK").put("at", "x").put("wallClockAt", "x")
                .putObject("decidedBy").put("id", "op-1").put("kind", "PERSON")
        }), emptyList())
    }

    // --- what a basis confirms ---

    @Test fun `a basis confirms only what its observed values say`() {
        val basis = assertIs<ReworkBasisRead.Observed>(reworkBasisOf(mapper.readTree(attemptedRework(observed))))
        assertEquals("hum-04" to "2026-09-06T00:00:04Z", basis.robotId to basis.readAt)
        assertEquals(
            listOf(
                ReworkConfirmation(ReworkConfirmed.EMPTY_HAND, null, null, null),
                ReworkConfirmation(ReworkConfirmed.SOURCE_OCCUPIED, "SEQ-IN-02.BIN-A", "ENGINE-COVER-A", "2026-09-06T00:00:03Z"),
                ReworkConfirmation(ReworkConfirmed.DESTINATION_FREE, "RACK-204.S02", null, null),
            ),
            basis.confirmed,
        )
        // occupied null (the equipment said nothing) is no confirmation; a taken destination or a holding hand neither
        val silent = observed.replace(""""occupied":false""", """"occupied":null""").replace(""""occupied":true""", """"occupied":null""")
            .replace("HOLD_KIND_EMPTY", "HOLD_KIND_HOLDING")
        assertEquals(emptyList(), assertIs<ReworkBasisRead.Observed>(reworkBasisOf(mapper.readTree(attemptedRework(silent)))).confirmed)
        val taken = observed.replace(""""occupied":false""", """"occupied":true""")
        assertEquals(listOf(ReworkConfirmed.EMPTY_HAND, ReworkConfirmed.SOURCE_OCCUPIED),
            assertIs<ReworkBasisRead.Observed>(reworkBasisOf(mapper.readTree(attemptedRework(taken)))).confirmed.map { it.what })
        // an OPTIONAL source: something is there, its identity unknown — carried as null, not as the material
        val unknownThing = observed.replace(""""identity":"ENGINE-COVER-A"""", """"identity":null""")
        assertNull(assertIs<ReworkBasisRead.Observed>(reworkBasisOf(mapper.readTree(attemptedRework(unknownThing)))).confirmed[1].identity)
    }

    @Test fun `robotId and readAt alone are no observation, and no basis is none`() {
        assertEquals(ReworkBasisRead.NotObserved("hum-04", "2026-09-06T00:00:04Z"), reworkBasisOf(mapper.readTree(attemptedRework(marker))))
        assertEquals(ReworkBasisRead.None, reworkBasisOf(mapper.readTree(run.incident("incident-1"))))
        assertEquals(ReworkBasisRead.None, reworkBasisOf(mapper.readTree(run.incident("incident-3"))))
    }

    // --- closure ---

    @Test fun `an episode whose every incident a person reworked is closed by rework`() {
        val one = reworkClosure(run.snapshot(listOf("incident-1")))!!
        assertEquals(listOf(ReworkedIncident("incident-1", "mw-2122c075-803d-4c91-a3b5-39f841d7cb4a/incident-1", 0, ReworkBasisRead.None)), one)
        // several incidents of one bundle each carry REWORK: one closure for all, the basis only where picasso put one
        val two = reworkClosure(parse(run.incident("incident-1"), attemptedRework(observed)))!!
        assertEquals(listOf(ReworkBasisRead.None::class, ReworkBasisRead.Observed::class), two.map { it.basis::class })
        // search lines do not hold it open
        assertTrue(reworkClosure(Snapshot.parse(run.manifest, listOf(run.incident("incident-1")), listOf(PicassoRestart9Before.search("search-1")))) != null)
    }

    @Test fun `an undecided or otherwise decided incident keeps the episode going - the next attempt is a new incident`() {
        // incident-2 is the same unit's next attempt (attempt 1, its own key): not folded into incident-1's rework
        assertNull(reworkClosure(run.snapshot(listOf("incident-1", "incident-2"))))
        assertNull(reworkClosure(run.snapshot(listOf("incident-1", "incident-3"))))
        assertNull(reworkClosure(parse(attemptedRework(observed), recheck())))
        assertNull(reworkClosure(run.snapshot(listOf("incident-2"))))
        // the same key's later line, undecided (a hold a restart reopened), is what the incident says now
        val reopened = edited(run.incident("incident-1")) { put("openingKind", "REOPENED"); putNull("resolution"); put("digest", "d2") }
        assertNull(reworkClosure(parse(run.incident("incident-1"), reopened)))
        assertTrue(reworkClosure(parse(reopened, run.incident("incident-1"))) != null)
    }

    @Test fun `before schema 9 a REWORK reads as before - no closure, so committed histories replay unchanged`() {
        val line = edited(PicassoRestartAfter.incident("incident-1")) {
            putObject("resolution").put("decision", "REWORK").put("at", "x").put("wallClockAt", "x")
                .putObject("decidedBy").put("id", "op-1").put("kind", "PERSON")
        }
        assertNull(reworkClosure(Snapshot.parse(PicassoRestartAfter.manifest, listOf(line), emptyList())))
        // no incident at all: nothing was reworked
        assertNull(reworkClosure(Snapshot.parse(run.manifest, emptyList(), listOf(PicassoRestart9Before.search("search-1")))))
    }

    @Test fun `the episode closes by rework instead of diagnosing, with what the basis confirmed as its grounds`() {
        val manifest = run.manifest
        val line = mapper.readTree(attemptedRework(observed)) as ObjectNode
        val opened = startEpisode(EpisodeEvent.Opened(INSTANCE, manifest, Symptom("incident:rw", SymptomKind.INCIDENT, line, T0)), policy(), T0)
        assertEquals(Phase.CORRELATING, opened.state.phase)
        val s = opened.expireState()
        assertEquals(Phase.CLOSED, s.state.phase)
        assertNull(s.state.escalation)
        assertTrue(s.commands.none { it is Command.Diagnose }, "a reworked episode is not diagnosed")
        assertEquals(emptyList(), s.notices(), "no one is paged: the person who reworked is already on it")
        val transition = s.records(RecordKind.TRANSITION).single().entry.payload
        assertEquals("CLOSED", transition.text("to"))
        assertTrue(transition.text("detail")!!.startsWith("REWORKED"), transition.toString())
        val record = s.records(RecordKind.REWORKED).single().entry.payload.get("incidents").single()
        assertEquals("OBSERVED", record.text("basis"))
        assertEquals(listOf("EMPTY_HAND", "SOURCE_OCCUPIED", "DESTINATION_FREE"), record.get("confirmed").map { it.text("what") })
        assertEquals("2026-09-06T00:00:03Z", record.get("confirmed")[1].text("observedAt"))
        // a closed episode takes nothing more
        assertEquals(Phase.CLOSED, s.on(EpisodeEvent.SymptomJoined(Symptom("incident:next", SymptomKind.INCIDENT, mapper.readTree(recheck()) as ObjectNode, T0)), T0.plusSeconds(9)).state.phase)

        val markerOnly = startEpisode(EpisodeEvent.Opened(INSTANCE, manifest,
            Symptom("incident:mk", SymptomKind.INCIDENT, mapper.readTree(attemptedRework(marker)) as ObjectNode, T0)), policy(), T0).expireState()
        val m = markerOnly.records(RecordKind.REWORKED).single().entry.payload.get("incidents").single()
        assertEquals("NOT_OBSERVED", m.text("basis"))
        assertTrue(!m.has("confirmed"), "no observation is not an empty confirmation list")
    }

    @Test fun `a rework that joins an episode before its diagnosis closes it - one undecided incident keeps it diagnosing`() {
        val first = Symptom("incident:1", SymptomKind.INCIDENT, mapper.readTree(run.incident("incident-1")) as ObjectNode, T0)
        val next = Symptom("incident:2", SymptomKind.INCIDENT, mapper.readTree(run.incident("incident-3")) as ObjectNode, T0)
        val opened = startEpisode(EpisodeEvent.Opened(INSTANCE, run.manifest, first), policy(), T0)
        assertEquals(Phase.DIAGNOSING, opened.on(EpisodeEvent.SymptomJoined(next), T0.plusSeconds(1)).expireState().state.phase)
        assertEquals(Phase.CLOSED, opened.expireState().state.phase)
    }

    // --- REWORK_RECHECK ---

    @Test fun `a REWORK_RECHECK incident offers no decision and no remedy - a person reworks again`() {
        assertEquals(listOf("ESCALATE"), projectCandidates(parse(recheck()), emptyList()).map { it.candidateId })
        // even a line that says unresolved: the class is no robot failure to decide on here
        val odd = edited(recheck(unresolved = true)) { put("verification", "MATCHED") }
        assertEquals(listOf("ESCALATE"), projectCandidates(parse(odd), emptyList()).map { it.candidateId })
        // its episode is diagnosed with ESCALATE alone, never closed: no one has reworked it yet
        val opened = startEpisode(EpisodeEvent.Opened(INSTANCE, run.manifest,
            Symptom("incident:rc", SymptomKind.INCIDENT, mapper.readTree(recheck()) as ObjectNode, T0)), policy(), T0).expireState()
        assertEquals(Phase.DIAGNOSING, opened.state.phase)
        assertEquals(listOf("ESCALATE"), opened.commands.filterIsInstance<Command.Diagnose>().single().request.candidates.map { it.candidateId })
        // once a person reworks it, it carries REWORK and closes as any reworked incident
        val reworked = edited(recheck()) {
            set<ObjectNode>("resolution", mapper.readTree(
                """{"decision":"REWORK","at":"2026-09-06T00:00:09Z","wallClockAt":"x","decidedBy":{"id":"op-1","kind":"PERSON"},"reworkBasis":$observed}""",
            ))
        }
        assertTrue(reworkClosure(parse(reworked)) != null)
    }

    @Test fun `a REWORK_RECHECK line passes the schema 8 checks and correlates by robot and order as picasso writes it`() {
        val line = mapper.readTree(recheck())
        assertTrue(Snapshot.formatProblems("9", mapper.readTree(run.manifest), listOf(line), emptyList()).isEmpty())
        val rule = CorrelationRule(setOf(SymptomKind.SEARCH, SymptomKind.INCIDENT), listOf("robotId", "jobOrderId"))
        assertEquals(Correlation.Merged("r:by:robotId:hum-04:jobOrderId:SEQ-2"), correlate(SymptomKind.INCIDENT, line, "r", "incident-5", listOf(rule)))
    }

    @Test fun `a reworked attempt's remedy search offers nothing - a REWORK_RECHECK episode stays ESCALATE alone`() {
        // incident-3 reworked at 00:00:05 (its search at 00:00:04), the rework not issued at 00:00:06 (REWORK_RECHECK)
        val earlier = found("search-a", "2026-09-06T00:00:04Z")
        val s = Snapshot.parse(run.manifest, listOf(attemptedRework(observed), recheck()), listOf(earlier))
        assertEquals(listOf("ESCALATE"), projectCandidates(s, emptyList()).map { it.candidateId })
        // the recheck alone covers a search made before it, too
        assertEquals(listOf("ESCALATE"), projectCandidates(Snapshot.parse(run.manifest, listOf(recheck()), listOf(earlier)), emptyList()).map { it.candidateId })
        // a later search, of a later failure, is offered; so is another order's
        val later = found("search-b", "2026-09-06T00:00:09Z")
        val other = edited(found("search-c", "2026-09-06T00:00:04Z")) { put("jobOrderId", "SEQ-9") }
        assertEquals(
            listOf("APPROVE_REMEDY:hum-04:SEQ-2:pick_place", "APPROVE_REMEDY:hum-04:SEQ-9:pick_place", "ESCALATE"),
            projectCandidates(Snapshot.parse(run.manifest, listOf(attemptedRework(observed), recheck()), listOf(earlier, later, other)), emptyList())
                .map { it.candidateId },
        )
        assertEquals("search-b", projectCandidates(Snapshot.parse(run.manifest, listOf(recheck()), listOf(earlier, later)), emptyList())[0].ref!!["searchId"])
        // a time that cannot be read covers the search
        val unreadable = edited(found("search-d", "2026-09-06T00:00:09Z")) { put("at", "soon") }
        assertEquals(listOf("ESCALATE"), projectCandidates(Snapshot.parse(run.manifest, listOf(recheck()), listOf(unreadable)), emptyList()).map { it.candidateId })
        // before schema 9 the search is offered as before
        val eight = Snapshot.parse(PicassoRestartAfter.manifest, listOf(attemptedRework(observed)), listOf(earlier))
        assertEquals(listOf("APPROVE_REMEDY:hum-04:SEQ-2:pick_place", "ESCALATE"), projectCandidates(eight, emptyList()).map { it.candidateId })
    }

    @Test fun `a re-diagnosis closes by rework too - a dispatched remedy, a REWORK joins, NOT_DONE`() {
        val search = Symptom("search:a", SymptomKind.SEARCH, mapper.readTree(found("search-a", "2026-09-06T00:00:04Z")) as ObjectNode, T0)
        val diagnosing = startEpisode(EpisodeEvent.Opened(INSTANCE, run.manifest, search), policy(), T0).expireState()
        assertEquals("APPROVE_REMEDY:hum-04:SEQ-2:pick_place", diagnosing.request.candidates.first().candidateId)
        val dispatched = diagnosing.answer().let { it.revalidated(TriState.TRUE, it.state.enteredAt.plusSeconds(1)) }.recorded()
        val waiting = dispatched.returned(DispatchResult.Answer(PicassoAnswers.approved()))
        assertEquals(Phase.AWAITING_EVIDENCE, waiting.state.phase)
        // the person reworks the unit instead: the REWORK line joins, and the wait for evidence is left as it is
        val rework = Symptom("incident:rw", SymptomKind.INCIDENT, mapper.readTree(attemptedRework(observed)) as ObjectNode, T0)
        val joined = waiting.on(EpisodeEvent.SymptomJoined(rework), waiting.state.enteredAt.plusSeconds(5))
        assertEquals(Phase.AWAITING_EVIDENCE, joined.state.phase)
        val attempt = joined.state.attempt!!
        val s = joined.on(EpisodeEvent.ConfirmedOutcome(attempt.candidate!!.candidateId, attempt.proposalId, false, Episodes.OPERATOR), joined.state.enteredAt.plusSeconds(10))
        assertEquals(Phase.CLOSED, s.state.phase)
        assertTrue(s.commands.none { it is Command.Diagnose })
        assertEquals(1, s.records(RecordKind.REDIAGNOSE).size)
        assertEquals("OBSERVED", s.records(RecordKind.REWORKED).single().entry.payload.get("incidents").single().text("basis"))
        // and in ESCALATED nothing closes: a person has the episode
        val escalated = waiting.on(EpisodeEvent.TakenOver(Episodes.OPERATOR), waiting.state.enteredAt.plusSeconds(1))
        assertEquals(Phase.ESCALATED, escalated.on(EpisodeEvent.SymptomJoined(rework), escalated.state.enteredAt.plusSeconds(1)).state.phase)
    }

    @Test fun `an attempted failure that a person reworked offers no decision`() {
        assertEquals(listOf("ESCALATE"), projectCandidates(parse(attemptedRework(observed)), emptyList()).map { it.candidateId })
    }
}
