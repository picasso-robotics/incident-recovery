package dev.picasso.recovery.core

import com.fasterxml.jackson.databind.JsonNode

/**
 * A revision removal (export schema "10", picasso §15.329; design §12 구현(버전 10)): a revision took items out of a job
 * order and removed their **untouched** units from the execution — a `PENDING` unit not yet issued, or a `FAILED` unit
 * without a task (a plan failure, e.g. `PREDECESSOR_NOT_DONE`: skipped because its predecessor did not finish). picasso
 * refuses a revision that would remove a unit it issued, finished or holds; such a unit never appears here.
 *
 * It is **not a symptom**: no failure, no cancel, no completion, no person's judgement, and nothing to recover. So the
 * export reader checks the line's shape and carries nothing in — no `symptom` signal, no episode, no recurrence count
 * (`attempt`) — and nothing here closes an open incident. A removed skipped unit may belong to its predecessor's incident
 * or rework; its removal says the unit left the scope, not that the predecessor's incident was resolved, which stays as it
 * is. (incident-recovery counts no rework group by units: [reworkClosure] reads incidents, and a removed unit has no
 * incident line of its own, so there is nothing to subtract.)
 */

/** The states a removed unit may have had (picasso `RemovedUnit.state`): not yet issued, or a plan failure without a task. */
val REMOVED_STATES = setOf("PENDING", "FAILED")

/**
 * Why [line] cannot be read as a schema "10" `revision-removals.jsonl` line (picasso `LedgerExport.revisionRemovalLine`)
 * — empty when it can: `executionId` · `jobOrderId` · `robotId` · `at` · `wallClockAt` non-blank strings, `fromVersion`
 * · `toVersion` integers (a revision may skip versions, so their difference is not checked), `removed` a non-empty array of
 * `{unitId, state}` with `state` in [REMOVED_STATES]. Only the shape is checked; nothing of it is interpreted.
 */
fun revisionRemovalProblems(line: JsonNode): List<String> {
    val p = mutableListOf<String>()
    for (key in listOf("executionId", "jobOrderId", "robotId", "at", "wallClockAt"))
        if (line.text(key).isNullOrBlank()) p += "$key must be a non-blank string, was ${line.get(key)}"
    for (key in listOf("fromVersion", "toVersion")) {
        val n = line.get(key)
        if (n == null || !n.isIntegralNumber || !n.canConvertToInt()) p += "$key must be an integer, was $n"
    }
    val removed = line.get("removed")
    if (removed == null || !removed.isArray || removed.isEmpty) {
        p += "removed must be a non-empty array of {unitId, state}, was $removed"
    } else {
        removed.forEachIndexed { i, u ->
            if (!u.isObject || u.text("unitId").isNullOrBlank() || u.text("state") !in REMOVED_STATES)
                p += "removed[$i] must be {unitId, state} with state one of $REMOVED_STATES, was $u"
        }
    }
    return p
}
