package dev.picasso.recovery.core

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * The picasso export lines an episode diagnoses, kept VERBATIM (design §9.0): the manifest with every field picasso
 * wrote, and the incident / remedy-search lines with the episode-opening line FIRST in its list (narrator puts the
 * first of each list into its query). The checks mirror the diagnosis contract's request-reading rules (contract 0.6
 * §3), so incident-recovery never builds a request narrator would reject as a non-retryable contract violation.
 */
class Snapshot(manifest: ObjectNode, incidents: List<ObjectNode>, searches: List<ObjectNode>) {
    // Deep copies: the caller keeps no handle that could change a snapshot after its checks ran.
    val manifest: ObjectNode = manifest.deepCopy()
    val incidents: List<ObjectNode> = incidents.map { it.deepCopy() }
    val searches: List<ObjectNode> = searches.map { it.deepCopy() }

    // "Verbatim" means tree-equal with field order kept, not byte-equal: a float in a future picasso line would be
    // re-rendered by Jackson. run-1 has none, and the canonical hash (Jcs) refuses numbers outright.
    init {
        val sv = manifest.get("schemaVersion")
        require(sv != null && sv.isTextual && sv.textValue() in SCHEMA_VERSIONS) {
            "manifest.schemaVersion must be one of the strings $SCHEMA_VERSIONS, was $sv"
        }
        require(incidents.isNotEmpty() || searches.isNotEmpty()) { "a snapshot needs at least one incident or search line" }
        incidents.forEach { require(!it.text("digest").isNullOrBlank()) { "incident line without digest: ${it.get("incidentId")}" } }
        searches.forEach { require(!it.text("searchId").isNullOrBlank()) { "search line without searchId" } }
        val problems = formatProblems(sv.textValue(), manifest, incidents, searches)
        require(problems.isEmpty()) { "export schema ${sv.textValue()}: ${problems.joinToString("; ")}" }
    }

    fun toJson(): ObjectNode = mapper.createObjectNode().apply {
        set<JsonNode>("manifest", manifest.deepCopy())
        putArray("incidents").apply { incidents.forEach { add(it.deepCopy()) } }
        putArray("searches").apply { searches.forEach { add(it.deepCopy()) } }
    }

    companion object {
        /**
         * The picasso export schemas narrator reads (contract 0.6 §3): "5", "6" since narrator `376d082` (2026-10-08),
         * "7" since narrator `e030b80` (2026-11-14, narrator main `17265db`), and "8" — the same list as this
         * module's export reader. "7" (picasso §15.320, main fde49d1) keeps every field and adds two values to an
         * incident line's `resolution.decision` ([DECISIONS]). "8" (picasso §15.322, the audit export bundle,
         * `feat/audit-export-bundle` fd31c6f) adds fields ([formatProblems]); narrator's reading of "8" is on its branch
         * `chore/picasso-export-8`, not on narrator main when this was written: until it is, a schema 8 episode's
         * diagnosis fails as a contract mismatch and goes to a person (DIAGNOSIS_FAILED).
         */
        val SCHEMA_VERSIONS = setOf("5", "6", "7", "8")

        /**
         * The values an incident line's `resolution.decision` may carry in each export schema (picasso
         * `IncidentDecision`, main fde49d1): REWORK and CONFIRM_DONE in "5" and "6"; "7" adds OPERATOR_CANCEL (a person
         * closed the execution at the operator cancel entrance) and REMEDY_APPLIED (a person approved a recovery
         * proposal and the remedy was put in front of that unit — NOT "recovered": what the remedy and the unit did is
         * told by later incidents and job responses). The same table as narrator `d4df329` (`receiver/export.py`
         * `DECISIONS`), which its diagnosis contract check also applies: a value outside it is a changed format, and
         * read as if known it would reach the diagnosis as a fact.
         */
        val DECISIONS: Map<String, Set<String>> = mapOf(
            "5" to setOf("REWORK", "CONFIRM_DONE"),
            "6" to setOf("REWORK", "CONFIRM_DONE"),
            "7" to setOf("REWORK", "CONFIRM_DONE", "OPERATOR_CANCEL", "REMEDY_APPLIED"),
            "8" to setOf("REWORK", "CONFIRM_DONE", "OPERATOR_CANCEL", "REMEDY_APPLIED"),
        )

        /**
         * The remedy-search `outcome`s export schema "8" adds (picasso §15.322): the cell could not see the source a unit
         * asked to observe, the destination is taken, the cell said nothing about the destination. Answers that wait for
         * a person to check or clear the place, never a remedy to approve: picasso writes no `steps` key, and
         * [projectCandidates] offers no candidate for them (only FOUND becomes APPROVE_REMEDY). Carried verbatim into the
         * diagnosis snapshot; a line of an older schema carrying one is a changed format.
         */
        val PERSON_PLACE_OUTCOMES = setOf("SOURCE_UNOBSERVED", "DESTINATION_OCCUPIED", "DESTINATION_UNOBSERVED")

        /** `openingKind` of a schema "8" incident line: opened by this picasso instance, or reopened by its restart. */
        val OPENING_KINDS = setOf("NEW", "REOPENED")

        /**
         * Why [manifest] and the lines cannot be read as export schema [version] — empty when they can. The same checks
         * for the diagnosis snapshot and the export reader (`ExportSymptoms.read`), as narrator applies them in its
         * contract check:
         * - every schema: `resolution.decision` within [DECISIONS]; a search `outcome` of [PERSON_PLACE_OUTCOMES] only
         *   from "8", and never with a `steps` key (it is not approvable);
         * - "8" (picasso `LedgerExport`, fd31c6f): the manifest's `instanceId`; on each incident line `openingKind`
         *   ([OPENING_KINDS]), `attempt` (an integer ≥ 0), `incidentKey` and `firstOpenedAt` (keys present, null together:
         *   null is a hold reopened from an older judgement record, whose link is unknown — so only on a REOPENED line),
         *   `resolution.decidedBy {id, kind}` whenever a resolution is given, and on `intent` `missionVersion` (an integer
         *   or null) and the site settings `siteSettingsVersion` · `inDoubtGrace` · `stallWindow`, null together or present
         *   together (picasso's encoder asserts it).
         * Only the shape is checked. `incidentKey` is opaque and never taken apart; `attempt` is not read beyond this.
         */
        fun formatProblems(version: String, manifest: JsonNode, incidents: List<JsonNode>, searches: List<JsonNode>): List<String> {
            val problems = mutableListOf<String>()
            val strange = unknownDecisions(version, incidents)
            if (strange.isNotEmpty()) problems += "resolution.decision must be one of ${DECISIONS[version]}, was $strange"
            for (s in searches) {
                val outcome = s.text("outcome") ?: continue
                if (outcome !in PERSON_PLACE_OUTCOMES) continue
                val id = s.get("searchId")
                if (version != "8") problems += "search $id: outcome $outcome comes only from schema 8"
                if (s.has("steps")) problems += "search $id: outcome $outcome carries steps, it is never an approvable remedy"
            }
            if (version != "8") return problems
            if (manifest.text("instanceId").isNullOrBlank()) problems += "manifest.instanceId must be a non-blank string"
            for (i in incidents) problems += incidentProblems8(i).map { "incident ${i.get("incidentId")}: $it" }
            return problems
        }

        private fun incidentProblems8(i: JsonNode): List<String> {
            val p = mutableListOf<String>()
            val opening = i.text("openingKind")
            if (opening !in OPENING_KINDS) p += "openingKind must be one of $OPENING_KINDS, was ${i.get("openingKind")}"
            val attempt = i.get("attempt")
            if (attempt == null || !attempt.isIntegralNumber || !attempt.canConvertToInt() || attempt.intValue() < 0)
                p += "attempt must be an integer >= 0, was $attempt"
            fun nullOrText(key: String, node: JsonNode = i): Boolean =
                node.has(key) && (node.get(key).isNull || (node.get(key).isTextual && node.get(key).textValue().isNotBlank()))
            fun nullOrInt(key: String, node: JsonNode): Boolean =
                node.has(key) && (node.get(key).isNull || (node.get(key).isIntegralNumber && node.get(key).canConvertToInt()))
            if (!nullOrText("incidentKey")) p += "incidentKey must be null or a non-blank string, was ${i.get("incidentKey")}"
            if (!nullOrText("firstOpenedAt")) p += "firstOpenedAt must be null or a non-blank string, was ${i.get("firstOpenedAt")}"
            val keyNull = i.get("incidentKey")?.isNull ?: false
            val firstNull = i.get("firstOpenedAt")?.isNull ?: false
            if (keyNull != firstNull) p += "incidentKey and firstOpenedAt must be null together"
            if (keyNull && opening == "NEW") p += "a NEW incident always has an incidentKey"
            val resolution = i.get("resolution")
            if (resolution != null && resolution.isObject) {
                val by = resolution.get("decidedBy")
                if (by == null || !by.isObject || by.text("id").isNullOrBlank() || by.text("kind").isNullOrBlank())
                    p += "resolution.decidedBy must be {id, kind} when a resolution is given, was $by"
            }
            val intent = i.get("intent")
            if (intent == null || !intent.isObject) {
                p += "intent must be an object, was $intent"
            } else {
                if (!nullOrInt("missionVersion", intent)) p += "intent.missionVersion must be an integer or null, was ${intent.get("missionVersion")}"
                val settings = listOf(
                    nullOrInt("siteSettingsVersion", intent), nullOrText("inDoubtGrace", intent), nullOrText("stallWindow", intent),
                )
                if (!settings.all { it }) p += "intent.siteSettingsVersion must be an integer or null, inDoubtGrace and stallWindow strings or null"
                else if (listOf("siteSettingsVersion", "inDoubtGrace", "stallWindow").map { intent.get(it).isNull }.distinct().size != 1)
                    p += "intent.siteSettingsVersion, inDoubtGrace and stallWindow must be null together or present together"
            }
            return p
        }

        /**
         * The `resolution.decision` values in [incidents] that export schema [version] cannot carry (empty when all
         * can). A null `resolution` ("no judgement given") and a `resolution` without a `decision` key are not checked,
         * as narrator does not; a decision that is not a string is never one the schema can carry.
         */
        fun unknownDecisions(version: String, incidents: List<JsonNode>): List<String> {
            val known = DECISIONS[version].orEmpty()
            return incidents.mapNotNull { it.get("resolution") }
                .filter { it.isObject && it.has("decision") }
                .map { it.get("decision") }
                .filterNot { it.isTextual && it.textValue() in known }
                .map { it.toString() }
        }

        fun parse(manifestJson: String, incidentLines: List<String>, searchLines: List<String>): Snapshot =
            Snapshot(obj(manifestJson), incidentLines.map(::obj), searchLines.map(::obj))

        private fun obj(json: String): ObjectNode {
            val node = try {
                mapper.readTree(json)
            } catch (e: JsonProcessingException) {
                throw IllegalArgumentException("not valid JSON (${e.originalMessage}): ${json.take(60)}", e)
            }
            return node as? ObjectNode ?: throw IllegalArgumentException("not a JSON object: ${json.take(60)}")
        }
    }
}
