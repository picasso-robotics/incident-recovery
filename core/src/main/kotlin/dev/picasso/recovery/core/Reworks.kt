package dev.picasso.recovery.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * What a person's REWORK on an incident means for the episode (export schema "9", picasso §15.323; design §12
 * 구현(버전 9)). From "9" picasso takes a rework of any attempted failure, not only of a skipped pair: `resolution.decision
 * = REWORK` on an incident is a person's decision to issue that unit again. The recovery episode such incidents opened
 * has nothing left to find — no remedy is approved for a unit a person is already reissuing — so it is closed as "closed
 * by a person's rework" ([reworkClosure]). The unit's next attempt is a new incident: picasso hands it a new
 * `incidentKey` (its `attempt` is larger), so it is never folded into this one and opens (or joins) an episode of its own.
 */

/** What a schema "9" `resolution.reworkBasis` says was read when the rework was taken. */
sealed interface ReworkBasisRead {
    /** `reworkBasis` null (or no resolution): no basis — a hold's rework, a unit reverted along with the root, another step. */
    data object None : ReworkBasisRead

    /**
     * `{robotId, readAt}` with `hold`, `source` and `destination` all null: an inspection, a move or a skipped pair's move,
     * which picasso does not ask about. **No observation** — neither "confirmed" nor "reworked without checking".
     */
    data class NotObserved(val robotId: String, val readAt: String) : ReworkBasisRead

    /**
     * The hand and places were asked. [confirmed] lists only what an observed value says: an empty hand
     * (`hold.kind = HOLD_KIND_EMPTY`), a loaded source (`occupied: true`), a free destination (`occupied: false`).
     * `occupied: null` (the equipment said nothing) confirms nothing, and nothing absent is read as a confirmation.
     */
    data class Observed(val robotId: String, val readAt: String, val confirmed: List<ReworkConfirmation>) : ReworkBasisRead
}

enum class ReworkConfirmed { EMPTY_HAND, SOURCE_OCCUPIED, DESTINATION_FREE }

/**
 * One confirmation and its observation time: [observedAt] is the slot's source time when the equipment gave one, else
 * null (then the basis' `readAt` is the time). [identity] is what the source slot said it holds — null is "something whose
 * identity is not known" (an OPTIONAL source declaration), not a confirmation of the material.
 */
data class ReworkConfirmation(val what: ReworkConfirmed, val slotId: String?, val identity: String?, val observedAt: String?)

/** [line]'s rework basis; the line has passed [Snapshot.formatProblems] under schema "9". Null-safe for other lines (None). */
fun reworkBasisOf(line: JsonNode): ReworkBasisRead {
    val b = line.path("resolution").get("reworkBasis")
    if (b == null || !b.isObject) return ReworkBasisRead.None
    val robotId = b.text("robotId") ?: return ReworkBasisRead.None
    val readAt = b.text("readAt") ?: return ReworkBasisRead.None
    val hold = b.get("hold")?.takeIf { it.isObject }
    val source = b.get("source")?.takeIf { it.isObject }
    val destination = b.get("destination")?.takeIf { it.isObject }
    if (hold == null && source == null && destination == null) return ReworkBasisRead.NotObserved(robotId, readAt)
    val confirmed = buildList {
        if (hold?.text("kind") == "HOLD_KIND_EMPTY") add(ReworkConfirmation(ReworkConfirmed.EMPTY_HAND, null, null, null))
        fun slot(n: JsonNode?, occupied: Boolean, what: ReworkConfirmed) {
            val o = n?.get("occupied") ?: return
            if (o.isBoolean && o.booleanValue() == occupied) add(ReworkConfirmation(what, n.text("slotId"), n.text("identity"), n.text("observedAt")))
        }
        slot(source, occupied = true, ReworkConfirmed.SOURCE_OCCUPIED)
        slot(destination, occupied = false, ReworkConfirmed.DESTINATION_FREE)
    }
    return ReworkBasisRead.Observed(robotId, readAt, confirmed)
}

/** One incident a person reworked, as the closing record carries it. */
data class ReworkedIncident(val incidentId: String?, val incidentKey: String?, val attempt: Int?, val basis: ReworkBasisRead)

/**
 * The incidents a person reworked when the episode is closed by rework, or null when it is not: export schema "9" only
 * (before it a REWORK decided a hold or a skipped pair, read as before — and committed histories replay unchanged), at
 * least one incident line, and the latest line of every incident (`incidentKey`; a line without one is its own) decided
 * REWORK. One incident still undecided, or decided otherwise, keeps the episode going — a rework's next attempt that
 * failed again is such an incident (new key). Search lines do not hold it open: a person is reissuing the unit they were
 * searching a remedy for.
 */
fun reworkClosure(snapshot: Snapshot): List<ReworkedIncident>? {
    if (snapshot.manifest.text("schemaVersion") != "9" || snapshot.incidents.isEmpty()) return null
    val latest = LinkedHashMap<String, ObjectNode>()
    snapshot.incidents.forEachIndexed { index, line -> latest[incidentKeyOf(line)?.let { "key:$it" } ?: "line:$index"] = line }
    if (!latest.values.all { it.path("resolution").text("decision") == "REWORK" }) return null
    return latest.values.map { line ->
        val attempt = line.get("attempt")?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue()
        ReworkedIncident(line.text("incidentId"), incidentKeyOf(line), attempt, reworkBasisOf(line))
    }
}

/** The closing record's entry for [r]: what the basis confirmed and when, never more than the observed values say. */
internal fun ObjectNode.putReworked(r: ReworkedIncident) {
    put("incidentId", r.incidentId)
    put("incidentKey", r.incidentKey)
    if (r.attempt == null) putNull("attempt") else put("attempt", r.attempt)
    when (val b = r.basis) {
        ReworkBasisRead.None -> put("basis", "NONE")
        is ReworkBasisRead.NotObserved -> {
            put("basis", "NOT_OBSERVED")
            put("robotId", b.robotId)
            put("readAt", b.readAt)
        }
        is ReworkBasisRead.Observed -> {
            put("basis", "OBSERVED")
            put("robotId", b.robotId)
            put("readAt", b.readAt)
            putArray("confirmed").apply {
                b.confirmed.forEach { c ->
                    addObject().put("what", c.what.name).put("slotId", c.slotId).put("identity", c.identity).put("observedAt", c.observedAt)
                }
            }
        }
    }
}
