package dev.picasso.recovery.core

/** Verbatim lines of one picasso narrator-handoff run (copied into test resources; the picasso repo is read-only). */
class PicassoRun(private val dir: String) {
    constructor(run: Int) : this("run-$run")

    private fun res(name: String): String =
        requireNotNull(javaClass.getResource("/picasso/$dir/$name")) { "missing test resource $dir/$name" }
            .readText(Charsets.UTF_8)

    val manifest: String get() = res("manifest.json")
    private val incidents: List<String> get() = res("incidents.jsonl").lines().filter { it.isNotBlank() }
    private val searches: List<String> get() = res("remedy-searches.jsonl").lines().filter { it.isNotBlank() }
    val searchCount: Int get() = searches.size
    val incidentCount: Int get() = incidents.size

    fun incident(id: String): String = incidents.single { mapper.readTree(it).text("incidentId") == id }
    fun search(id: String): String = searches.single { mapper.readTree(it).text("searchId") == id }

    fun snapshot(incidentIds: List<String> = emptyList(), searchIds: List<String> = emptyList()): Snapshot =
        Snapshot.parse(manifest, incidentIds.map(::incident), searchIds.map(::search))
}

// The file and this val keep their run-1 names on purpose: ~50 call sites read `PicassoRun1.x`, and renaming them is churn.
/** Run 1, which the four contract fixtures and most unit tests use. */
val PicassoRun1 = PicassoRun(1)

/** picasso's restart scene, export schema 8 (§15.322): run-5a's NEW incident, reopened in run-5b with the same incidentKey. */
val PicassoRestartBefore = PicassoRun("schema-8/run-5a")
val PicassoRestartAfter = PicassoRun("schema-8/run-5b")
