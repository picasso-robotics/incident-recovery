package dev.picasso.recovery.host

import io.temporal.client.WorkflowClient
import io.temporal.serviceclient.WorkflowServiceStubs
import dev.picasso.recovery.runtime.DataConverterSupport
import dev.picasso.recovery.runtime.EpisodeStore
import dev.picasso.recovery.runtime.EpisodeWatcher
import dev.picasso.recovery.runtime.PicassoMode
import dev.picasso.recovery.runtime.PolicyFileReader
import dev.picasso.recovery.runtime.TemporalEpisodeSignals
import dev.picasso.recovery.runtime.WatchStore
import java.io.File
import java.nio.file.Path

/** The watcher's settings (design §12), read once at start. */
data class WatcherConfig(val exports: List<Path>, val carry: Path?, val intervalMs: Long, val policyPath: Path) {
    companion object {
        fun fromEnv(env: Map<String, String>): WatcherConfig {
            require(PicassoMode.fromEnv(env) != PicassoMode.OFF) {
                "RECOVERY_PICASSO is off: no episode worker runs, so the episodes the watcher opens would never be run or seen " +
                    "(signalWithStart still creates them; they wait)"
            }
            val exports = requireNotNull(env["RECOVERY_WATCH_EXPORTS"]) { "RECOVERY_WATCH_EXPORTS: the picasso export directories, separated by '${File.pathSeparator}'" }
                .split(File.pathSeparator)
            require(exports.isNotEmpty() && exports.none { it.isBlank() }) { "RECOVERY_WATCH_EXPORTS has an empty entry: '${env["RECOVERY_WATCH_EXPORTS"]}'" }
            val carry = env["RECOVERY_WATCH_CARRY"]?.also { require(it.isNotBlank()) { "RECOVERY_WATCH_CARRY is blank" } }
            val interval = (env["RECOVERY_WATCH_INTERVAL_MS"] ?: "2000").toLongOrNull()
                ?.takeIf { it in 100..60_000 } ?: throw IllegalArgumentException("RECOVERY_WATCH_INTERVAL_MS must be 100..60000, was '${env["RECOVERY_WATCH_INTERVAL_MS"]}'")
            return WatcherConfig(
                exports.map { Path.of(it) }, carry?.let { Path.of(it) }, interval,
                Path.of(env["RECOVERY_EPISODE_POLICY"] ?: "policy/active.yaml"),
            )
        }
    }
}

/**
 * The watcher process (design §4.2, §12): polls the exports and the carry, sends to Temporal on incident-recovery's converter,
 * keeps its cursor in Postgres. It creates the episode tables if the worker has not yet (the script is idempotent);
 * as the runtime role it only checks that the owner has (EpisodeStore.ensureSchema, plan D-lite).
 */
fun main() {
    val config = WatcherConfig.fromEnv(System.getenv())
    val login = EpisodeDbLogin.fromEnv(System.getenv())   // the episode tables' login (plan D-lite): the runtime role in production
    println("incident-recovery watcher: episode tables as DB user '${login.user}' (RECOVERY_EPISODE_DB_USER, else RECOVERY_DB_USER's login)")
    EpisodeStore { login.connect() }.ensureSchema()
    val service = WorkflowServiceStubs.newLocalServiceStubs()
    val client = WorkflowClient.newInstance(service, DataConverterSupport.clientOptions())
    val watcher = EpisodeWatcher(config.exports, config.carry, PolicyFileReader(config.policyPath), WatchStore { login.connect() }, TemporalEpisodeSignals(client))
    println("incident-recovery watcher: exports=${config.exports.map { it.toAbsolutePath() }} carry=${config.carry?.toAbsolutePath() ?: "-"} " +
        "every ${config.intervalMs} ms (log: episode_watch_log)")
    Runtime.getRuntime().addShutdownHook(Thread { service.shutdown() })
    while (true) {
        try {
            val r = watcher.pollOnce()
            if (r.opened > 0 || r.evidence > 0) println("incident-recovery watcher: opened ${r.opened}, evidence ${r.evidence}")
        } catch (e: Exception) {
            // The database or the file system failed mid-pass: nothing past the last cursor row is lost; try again.
            // A pass that fails the same way every interval (the policy parse throwing, a cursor check failing) prints
            // here each time and leaves no row in episode_watch_log — acceptable for a dev process watched by a person.
            System.err.println("incident-recovery watcher: pass failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        Thread.sleep(config.intervalMs)
    }
}
