package dev.picasso.recovery.runtime

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Records what the watcher sent; [failOpen] makes `open` throw; [running] says which instances still take evidence;
 * [episodeOpen] says which (workflow id, run id) still take a joining symptom; [failJoin] makes `join` throw.
 */
internal class RecordingSignals : EpisodeSignals {
    val opened = mutableListOf<Pair<String, SymptomSignal>>()
    val joined = mutableListOf<Pair<String, SymptomSignal>>()
    val evidence = mutableListOf<Pair<String, EvidenceSignal>>()
    var failOpen: RuntimeException? = null
    var failJoin: RuntimeException? = null
    var running: (String) -> Boolean = { true }
    var episodeOpen: (String, String) -> Boolean = { _, _ -> true }
    override fun open(workflowId: String, start: EpisodeStart, symptom: SymptomSignal): String {
        failOpen?.let { throw it }
        opened += workflowId to symptom
        return "run"
    }
    override fun join(workflowId: String, runId: String, symptom: SymptomSignal): Boolean {
        failJoin?.let { throw it }
        if (!episodeOpen(workflowId, runId)) return false
        joined += "$workflowId/$runId" to symptom
        return true
    }
    override fun evidence(instanceId: String, signal: EvidenceSignal): Boolean {
        if (!running(instanceId)) return false
        evidence += instanceId to signal
        return true
    }
}

/** Writes a picasso-shaped export (lines first, manifest last), as picasso's BundleWriter does. */
internal class ExportDir(val dir: Path, var runId: String = "run-A") {
    val incidents = mutableListOf<String>()
    val searches = mutableListOf<String>()
    /** The manifest's schemaVersion; "8" also writes an `instanceId`. */
    var schema: String = "5"
    fun snapshot(): ExportDir {
        write("incidents.jsonl", incidents.joinToString("") { "$it\n" })
        write("remedy-searches.jsonl", searches.joinToString("") { "$it\n" })
        val instance = if (schema == "8") ""","instanceId":"mw-$runId"""" else ""
        write("manifest.json", """{"schemaVersion":"$schema","runId":"$runId"$instance,"writtenAt":"x","virtualNow":"x","contractSemver":"0.9.0","counts":{"incidents":${incidents.size},"remedySearches":${searches.size}}}""")
        return this
    }

    /** Takes the lines, schemaVersion and runId of a committed picasso bundle (`/picasso/<path>/`), lines verbatim. */
    fun copy(path: String): ExportDir {
        val sample = Path.of(checkNotNull(javaClass.getResource("/picasso/$path/manifest.json")).toURI()).parent
        fun lines(name: String) = Files.readAllLines(sample.resolve(name)).filter { it.isNotBlank() }
        incidents.clear(); incidents += lines("incidents.jsonl")
        searches.clear(); searches += lines("remedy-searches.jsonl")
        val manifest = strictJson.readTree(Files.readString(sample.resolve("manifest.json")))
        schema = manifest.path("schemaVersion").textValue()
        runId = manifest.path("runId").textValue()
        return this
    }
    private fun write(name: String, text: String) {
        val tmp = dir.resolve("$name.tmp")
        Files.writeString(tmp, text)
        Files.move(tmp, dir.resolve(name), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}

class EpisodeWatcherIntakeTest {
    @TempDir lateinit var root: Path
    private lateinit var watch: WatchStore
    private val signals = RecordingSignals()
    private lateinit var export: ExportDir
    private lateinit var policy: Path

    @BeforeEach fun up() {
        EpisodeDb.reset()
        watch = WatchStore(EpisodeDb::connection)
        export = ExportDir(Files.createDirectories(root.resolve("export")))
        policy = root.resolve("active.yaml")
        Files.writeString(policy, TestPolicies.table(autoApprove = false))   // JSON is YAML
    }

    private fun watcher() = EpisodeWatcher(listOf(export.dir), carry = null, PolicyFileReader(policy), watch, signals) { Instant.ofEpochMilli(42) }
    private fun withRules(rules: String) = Files.writeString(policy, TestPolicies.table(autoApprove = false).replace("\"correlation\": []", "\"correlation\": $rules"))

    @Test fun `each new line opens its own episode once - a second poll sends nothing`() {
        export.incidents += Picasso.incident("incident-1")
        export.searches += Picasso.search("search-1")
        export.snapshot()
        val w = watcher()
        assertEquals(2, w.pollOnce().opened)
        assertEquals(listOf("ep:run-A:incident-1", "ep:run-A:search-1"), signals.opened.map { it.first })
        assertEquals("incident:run-A:d04ac2a20a63afc2ba147218bae871ef69746c990e3993deb00cfb4ddd262143", signals.opened[0].second.eventId)
        assertEquals(42L, signals.opened[0].second.observedAtMillis)
        assertEquals(0, w.pollOnce().opened)
        assertEquals(0, watcher().pollOnce().opened, "the cursor is in Postgres, not in the process")
    }

    @Test fun `a rewritten export sends only its new lines`() {
        export.searches += Picasso.search("search-1"); export.snapshot()
        val w = watcher(); w.pollOnce()
        export.searches += Picasso.search("search-2"); export.snapshot()
        assertEquals(1, w.pollOnce().opened)
        assertEquals("ep:run-A:search-2", signals.opened.last().first)
    }

    @Test fun `a new run in the same directory is new symptoms`() {
        export.searches += Picasso.search("search-1"); export.snapshot()
        val w = watcher(); w.pollOnce()
        export.runId = "run-B"; export.snapshot()
        assertEquals(1, w.pollOnce().opened)
        assertEquals("search:run-B:search-1", signals.opened.last().second.eventId)
    }

    @Test fun `a merge rule puts one robot and order into one episode`() {
        withRules("""[{"kinds":["SEARCH","INCIDENT"],"by":["robotId","jobOrderId"]}]""")
        export.incidents += Picasso.incident("incident-1")
        export.searches += Picasso.search("search-1")
        export.searches += Picasso.search("search-2")
        export.snapshot()
        watcher().pollOnce()
        assertEquals(
            listOf("ep:run-A:by:robotId:hum-02:jobOrderId:PATROL-1", "ep:run-A:by:robotId:hum-02:jobOrderId:PATROL-1",
                "ep:run-A:by:robotId:hum-03:jobOrderId:PATROL-2"),
            signals.opened.map { it.first },
        )
    }

    @Test fun `rules that disagree merge nothing and leave a cross-reference`() {
        withRules("""[{"kinds":["SEARCH"],"by":["robotId"]},{"kinds":["SEARCH"],"by":["jobOrderId"]}]""")
        export.searches += Picasso.search("search-1"); export.snapshot()
        watcher().pollOnce()
        assertEquals("ep:run-A:search-1", signals.opened.single().first)
        val log = watch.logs(10).single { it.kind == "AMBIGUOUS" }
        assertTrue("run-A:by:robotId:hum-02" in log.detailJson && "run-A:by:jobOrderId:PATROL-1" in log.detailJson, log.detailJson)
    }

    @Test fun `an ambiguous line is said once, only after it was delivered`() {
        withRules("""[{"kinds":["SEARCH"],"by":["robotId"]},{"kinds":["SEARCH"],"by":["jobOrderId"]}]""")
        export.searches += Picasso.search("search-1"); export.snapshot()
        val w = watcher()
        signals.failOpen = IllegalStateException("temporal down")
        assertEquals(0, w.pollOnce().opened)
        assertEquals(0, watch.logs(10).count { it.kind == "AMBIGUOUS" }, "not delivered: nothing to cross-reference yet")
        signals.failOpen = null
        assertEquals(1, w.pollOnce().opened)
        w.pollOnce()
        assertEquals(1, watch.logs(10).count { it.kind == "AMBIGUOUS" })
    }

    @Test fun `an unusable policy merges nothing, and says so once`() {
        Files.writeString(policy, "version: [")
        export.searches += Picasso.search("search-1"); export.snapshot()
        val w = watcher(); w.pollOnce(); w.pollOnce()
        assertEquals("ep:run-A:search-1", signals.opened.single().first)
        assertEquals(1, watch.logs(10).count { it.kind == "POLICY_UNUSABLE" })
    }

    @Test fun `a failed signal moves nothing - the next poll sends it again`() {
        export.searches += Picasso.search("search-1"); export.snapshot()
        val w = watcher()
        signals.failOpen = IllegalStateException("temporal down")
        assertEquals(0, w.pollOnce().opened)
        signals.failOpen = null
        assertEquals(1, w.pollOnce().opened)
        assertEquals(1, watch.logs(10).count { it.kind == "SIGNAL_FAILED" })
    }

    @Test fun `a source that changed under the cursor stops, it is not re-guessed`() {
        export.searches += Picasso.search("search-1"); export.searches += Picasso.search("search-2"); export.snapshot()
        val w = watcher(); w.pollOnce()
        export.searches.clear(); export.searches += Picasso.search("search-3"); export.searches += Picasso.search("search-4"); export.snapshot()
        assertEquals(0, w.pollOnce().opened)
        assertEquals(0, w.pollOnce().opened)
        assertEquals(1, watch.logs(10).count { it.kind == "STUCK" })
    }

    @Test fun `an incident without digest stops its file at that line`() {
        export.incidents += """{"incidentId":"incident-x","robotId":"hum-01","jobOrderId":"J"}"""
        export.incidents += Picasso.incident("incident-1")
        export.searches += Picasso.search("search-1")
        export.snapshot()
        val w = watcher()
        w.pollOnce(); w.pollOnce()
        assertEquals(listOf("ep:run-A:search-1"), signals.opened.map { it.first }, "the other file goes on, the incidents stay stopped")
        assertEquals(1, watch.logs(10).count { it.kind == "BROKEN" })
    }

    @Test fun `an id that makes no valid key stops its file - nothing of it is sent`() {
        export.searches += """{"searchId":"s:1","robotId":"hum-02","jobOrderId":"PATROL-1"}"""
        export.searches += Picasso.search("search-1")
        export.snapshot()
        val w = watcher()
        assertEquals(0, w.pollOnce().opened)
        assertEquals(0, w.pollOnce().opened)
        val stuck = watch.logs(10).single { it.kind == "STUCK" }
        assertTrue("no valid key" in stuck.detailJson && "s:1" in stuck.detailJson, stuck.detailJson)
    }

    @Test fun `a directory without a manifest is said once, not every poll`() {
        val w = watcher()
        w.pollOnce(); w.pollOnce()
        assertEquals(1, watch.logs(10).count { it.kind == "NOT_READY" })
    }

    @Test fun `the last valid rules stay when the policy becomes unusable, and each new reason is said once`() {
        withRules("""[{"kinds":["SEARCH"],"by":["robotId","jobOrderId"]}]""")
        export.searches += Picasso.search("search-1"); export.snapshot()
        val w = watcher(); w.pollOnce()
        Files.writeString(policy, "version: [")   // unreadable
        export.searches += Picasso.search("search-2"); export.snapshot()
        w.pollOnce(); w.pollOnce()
        assertEquals("ep:run-A:by:robotId:hum-03:jobOrderId:PATROL-2", signals.opened.last().first, "the last valid rules still merge")
        assertEquals(1, watch.logs(10).count { it.kind == "POLICY_UNUSABLE" })
        Files.delete(policy)   // missing: a new reason
        w.pollOnce(); w.pollOnce()
        val said = watch.logs(10).filter { it.kind == "POLICY_UNUSABLE" }
        assertEquals(2, said.size)
        assertTrue("missing" in said.first().detailJson && "the last valid rules" in said.first().detailJson, said.first().detailJson)
    }

    @Test fun `a failed signal is said once per kind of failure, not per message`() {
        export.searches += Picasso.search("search-1"); export.snapshot()
        val w = watcher()
        signals.failOpen = IllegalStateException("temporal down at 1")
        w.pollOnce()
        signals.failOpen = IllegalStateException("temporal down at 2")
        w.pollOnce()
        assertEquals(1, watch.logs(10).count { it.kind == "SIGNAL_FAILED" })
        signals.failOpen = IllegalArgumentException("another kind")
        w.pollOnce()
        assertEquals(2, watch.logs(10).count { it.kind == "SIGNAL_FAILED" })
    }

    @Test fun `a failed signal names its line, once per line - a new stuck line is a new row`() {
        export.searches += Picasso.search("search-1"); export.searches += Picasso.search("search-2"); export.snapshot()
        var failing = "ep:run-A:search-1"
        var n = 0
        val flaky = object : EpisodeSignals by signals {
            override fun open(workflowId: String, start: EpisodeStart, symptom: SymptomSignal): String {
                if (workflowId == failing) throw IllegalStateException("temporal down at ${++n}")
                return signals.open(workflowId, start, symptom)
            }
        }
        val w = EpisodeWatcher(listOf(export.dir), carry = null, PolicyFileReader(policy), watch, flaky) { Instant.ofEpochMilli(42) }
        w.pollOnce(); w.pollOnce()
        val first = strictJson.readTree(watch.logs(10).single { it.kind == "SIGNAL_FAILED" }.detailJson)
        assertEquals(listOf("run-A", "1", "search-1", "search:run-A:search-1", "temporal down at 1"),
            listOf("runId", "position", "id", "eventId", "why").map { first.get(it).asText() }, "$first")
        failing = "ep:run-A:search-2"
        assertEquals(1, w.pollOnce().opened, "search-1 goes, search-2 is stuck")
        w.pollOnce()
        val rows = watch.logs(10).filter { it.kind == "SIGNAL_FAILED" }.map { strictJson.readTree(it.detailJson) }
        assertEquals(2, rows.size)
        assertEquals(listOf("2", "search-2"), listOf("position", "id").map { rows.first().get(it).asText() }, "${rows.first()}")
    }

    @Test fun `a failed signal keeps what was sent before it and stops the pass before the next directory`() {
        export.searches += Picasso.search("search-1"); export.searches += Picasso.search("search-2"); export.snapshot()
        val other = ExportDir(Files.createDirectories(root.resolve("export2")), runId = "run-B")
        other.searches += Picasso.search("search-3"); other.snapshot()
        val attempted = mutableListOf<String>()
        var down = true
        val flaky = object : EpisodeSignals by signals {
            override fun open(workflowId: String, start: EpisodeStart, symptom: SymptomSignal): String {
                attempted += workflowId
                if (down && workflowId == "ep:run-A:search-2") throw IllegalStateException("temporal down")
                return signals.open(workflowId, start, symptom)
            }
        }
        val w = EpisodeWatcher(listOf(export.dir, other.dir), carry = null, PolicyFileReader(policy), watch, flaky) { Instant.ofEpochMilli(42) }
        assertEquals(1, w.pollOnce().opened)
        assertEquals(listOf("ep:run-A:search-1", "ep:run-A:search-2"), attempted, "the second directory is not attempted")
        down = false
        assertEquals(2, w.pollOnce().opened)
        assertEquals(listOf("ep:run-A:search-1", "ep:run-A:search-2", "ep:run-B:search-3"), signals.opened.map { it.first },
            "search-1 stayed carried and is not sent again")
    }

    @Test fun `a reason whose log row failed is logged at a later poll`() {
        val flaky = object : WatchStore(EpisodeDb::connection) {
            var fail = true
            override fun log(source: String, kind: String, detailJson: String) {
                if (fail) { fail = false; throw IllegalStateException("db down") }
                super.log(source, kind, detailJson)
            }
        }
        val w = EpisodeWatcher(listOf(export.dir), carry = null, PolicyFileReader(policy), flaky, signals) { Instant.ofEpochMilli(42) }
        assertFailsWith<IllegalStateException> { w.pollOnce() }
        w.pollOnce(); w.pollOnce()
        assertEquals(1, watch.logs(10).count { it.kind == "NOT_READY" })
    }

    @Test fun `a line that is not JSON stops the whole export - nothing before it, nothing in the other file`() {
        export.incidents += Picasso.incident("incident-1")
        export.incidents += "{not json"
        export.searches += Picasso.search("search-1")
        export.snapshot()
        val w = watcher()
        assertEquals(0, w.pollOnce().opened)
        assertEquals(0, w.pollOnce().opened)
        assertEquals(1, watch.logs(10).count { it.kind == "BROKEN" })
    }

    @Test fun `an export without its manifest is not read`() {
        Files.writeString(export.dir.resolve("remedy-searches.jsonl"), Picasso.search("search-1") + "\n")
        assertEquals(0, watcher().pollOnce().opened)
    }

    // Export schema 8 (picasso §15.322): the same incidentKey is one incident, in the episode running and in one ended.

    private val restartA = "run-2026-11-14T11:15:50.275989400Z-1"   // schema-8/run-5a: the instance before the restart
    private val restartB = "run-2026-11-14T11:15:50.314496800Z-2"   // schema-8/run-5b: the instance that reopened its hold
    private val firstEpisode = "ep:$restartA:incident-1"

    /** Carries run-5a in: its NEW incident opens [firstEpisode], its three searches their own episodes. */
    private fun beforeRestart(w: EpisodeWatcher) {
        export.copy("schema-8/run-5a").snapshot()
        assertEquals(4, w.pollOnce().opened)
        assertEquals(firstEpisode, signals.opened.first().first)
    }

    private fun detail(kind: String) = strictJson.readTree(watch.logs(100).single { it.kind == kind }.detailJson)

    @Test fun `a hold reopened after a picasso restart joins the episode its incident already has - no second episode`() {
        val w = watcher()
        beforeRestart(w)
        export.copy("schema-8/run-5b").snapshot()
        assertEquals(1, w.pollOnce().opened)
        assertEquals(4, signals.opened.size, "no episode opened for the reopened line")
        val (to, line) = signals.joined.single()
        assertEquals("$firstEpisode/run", to)
        assertTrue(line.eventId.startsWith("incident:$restartB:"), "the event id stays its own (receipt idempotency): ${line.eventId}")
        val folded = detail("FOLDED")
        assertEquals(listOf("RUNNING", "REOPENED", firstEpisode), listOf("episode", "openingKind", "workflowId").map { folded.get(it).asText() }, "$folded")
        assertTrue(folded.get("firstEventId").asText().startsWith("incident:$restartA:"), "$folded")
        assertEquals(0, w.pollOnce().opened)
        assertEquals(0, watcher().pollOnce().opened, "the key's episode is in Postgres, not in the process")
    }

    @Test fun `a hold reopened after its episode ended opens no new episode - it is folded and said`() {
        val w = watcher()
        beforeRestart(w)
        signals.episodeOpen = { _, _ -> false }   // the first episode has ended
        export.copy("schema-8/run-5b").snapshot()
        assertEquals(1, w.pollOnce().opened, "carried: the cursor moves")
        assertEquals(4, signals.opened.size)
        assertEquals(emptyList(), signals.joined)
        assertEquals("ENDED", detail("FOLDED").get("episode").asText())
        assertEquals(0, watcher().pollOnce().opened)
    }

    @Test fun `the first episode is the one the line went to - a merge rule's key, not the line's own`() {
        withRules("""[{"kinds":["SEARCH","INCIDENT"],"by":["robotId","jobOrderId"]}]""")
        val w = watcher()
        export.copy("schema-8/run-5a").snapshot(); w.pollOnce()
        export.copy("schema-8/run-5b").snapshot(); w.pollOnce()
        assertEquals("ep:$restartA:by:robotId:hum-02:jobOrderId:SEQ-RESTART/run", signals.joined.single().first)
    }

    @Test fun `a failed join stops the pass and is sent again - never through signalWithStart`() {
        val w = watcher()
        beforeRestart(w)
        export.copy("schema-8/run-5b").snapshot()
        signals.failJoin = IllegalStateException("temporal down")
        assertEquals(0, w.pollOnce().opened)
        assertEquals("incident-1", detail("SIGNAL_FAILED").get("id").asText())
        signals.failJoin = null
        assertEquals(1, w.pollOnce().opened)
        assertEquals(1, signals.joined.size)
        assertEquals(4, signals.opened.size)
    }

    @Test fun `a second run of the same scenario is new incidents - another instance, other keys`() {
        val w = watcher()
        export.copy("schema-8/run-1").snapshot()
        assertEquals(14, w.pollOnce().opened)
        export.copy("schema-8/run-2").snapshot()
        assertEquals(14, w.pollOnce().opened)
        assertEquals(28, signals.opened.size)
        assertEquals(emptyList(), signals.joined)
        assertEquals(0, watch.logs(100).count { it.kind == "FOLDED" })
    }

    @Test fun `a reopened line whose link is unknown opens its own episode, says so, and is never linked by a guess`() {
        val w = watcher()
        beforeRestart(w)
        fun unlinked(run: String) {
            export.copy("schema-8/run-5b")
            export.runId = run
            export.incidents[0] = export.incidents[0]
                .replace(""""incidentKey":"mw-336f1d22-234e-4617-a7ea-89609dacd917/incident-1"""", """"incidentKey":null""")
                .replace(""""firstOpenedAt":"2026-09-06T00:00:01Z"""", """"firstOpenedAt":null""")
            export.snapshot()
        }
        unlinked("restart-2")
        assertEquals(1, w.pollOnce().opened)
        unlinked("restart-3")   // the next restart writes one more such line for the same hold
        assertEquals(1, w.pollOnce().opened)
        assertEquals(listOf("ep:restart-2:incident-1", "ep:restart-3:incident-1"), signals.opened.drop(4).map { it.first })
        assertEquals(emptyList(), signals.joined, "the same robot, unit and class are no link")
        val unknown = watch.logs(100).filter { it.kind == "LINK_UNKNOWN" }.map { strictJson.readTree(it.detailJson) }
        assertEquals(listOf("restart-3:incident-1", "restart-2:incident-1"), unknown.map { it.get("key").asText() })
        assertEquals(0, watch.logs(100).count { it.kind == "FOLDED" })
    }

    @Test fun `a schema 7 line carries no key - nothing is folded, whatever it says`() {
        val w = watcher()
        export.copy("schema-8/run-5a")
        export.schema = "7"
        export.searches.clear()   // schema 7 has no SOURCE_UNOBSERVED
        export.snapshot(); w.pollOnce()
        export.runId = "run-again"; export.snapshot(); w.pollOnce()
        assertEquals(listOf("ep:$restartA:incident-1", "ep:run-again:incident-1"), signals.opened.map { it.first })
        assertEquals(emptyList(), signals.joined)
    }
}
