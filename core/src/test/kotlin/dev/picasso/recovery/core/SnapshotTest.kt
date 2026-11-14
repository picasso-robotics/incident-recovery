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
        for (other in listOf("4", "10")) {
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

    // Export schema 8 (picasso §15.322, fd31c6f): the restart scene as picasso wrote it, and each field's shape.

    private val before = PicassoRestartBefore
    private val after = PicassoRestartAfter
    private fun snapshot8(incidents: List<String>, searches: List<String> = emptyList(), m: String = after.manifest) =
        Snapshot.parse(m, incidents, searches)
    private fun reopened(edit: ObjectNode.() -> Unit) = edited(after.incident("incident-1"), edit)
    private fun rejected(incident: String, why: String) {
        val e = assertFailsWith<IllegalArgumentException> { snapshot8(listOf(incident)) }
        assertTrue(why in e.message.orEmpty(), e.message)
    }

    @Test fun `the schema 8 restart scene reads as picasso wrote it - one incident key across two instances`() {
        val a = before.snapshot(listOf("incident-1"), listOf("search-1", "search-2", "search-3"))
        val b = after.snapshot(listOf("incident-1"))
        assertEquals("8", b.manifest.text("schemaVersion"))
        val first = incidentLink("8", a.incidents.single())
        val again = incidentLink("8", b.incidents.single())
        assertEquals(IncidentLink.Keyed("mw-336f1d22-234e-4617-a7ea-89609dacd917/incident-1", reopened = false), first)
        assertEquals(IncidentLink.Keyed("mw-336f1d22-234e-4617-a7ea-89609dacd917/incident-1", reopened = true), again)
        assertTrue(a.incidents.single().text("digest") != b.incidents.single().text("digest"), "the digests differ: two incidents, one key")
        assertTrue(a.manifest.text("instanceId") != b.manifest.text("instanceId"))
    }

    @Test fun `a link is read only from schema 8 - before it the same field says nothing`() {
        val line = mapper.readTree(after.incident("incident-1"))
        assertEquals(IncidentLink.NotCarried, incidentLink("7", line))
        assertEquals(IncidentLink.LinkUnknown, incidentLink("8", (line as ObjectNode).deepCopy().putNull("incidentKey")))
    }

    @Test fun `a reopened line may have no key - link unknown - but a NEW one always has one`() {
        val unknown = reopened { putNull("incidentKey"); putNull("firstOpenedAt") }
        snapshot8(listOf(unknown))
        val newWithout = edited(before.incident("incident-1")) { putNull("incidentKey"); putNull("firstOpenedAt") }
        rejected(newWithout, "a NEW incident always has an incidentKey")
        rejected(reopened { putNull("incidentKey") }, "null together")
        rejected(reopened { put("incidentKey", "") }, "incidentKey must be null or a non-blank string")
        rejected(reopened { remove("incidentKey"); remove("firstOpenedAt") }, "incidentKey must be")
    }

    @Test fun `openingKind and attempt are required in schema 8`() {
        rejected(reopened { put("openingKind", "RESTARTED") }, "openingKind must be one of")
        rejected(reopened { remove("openingKind") }, "openingKind must be one of")
        rejected(reopened { put("attempt", -1) }, "attempt must be an integer")
        rejected(reopened { put("attempt", "0") }, "attempt must be an integer")
        rejected(reopened { remove("attempt") }, "attempt must be an integer")
        snapshot8(listOf(reopened { put("attempt", 3) }))
    }

    @Test fun `a resolution carries who decided it from schema 8`() {
        val decided = reopened {
            putObject("resolution").put("decision", "REWORK").put("at", "2026-09-06T00:00:07Z").put("wallClockAt", "x")
                .putObject("decidedBy").put("id", "op-1").put("kind", "PERSON")
        }
        snapshot8(listOf(decided))
        rejected(edited(decided) { (get("resolution") as ObjectNode).remove("decidedBy") }, "resolution.decidedBy must be {id, kind}")
        rejected(edited(decided) { (get("resolution") as ObjectNode).putObject("decidedBy").put("kind", "PERSON") }, "resolution.decidedBy")
        // a schema 7 line has no decidedBy: not asked there
        val seven = after.manifest.replace("\"schemaVersion\":\"8\"", "\"schemaVersion\":\"7\"")
        Snapshot.parse(seven, listOf(edited(decided) { (get("resolution") as ObjectNode).remove("decidedBy") }), emptyList())
    }

    @Test fun `the site settings are null together or present together, and the mission version an integer or null`() {
        fun intent(edit: ObjectNode.() -> Unit) = reopened { (get("intent") as ObjectNode).edit() }
        snapshot8(listOf(intent { putNull("siteSettingsVersion"); putNull("inDoubtGrace"); putNull("stallWindow"); putNull("missionVersion") }))
        rejected(intent { putNull("stallWindow") }, "null together or present together")
        rejected(intent { remove("inDoubtGrace") }, "intent.siteSettingsVersion must be")
        rejected(intent { put("missionVersion", "1") }, "intent.missionVersion")
        rejected(intent { remove("missionVersion") }, "intent.missionVersion")
        rejected(reopened { putNull("intent") }, "intent must be an object")
    }

    @Test fun `the manifest of schema 8 names its instance`() {
        val line = listOf(after.incident("incident-1"))
        val e = assertFailsWith<IllegalArgumentException> {
            snapshot8(line, m = after.manifest.replace("\"instanceId\":\"mw-fc2e0beb-2fcb-4816-8029-6f07cb9624fa\",", ""))
        }
        assertTrue("manifest.instanceId" in e.message.orEmpty(), e.message)
    }

    @Test fun `the three place outcomes come only from schema 8, never with steps`() {
        val searches = listOf("search-1", "search-2", "search-3").map(before::search)
        assertEquals(
            listOf("SOURCE_UNOBSERVED", "DESTINATION_OCCUPIED", "DESTINATION_UNOBSERVED"),
            before.snapshot(searchIds = listOf("search-1", "search-2", "search-3")).searches.map { it.text("outcome") },
        )
        val seven = before.manifest.replace("\"schemaVersion\":\"8\"", "\"schemaVersion\":\"7\"")
        for (s in searches) {
            val e = assertFailsWith<IllegalArgumentException> { Snapshot.parse(seven, emptyList(), listOf(s)) }
            assertTrue("comes only from schema 8" in e.message.orEmpty(), e.message)
        }
        val stepped = edited(searches[0]) { putArray("steps").addObject().put("skillType", "pick_place") }
        val e = assertFailsWith<IllegalArgumentException> { Snapshot.parse(before.manifest, emptyList(), listOf(stepped)) }
        assertTrue("never an approvable remedy" in e.message.orEmpty(), e.message)
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
