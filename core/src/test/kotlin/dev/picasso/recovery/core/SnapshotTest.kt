package dev.picasso.recovery.core

import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SnapshotTest {
    private val manifest = PicassoRun1.manifest

    private fun edited(line: String, edit: ObjectNode.() -> Unit): String =
        mapper.writeValueAsString((mapper.readTree(line) as ObjectNode).apply(edit))

    @Test fun `lines stay verbatim, field order included`() {
        val line = PicassoRun1.incident("incident-6")
        val s = Snapshot.parse(manifest, listOf(line), emptyList())
        val out = s.toJson().get("incidents").get(0)
        val original = mapper.readTree(line)
        assertEquals(original, out)
        assertEquals(original.fieldNames().asSequence().toList(), out.fieldNames().asSequence().toList())
    }

    @Test fun `manifest keeps every field and schemaVersion stays the string 5`() {
        val m = PicassoRun1.snapshot(searchIds = listOf("search-1")).toJson().get("manifest")
        assertEquals(mapper.readTree(manifest), m)
        assertTrue(m.get("schemaVersion").isTextual)
    }

    @Test fun `schemaVersions 6 and 7 are read as 5 is, other versions are rejected`() {
        val search = listOf(PicassoRun1.search("search-1"))
        for (known in listOf("6", "7")) {
            val m = manifest.replace("\"schemaVersion\":\"5\"", "\"schemaVersion\":\"$known\"")
            assertEquals(known, Snapshot.parse(m, emptyList(), search).manifest.text("schemaVersion"))
        }
        for (other in listOf("4", "8")) {
            val m = manifest.replace("\"schemaVersion\":\"5\"", "\"schemaVersion\":\"$other\"")
            assertFailsWith<IllegalArgumentException> { Snapshot.parse(m, emptyList(), search) }
        }
    }

    @Test fun `a decision its export schema cannot carry is rejected - OPERATOR_CANCEL and REMEDY_APPLIED only from 7`() {
        fun decided(decision: String) = edited(PicassoRun1.incident("incident-6")) { putObject("resolution").put("decision", decision) }
        fun schema(v: String) = manifest.replace("\"schemaVersion\":\"5\"", "\"schemaVersion\":\"$v\"")
        for (v in listOf("5", "6", "7")) for (d in listOf("REWORK", "CONFIRM_DONE")) Snapshot.parse(schema(v), listOf(decided(d)), emptyList())
        for (d in listOf("OPERATOR_CANCEL", "REMEDY_APPLIED")) {
            Snapshot.parse(schema("7"), listOf(decided(d)), emptyList())
            for (v in listOf("5", "6")) assertFailsWith<IllegalArgumentException>("$d in $v") { Snapshot.parse(schema(v), listOf(decided(d)), emptyList()) }
        }
        assertFailsWith<IllegalArgumentException> { Snapshot.parse(schema("7"), listOf(decided("RECOVERED")), emptyList()) }
        val numeric = edited(PicassoRun1.incident("incident-6")) { putObject("resolution").put("decision", 1) }
        assertFailsWith<IllegalArgumentException> { Snapshot.parse(schema("7"), listOf(numeric), emptyList()) }
        // no judgement given, or a resolution without a decision key: nothing to check
        val noKey = edited(PicassoRun1.incident("incident-6")) { putObject("resolution").put("at", "2026-09-06T00:00:01Z") }
        Snapshot.parse(schema("7"), listOf(PicassoRun1.incident("incident-6"), noKey), emptyList())
    }

    @Test fun `a numeric schemaVersion is rejected`() {
        val numeric = manifest.replace("\"schemaVersion\":\"5\"", "\"schemaVersion\":5")
        assertFailsWith<IllegalArgumentException> { Snapshot.parse(numeric, emptyList(), listOf(PicassoRun1.search("search-1"))) }
    }

    @Test fun `at least one line is required`() {
        assertFailsWith<IllegalArgumentException> { Snapshot.parse(manifest, emptyList(), emptyList()) }
    }

    @Test fun `an incident line needs its digest and a search line its searchId`() {
        assertFailsWith<IllegalArgumentException> { Snapshot.parse(manifest, listOf("""{"incidentId":"x"}"""), emptyList()) }
        assertFailsWith<IllegalArgumentException> { Snapshot.parse(manifest, emptyList(), listOf("""{"outcome":"FOUND"}""")) }
    }

    @Test fun `a duplicate key is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            Snapshot.parse(manifest, emptyList(), listOf("""{"searchId":"a","searchId":"b"}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            Snapshot.parse(manifest.replace("\"runId\"", "\"schemaVersion\":\"5\",\"runId\""), emptyList(), listOf(PicassoRun1.search("search-1")))
        }
    }

    @Test fun `trailing tokens are rejected`() {
        assertFailsWith<IllegalArgumentException> { Snapshot.parse(manifest, emptyList(), listOf(PicassoRun1.search("search-1") + " {}")) }
        assertFailsWith<IllegalArgumentException> { Snapshot.parse("$manifest garbage", emptyList(), listOf(PicassoRun1.search("search-1"))) }
    }

    @Test fun `the constructor copies the nodes it is given`() {
        val m = mapper.readTree(manifest) as ObjectNode
        val line = mapper.readTree(PicassoRun1.search("search-1")) as ObjectNode
        val s = Snapshot(m, emptyList(), listOf(line))
        line.put("outcome", "MUTATED")
        m.put("schemaVersion", "9")
        assertEquals("FOUND", s.searches[0].text("outcome"))
        assertEquals("5", s.manifest.text("schemaVersion"))
    }

    @Test fun `a line that is not an object is rejected`() {
        assertFailsWith<IllegalArgumentException> { Snapshot.parse(manifest, emptyList(), listOf("[1,2]")) }
    }
}
