package dev.picasso.recovery.host

import kotlin.system.exitProcess

/**
 * Development CLI (design §7.2): the watcher is what carries symptoms in production.
 *   open --export <dir> (--search <searchId> | --incident <incidentId>) [--key <correlationKey>]
 *                          — open an episode from one picasso export line (signalWithStart)
 *   agent-off (<workflowId> | --all)  — switch an episode's agent off, or every open episode's
 */
fun main(args: Array<String>) {
    when (args.getOrNull(0)) {
        "open" -> doOpen(args.drop(1))
        "agent-off" -> doAgentOff(args.drop(1))
        null -> usageError("command required (open|agent-off)")
        else -> usageError("unknown command: ${args[0]} (expected open|agent-off)")
    }
}

// ---------------------------------------------------------------------------
// open --export <dir> (--search <searchId> | --incident <incidentId>) [--key <correlationKey>]
// agent-off (<workflowId> | --all)
// Arguments are checked before Temporal is reached, so a bad argument is a usage error, never a half-sent signal.
// A refusal (IllegalArgumentException) is a usage error; a failure to read the export or to reach Temporal is an ERROR.
// ---------------------------------------------------------------------------
private fun doOpen(rest: List<String>) {
    val s = try { EpisodeCli.symptom(EpisodeCli.parseOpen(rest.toTypedArray())) }
            catch (e: IllegalArgumentException) { usageError(e.message ?: "open: invalid arguments") }
            catch (e: Exception) { episodeFailed("open", e) }
    try { EpisodeCli.open(s) } catch (e: Exception) { episodeFailed("open", e) }
    println("opened ${s.workflowId} eventId=${s.signal.eventId}")
}

private fun doAgentOff(rest: List<String>) {
    val target = try { EpisodeCli.agentOffTarget(rest) }
                 catch (e: IllegalArgumentException) { usageError(e.message ?: "agent-off: invalid arguments") }
    when (target) {
        is EpisodeCli.AgentOffTarget.One -> {
            try { EpisodeCli.agentOff(target.workflowId) } catch (e: Exception) { episodeFailed("agent-off", e) }
            println("agent-off sent to ${target.workflowId}")
        }
        EpisodeCli.AgentOffTarget.All -> {
            val report = try { EpisodeCli.agentOffAll() } catch (e: Exception) { episodeFailed("agent-off", e) }
            println("agent-off sent to ${report.sent.size} open episode(s)")
            report.sent.forEach { println("  $it") }
            report.ended.forEach { println("ended before the signal: $it") }
            report.failed.forEach { (id, why) -> System.err.println("failed: $id: $why") }
            if (report.failed.isNotEmpty()) exitProcess(1)
        }
    }
}

private fun episodeFailed(sub: String, e: Exception): Nothing {
    System.err.println("ERROR: episode $sub: ${e.message}")
    exitProcess(1)
}

private fun usageError(msg: String): Nothing {
    System.err.println("ERROR: $msg")
    System.err.println()
    System.err.println("Usage:")
    System.err.println("  open --export <dir> (--search <searchId> | --incident <incidentId>) [--key <correlationKey>]   (dev) signalWithStart ep:<key> with one picasso export line; key [A-Za-z0-9._:-]+, default <runId>:<id>")
    System.err.println("  agent-off (<workflowId> | --all)   (dev) send agentOff to an episode (ep:<key>); --all: every open episode (design §10.4)")
    exitProcess(1)
}
