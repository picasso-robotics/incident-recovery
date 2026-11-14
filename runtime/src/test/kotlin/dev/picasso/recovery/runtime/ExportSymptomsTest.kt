package dev.picasso.recovery.runtime

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExportSymptomsTest {
    private val run1: Path = Path.of(checkNotNull(javaClass.getResource("/picasso/run-1/manifest.json")).toURI()).parent
    private val runId = "run-2026-09-22T16:47:37.854173400Z-1"

    @Test fun `run-1 reads as nine incidents and four searches`() {
        val r = assertIs<BundleRead.Ready>(ExportSymptoms.read(run1))
        assertEquals(runId, r.runId)
        assertEquals(9, r.lines.getValue(ExportKind.INCIDENT.file.name).size)
        assertEquals(4, r.lines.getValue(ExportKind.SEARCH.file.name).size)
    }

    @Test fun `a search is keyed by run and searchId, an incident by run and digest`() {
        val r = assertIs<BundleRead.Ready>(ExportSymptoms.read(run1))
        val search = r.lines.getValue(ExportKind.SEARCH.file.name).first()
        val s = ExportSymptoms.symptom(ExportKind.SEARCH, r, search, observedAtMillis = 7)!!
        assertEquals("search:$runId:search-1", s.eventId)
        assertEquals("SEARCH", s.kind)
        assertEquals(search.text, s.lineJson)
        assertEquals(r.manifestJson, s.manifestJson)
        assertEquals(7, s.observedAtMillis)
        val incident = r.lines.getValue(ExportKind.INCIDENT.file.name).first()
        assertEquals("incident:$runId:d04ac2a20a63afc2ba147218bae871ef69746c990e3993deb00cfb4ddd262143",
            ExportSymptoms.symptom(ExportKind.INCIDENT, r, incident, 0)!!.eventId)
    }

    @Test fun `an export of schema 6 reads like schema 5, an unknown schema is broken`(@TempDir dir: Path) {
        Files.list(run1).use { files -> files.forEach { Files.copy(it, dir.resolve(it.fileName)) } }
        val manifest = dir.resolve("manifest.json")
        val five = Files.readString(manifest)
        Files.writeString(manifest, five.replace("\"schemaVersion\":\"5\"", "\"schemaVersion\":\"6\""))
        val r = assertIs<BundleRead.Ready>(ExportSymptoms.read(dir))
        assertEquals(9, r.lines.getValue(ExportKind.INCIDENT.file.name).size)
        assertEquals(4, r.lines.getValue(ExportKind.SEARCH.file.name).size)
        Files.writeString(manifest, five.replace("\"schemaVersion\":\"5\"", "\"schemaVersion\":\"9\""))
        assertIs<BundleRead.Broken>(ExportSymptoms.read(dir))
        // schema 8 without its fields: a run-1 line of schema 5 under "8" is a changed format
        Files.writeString(manifest, five.replace("\"schemaVersion\":\"5\"", "\"schemaVersion\":\"8\""))
        val eight = assertIs<BundleRead.Broken>(ExportSymptoms.read(dir))
        assertTrue("manifest.instanceId" in eight.why && "openingKind" in eight.why, eight.why)
    }

    private fun sample(path: String): Path = Path.of(checkNotNull(javaClass.getResource("/picasso/$path/manifest.json")).toURI()).parent

    @Test fun `the committed schema 8 samples read as picasso wrote them - digests and event ids unchanged in shape`() {
        val one = assertIs<BundleRead.Ready>(ExportSymptoms.read(sample("schema-8/run-1")))
        assertEquals(10, one.lines.getValue(ExportKind.INCIDENT.file.name).size)
        assertEquals(4, one.lines.getValue(ExportKind.SEARCH.file.name).size)
        val incident = one.lines.getValue(ExportKind.INCIDENT.file.name).first()
        assertEquals("incident:${one.runId}:${incident.node.path("digest").textValue()}", ExportSymptoms.symptom(ExportKind.INCIDENT, one, incident, 0)!!.eventId)
        assertIs<BundleRead.Ready>(ExportSymptoms.read(sample("schema-8/run-2")))
        val before = assertIs<BundleRead.Ready>(ExportSymptoms.read(sample("schema-8/run-5a")))
        assertEquals(listOf("SOURCE_UNOBSERVED", "DESTINATION_OCCUPIED", "DESTINATION_UNOBSERVED"),
            before.lines.getValue(ExportKind.SEARCH.file.name).map { it.node.path("outcome").textValue() })
        val after = assertIs<BundleRead.Ready>(ExportSymptoms.read(sample("schema-8/run-5b")))
        assertEquals(emptyList(), after.lines.getValue(ExportKind.SEARCH.file.name))
        val reopened = after.lines.getValue(ExportKind.INCIDENT.file.name).single()
        assertEquals("REOPENED", reopened.node.path("openingKind").textValue())
        assertEquals(before.lines.getValue(ExportKind.INCIDENT.file.name).single().node.path("incidentKey"), reopened.node.path("incidentKey"))
    }

    @Test fun `a schema 8 line out of shape makes the bundle broken`(@TempDir dir: Path) {
        Files.list(sample("schema-8/run-5a")).use { files -> files.forEach { Files.copy(it, dir.resolve(it.fileName)) } }
        val incidents = dir.resolve("incidents.jsonl")
        val good = Files.readString(incidents)
        Files.writeString(incidents, good.replace("\"openingKind\":\"NEW\"", "\"openingKind\":\"FRESH\""))
        assertTrue("openingKind" in assertIs<BundleRead.Broken>(ExportSymptoms.read(dir)).why)
        Files.writeString(incidents, good.replace("\"stallWindow\":\"PT40S\"", "\"stallWindow\":null"))
        assertTrue("null together" in assertIs<BundleRead.Broken>(ExportSymptoms.read(dir)).why)
        Files.writeString(incidents, good)
        val searches = dir.resolve("remedy-searches.jsonl")
        Files.writeString(searches, Files.readString(searches).replace("\"outcome\":\"DESTINATION_UNOBSERVED\"", "\"outcome\":\"DESTINATION_UNOBSERVED\",\"steps\":[]"))
        assertTrue("never an approvable remedy" in assertIs<BundleRead.Broken>(ExportSymptoms.read(dir)).why)
    }

    @Test fun `the committed schema 7 sample reads as ten incidents and four searches`() {
        val sample = Path.of(checkNotNull(javaClass.getResource("/picasso/schema-7/run-1/manifest.json")).toURI()).parent
        val r = assertIs<BundleRead.Ready>(ExportSymptoms.read(sample))
        assertEquals("run-2026-11-14T05:46:55.751143400Z-1", r.runId)
        val incidents = r.lines.getValue(ExportKind.INCIDENT.file.name)
        assertEquals(10, incidents.size)
        assertEquals(4, r.lines.getValue(ExportKind.SEARCH.file.name).size)
        // hum-02's and hum-04's earlier incidents carry the person's cancel (picasso §15.320)
        assertEquals(listOf("incident-1", "incident-3"), incidents.filter { it.node.path("resolution").path("decision").textValue() == "OPERATOR_CANCEL" }.map { it.id })
        val search = r.lines.getValue(ExportKind.SEARCH.file.name).first()
        assertEquals("search:${r.runId}:search-1", ExportSymptoms.symptom(ExportKind.SEARCH, r, search, 0)!!.eventId)
    }

    @Test fun `an incident decision its schema cannot carry makes the bundle broken`(@TempDir dir: Path) {
        val sample = Path.of(checkNotNull(javaClass.getResource("/picasso/schema-7/run-1/manifest.json")).toURI()).parent
        Files.list(sample).use { files -> files.forEach { Files.copy(it, dir.resolve(it.fileName)) } }
        val manifest = dir.resolve("manifest.json")
        val seven = Files.readString(manifest)
        // schema 6 had no OPERATOR_CANCEL: a known version with an unknown value is a changed format
        Files.writeString(manifest, seven.replace("\"schemaVersion\":\"7\"", "\"schemaVersion\":\"6\""))
        val six = assertIs<BundleRead.Broken>(ExportSymptoms.read(dir))
        assertTrue("OPERATOR_CANCEL" in six.why, six.why)
        Files.writeString(manifest, seven)
        val incidents = dir.resolve("incidents.jsonl")
        Files.writeString(incidents, Files.readString(incidents).replace("\"decision\":\"OPERATOR_CANCEL\"", "\"decision\":\"RECOVERED\""))
        assertIs<BundleRead.Broken>(ExportSymptoms.read(dir))
    }

    @Test fun `the committed schema 6 sample reads as nine incidents and four searches`() {
        val sample = Path.of(checkNotNull(javaClass.getResource("/picasso/schema-6/run-1/manifest.json")).toURI()).parent
        val r = assertIs<BundleRead.Ready>(ExportSymptoms.read(sample))
        assertEquals("run-2026-11-04T15:25:40.403783500Z-1", r.runId)
        assertEquals(9, r.lines.getValue(ExportKind.INCIDENT.file.name).size)
        assertEquals(4, r.lines.getValue(ExportKind.SEARCH.file.name).size)
        val search = r.lines.getValue(ExportKind.SEARCH.file.name).first()
        assertEquals("search:${r.runId}:search-1", ExportSymptoms.symptom(ExportKind.SEARCH, r, search, 0)!!.eventId)
    }

    @Test fun `an incident without a digest has no symptom`() {
        val line = BundleLine(1, "incident-x", """{"incidentId":"incident-x"}""", strictJson.readTree("""{"incidentId":"incident-x"}"""))
        val r = BundleRead.Ready("run", "{}", emptyMap())
        assertNull(ExportSymptoms.symptom(ExportKind.INCIDENT, r, line, 0))
        val numeric = """{"incidentId":"incident-x","digest":5}"""
        assertNull(ExportSymptoms.symptom(ExportKind.INCIDENT, r, BundleLine(1, "incident-x", numeric, strictJson.readTree(numeric)), 0))
    }
}
