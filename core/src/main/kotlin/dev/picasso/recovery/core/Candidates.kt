package dev.picasso.recovery.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.format.DateTimeParseException

enum class CandidateKind { APPROVE_REMEDY, CHOOSE_SOURCE, OPERATOR_DECISION, ESCALATE }

/** picasso's failure class of a rework not issued because its first-issue recheck failed (export schema 9, §15.323). */
const val REWORK_RECHECK = "REWORK_RECHECK"

/** What an operator may decide about an unresolved unit. The enum NAME is the wire value (id material and `ref.decision`). */
enum class OperatorDecision { CONFIRM_DONE, REWORK }

/**
 * One option the diagnosis may point at (design §9.1, contract 0.6 §3.1). The id is derived from CONTENT, never from
 * list position, so the same action on the same target keeps its id across re-diagnoses. picasso's searchId /
 * incidentId repeat across runs of the same seed, so they are never id material — only traced in [ref].
 *
 * Value semantics: two candidates are equal when id, kind, [ref] and [sawSkillTypes] are equal.
 */
class Candidate private constructor(
    val kind: CandidateKind,
    ref: Map<String, String?>?,
    /** Already-encoded id segments (see [encodePart]); joined with `:` after the kind. */
    idParts: List<String>,
    sawSkillTypes: List<String>?,
) {
    val ref: Map<String, String?>? = ref?.let { LinkedHashMap(it) }
    val sawSkillTypes: List<String>? = sawSkillTypes?.toList()

    val candidateId: String =
        if (kind == CandidateKind.ESCALATE) ESCALATE_ID else (listOf(kind.name) + idParts).joinToString(":")

    /**
     * Execution class (design §9.1): moves the field, so it is withheld while anything is unknown. Fail-closed: of the
     * operator decisions only an explicit CONFIRM_DONE is outside the class, so an unexpected value counts as execution.
     */
    val physicalAction: Boolean
        get() = when (kind) {
            CandidateKind.APPROVE_REMEDY, CandidateKind.CHOOSE_SOURCE -> true
            CandidateKind.OPERATOR_DECISION -> ref?.get("decision") != OperatorDecision.CONFIRM_DONE.name
            CandidateKind.ESCALATE -> false
        }

    fun toJson(): ObjectNode = mapper.createObjectNode().apply {
        put("candidateId", candidateId)
        put("kind", kind.name)
        if (ref == null) putNull("ref") else putObject("ref").apply { ref.forEach { (k, v) -> put(k, v) } }
        if (kind == CandidateKind.APPROVE_REMEDY) putArray("sawSkillTypes").apply { sawSkillTypes.orEmpty().forEach { add(it) } }
    }

    /** Every string the candidate carries can be hashed (JCS refuses lone surrogates). */
    internal fun wellFormed(): Boolean =
        (ref?.values.orEmpty().filterNotNull() + sawSkillTypes.orEmpty()).all(Jcs::isWellFormed)

    override fun equals(other: Any?): Boolean =
        this === other || (other is Candidate && candidateId == other.candidateId && kind == other.kind &&
            ref == other.ref && sawSkillTypes == other.sawSkillTypes)

    override fun hashCode(): Int = listOf(candidateId, kind, ref, sawSkillTypes).hashCode()

    override fun toString(): String = "Candidate($candidateId)"

    companion object {
        const val ESCALATE_ID = "ESCALATE"

        val ESCALATE = Candidate(CandidateKind.ESCALATE, null, emptyList(), null)

        fun approveRemedy(robotId: String, jobOrderId: String, skills: List<String>, searchId: String) = Candidate(
            CandidateKind.APPROVE_REMEDY,
            linkedMapOf("robotId" to robotId, "jobOrderId" to jobOrderId, "searchId" to searchId),
            listOf(encodePart(robotId), encodePart(jobOrderId), skills.joinToString("+") { encodePart(it).replace("+", "%2B") }),
            skills,
        )

        /**
         * [missingSource] is picasso's `source` (the slot that ran out); [alternative] is the place to pick instead.
         * Only the alternative is id material; the missing slot is carried for the operator card.
         */
        fun chooseSource(jobOrderId: String, material: String, missingSource: String?, alternative: String, searchId: String) = Candidate(
            CandidateKind.CHOOSE_SOURCE,
            linkedMapOf(
                "jobOrderId" to jobOrderId, "material" to material, "missingSource" to missingSource,
                "alternative" to alternative, "searchId" to searchId,
            ),
            listOf(jobOrderId, material, alternative).map(::encodePart),
            null,
        )

        fun operatorDecision(executionId: String, unitId: String, decision: OperatorDecision) = Candidate(
            CandidateKind.OPERATOR_DECISION,
            linkedMapOf("executionId" to executionId, "unitId" to unitId, "decision" to decision.name),
            listOf(executionId, unitId, decision.name).map(::encodePart),
            null,
        )

        /** `%` first, then `:`, so the encoding stays injective. Skill lists additionally encode `+` (their separator). */
        private fun encodePart(s: String): String = s.replace("%", "%25").replace(":", "%3A")
    }
}

/**
 * Pure projection of picasso lines into candidates (design §9.1). ESCALATE is always present; with any unknown
 * (from the snapshot or passed by the caller) every execution-class candidate is withheld. Result ordered by id;
 * an id seen twice keeps its first occurrence — and so the first line's `ref`: two SOURCE_MISSING lines with the same job,
 * material and alternative keep the first line's `missingSource` and `searchId`. A line that lacks a field its candidate needs offers no candidate
 * (fail-safe: fewer options, never an exception; ESCALATE remains).
 */
fun projectCandidates(snapshot: Snapshot, unknowns: List<Unknown>): List<Candidate> {
    val byId = LinkedHashMap<String, Candidate>()
    // A candidate whose strings cannot be hashed is withheld (fail-safe) rather than breaking candidatesVersion.
    fun add(c: Candidate) { if (c.wellFormed()) byId.putIfAbsent(c.candidateId, c) }

    add(Candidate.ESCALATE)
    val reworked = reworkedAttempts(snapshot)
    for (s in snapshot.searches) {
        val searchId = requireNotNull(s.text("searchId"))
        val robotId = s.text("robotId")
        val jobOrderId = s.text("jobOrderId")
        if (reworked.any { it.covers(s) }) continue
        when (s.text("outcome")) {
            "FOUND" -> {
                // Every step must name its skill: picasso approves only if its steps ⊆ sawSkillTypes, so a partial list
                // would be a candidate that can never be approved as seen. No complete list, no candidate.
                val steps = s.path("steps")
                val skills = steps.mapNotNull { it.text("skillType") }
                if (robotId != null && jobOrderId != null && skills.isNotEmpty() && skills.size == steps.size())
                    add(Candidate.approveRemedy(robotId, jobOrderId, skills, searchId))
            }
            "SOURCE_MISSING" -> {
                // alternatives null = the cell was not asked; [] = the cell answered "no place". Neither offers a source.
                val material = s.text("material")
                val alts = s.get("alternatives")
                if (jobOrderId != null && material != null && alts != null && alts.isArray) alts.filter { it.isTextual }.forEach {
                    add(Candidate.chooseSource(jobOrderId, material, s.text("source"), it.textValue(), searchId))
                }
            }
            else -> Unit
        }
    }
    for (i in snapshot.incidents) {
        val unresolved = i.get("unresolved")?.let { it.isBoolean && it.booleanValue() } ?: false
        // Any judgement or step a person already put on the unit — REWORK, CONFIRM_DONE, and since export schema 7
        // OPERATOR_CANCEL or REMEDY_APPLIED — means no decision is offered. REMEDY_APPLIED is not "recovered": a person
        // put a remedy in front of the unit, and what came of it arrives as later lines; a person checks (ESCALATE).
        val undecided = i.get("resolution")?.isNull ?: true
        // Only a unit the equipment MATCHED has grounds for either decision: picasso's resolve raises the evidence
        // only then, and deciding DONE on an unmatched unit risks a missing part. Otherwise a person checks on site
        // (ESCALATE) — neither CONFIRM_DONE nor REWORK is offered.
        val matched = i.text("verification") == "MATCHED"
        val executionId = i.text("executionId")
        val unitId = i.text("unitId")
        // A REWORK_RECHECK incident (export schema 9, picasso §15.323) is no failure of the robot: a rework a person gave
        // was not issued because the hand or a place had changed just before. Its recovery is a person reworking again
        // at picasso — no decision is offered here, and picasso writes no remedy search for it. picasso marks it
        // `unresolved: false`; the class is checked as well, so a line that says otherwise still offers nothing.
        val recheck = i.text("failureClass") == REWORK_RECHECK
        if (unresolved && undecided && matched && !recheck && executionId != null && unitId != null)
            OperatorDecision.entries.forEach { add(Candidate.operatorDecision(executionId, unitId, it)) }
    }
    val blocked = unknowns.isNotEmpty()
    return byId.values.filterNot { blocked && it.physicalAction }.sortedBy { it.candidateId }
}

/**
 * An attempt a person already reworked (export schema 9): a `REWORK` decision on an incident, cut at the decision's `at`;
 * or a REWORK_RECHECK incident — a rework not issued — cut at the incident's `at`. A remedy search line has no unit or
 * attempt, only the robot and the order, so a search of that robot and order made at or before the cut (both picasso's
 * virtual clock) is that attempt's search: it offers nothing (a person is reissuing the unit). A later search, of a later
 * failure, is not covered. A time that cannot be read covers the search (fail-safe: fewer options).
 */
private class ReworkedAttempt(val robotId: String, val jobOrderId: String, val cut: Instant?) {
    fun covers(search: JsonNode): Boolean {
        if (search.text("robotId") != robotId || search.text("jobOrderId") != jobOrderId) return false
        val at = instantOrNull(search.text("at"))
        return cut == null || at == null || !at.isAfter(cut)
    }
}

/** Only from export schema 9: before it a search was offered as it was, and committed histories replay unchanged. */
private fun reworkedAttempts(snapshot: Snapshot): List<ReworkedAttempt> {
    if (snapshot.manifest.text("schemaVersion") != "9") return emptyList()
    return snapshot.incidents.mapNotNull { i ->
        val robotId = i.text("robotId") ?: return@mapNotNull null
        val jobOrderId = i.text("jobOrderId") ?: return@mapNotNull null
        val resolution = i.get("resolution")
        when {
            resolution != null && resolution.isObject && resolution.text("decision") == "REWORK" ->
                ReworkedAttempt(robotId, jobOrderId, instantOrNull(resolution.text("at")))
            i.text("failureClass") == REWORK_RECHECK -> ReworkedAttempt(robotId, jobOrderId, instantOrNull(i.text("at")))
            else -> null
        }
    }
}

private fun instantOrNull(text: String?): Instant? = try {
    text?.let(Instant::parse)
} catch (e: DateTimeParseException) {
    null
}
