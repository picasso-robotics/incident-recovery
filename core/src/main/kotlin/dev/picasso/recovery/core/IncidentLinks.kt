package dev.picasso.recovery.core

import com.fasterxml.jackson.databind.JsonNode

/**
 * What an incident line says about the hold it is part of (export schema "8" and later, picasso §15.322; design §12
 * 구현(판 8)). Schema "9" carries the same fields.
 * The episode-opening choice reads it: lines with the same `incidentKey` are one incident, in the episode that is
 * running and in one that has ended. `attempt` is never read here — it is recurrence material, not a link (picasso
 * hands out a new key when a reworked unit breaks again, and one attempt can open two incidents).
 */
sealed interface IncidentLink {
    /** Export schema before "8" ([Snapshot.FROM_8]), or a search line: the line says nothing about other incidents. */
    data object NotCarried : IncidentLink

    /** The opaque `incidentKey` (never taken apart). [reopened]: `openingKind` REOPENED, a picasso restart reopened it. */
    data class Keyed(val key: String, val reopened: Boolean) : IncidentLink

    /**
     * `incidentKey` null on a REOPENED line: a hold reopened from a judgement record older than the key, so which
     * incident it continues is not known. Never guessed from the run, unit or class — and it stays unknown after later
     * restarts (each restart may write one more such line for the same hold), so it is not "a new incident" either.
     */
    data object LinkUnknown : IncidentLink
}

/** [line]'s link under export schema [version]; the line has passed [Snapshot.formatProblems]. */
fun incidentLink(version: String, line: JsonNode): IncidentLink {
    if (version !in Snapshot.FROM_8) return IncidentLink.NotCarried
    val key = incidentKeyOf(line) ?: return IncidentLink.LinkUnknown
    return IncidentLink.Keyed(key, reopened = line.text("openingKind") == "REOPENED")
}

/** The line's `incidentKey` when it carries one (a non-blank string), whatever the schema. */
fun incidentKeyOf(line: JsonNode): String? = line.text("incidentKey")?.takeIf { it.isNotBlank() }
