package dev.picasso.recovery.runtime

import dev.picasso.recovery.core.Snapshot
import dev.picasso.recovery.core.revisionRemovalProblems
import java.nio.file.Path

/** The two line kinds of a picasso export that are symptoms (design §12). [name] is the `SymptomSignal.kind`. */
enum class ExportKind(val file: BundleFile) {
    INCIDENT(BundleFile("incidents.jsonl", "incidents", "incidentId")),
    SEARCH(BundleFile("remedy-searches.jsonl", "remedySearches", "searchId")),
}

/**
 * picasso's revision-removal register (export schema "10" on, [Snapshot.FROM_10], picasso §15.329): read and checked with
 * the bundle, never a symptom — not an [ExportKind], so no line of it becomes a signal, an episode or a recurrence
 * (design §12 구현(버전 10)). A line is named by its execution and `toVersion` (picasso takes one version of one execution
 * once); the execution id counts within the manifest's `instanceId`. An older bundle has no such file and is read as before.
 */
val REVISION_REMOVALS = BundleFile("revision-removals.jsonl", "revisionRemovals", "executionId and toVersion") { node ->
    val execution = node.get("executionId")?.takeIf { it.isTextual }?.textValue()?.takeIf { it.isNotBlank() }
    val to = node.get("toVersion")?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue()
    if (execution == null || to == null) null else "$execution@$to"
}

/**
 * A picasso export as symptoms — the one place that says how a line becomes a `symptom` signal, shared by the dev CLI
 * and the watcher so both send the same `eventId` for the same line (design §7.2 구현(B3c), §12).
 */
object ExportSymptoms {
    /**
     * picasso `LedgerExport.SCHEMA_VERSION`s this reader knows: "5" (picasso `8ab5400`), "6" (picasso `8f0cc04`, same
     * incident-line fields; a SIGNAL route and a new digest shape, neither of which this reader interprets), "7"
     * (picasso `fde49d1`, same fields; two more `resolution.decision` values) and "8" (picasso fd31c6f, §15.322: incident
     * identity, `decidedBy`, mission version and site settings, three more search outcomes, the manifest's `instanceId`;
     * same digests) and "9" (picasso f1dfd94, §15.323: `resolution.reworkBasis`, the key on every given resolution; same
     * digests) and "10" (picasso 6c4143d, §15.329: the same incident and search lines, a third register
     * [REVISION_REMOVALS] and its `counts.revisionRemovals`; same digests). The same list as the diagnosis snapshot's
     * ([Snapshot.SCHEMA_VERSIONS]).
     */
    val SCHEMA_VERSIONS = Snapshot.SCHEMA_VERSIONS

    /**
     * Incidents first, then searches: the order picasso writes them, and the order the watcher sends them. A bundle the
     * snapshot's per-version checks refuse ([Snapshot.formatProblems]: a `resolution.decision` its schema cannot carry,
     * a schema 8 field missing or of the wrong shape, a schema 9 `reworkBasis` out of shape) is broken: the format
     * changed under a known version, and no episode could diagnose that line anyway. From schema 10 the bundle's
     * [REVISION_REMOVALS] lines are read too (bounded by `counts.revisionRemovals`, which must be there) and a line out of
     * shape ([revisionRemovalProblems]) is broken the same way; they are in [BundleRead.Ready.lines] under the register's
     * name, and no reader of symptoms looks at them.
     */
    fun read(dir: Path): BundleRead {
        val read = Bundles.read(dir, "runId", SCHEMA_VERSIONS, ::filesOf)
        if (read !is BundleRead.Ready) return read
        val manifest = strictJson.readTree(read.manifestJson)
        val schema = manifest.path("schemaVersion").textValue()
        val problems = Snapshot.formatProblems(
            schema, manifest,
            read.lines[ExportKind.INCIDENT.file.name].orEmpty().map { it.node },
            read.lines[ExportKind.SEARCH.file.name].orEmpty().map { it.node },
        ).toMutableList()
        for (line in read.lines[REVISION_REMOVALS.name].orEmpty())
            problems += revisionRemovalProblems(line.node).map { "${REVISION_REMOVALS.name} line ${line.position}: $it" }
        return if (problems.isEmpty()) read else BundleRead.Broken("$dir: schema $schema: ${problems.joinToString("; ")}")
    }

    /** The files a bundle of export schema [schema] has: the symptom kinds, and from "10" the revision-removal register. */
    fun filesOf(schema: String): List<BundleFile> =
        ExportKind.entries.map { it.file } + (if (schema in Snapshot.FROM_10) listOf(REVISION_REMOVALS) else emptyList())

    /** `search:<runId>:<searchId>`, `incident:<runId>:<digest>`. Null for an incident without a digest: never guessed. */
    fun eventId(kind: ExportKind, runId: String, line: BundleLine): String? = when (kind) {
        ExportKind.SEARCH -> "search:$runId:${line.id}"
        ExportKind.INCIDENT -> line.node.get("digest")?.takeIf { it.isTextual }?.textValue()?.takeIf { it.isNotBlank() }
            ?.let { "incident:$runId:$it" }
    }

    /** The `symptom` signal for [line]: the line and the manifest exactly as written (design §7.1 구현(B3b)). */
    fun symptom(kind: ExportKind, export: BundleRead.Ready, line: BundleLine, observedAtMillis: Long): SymptomSignal? =
        eventId(kind, export.runId, line)?.let { SymptomSignal(it, kind.name, line.text, observedAtMillis, export.manifestJson) }
}
