package dev.picasso.recovery.runtime

import java.sql.Connection
import java.sql.DriverManager

/** Test code can mutate these to point at a Testcontainers instance. */
object DbConnectionOverride {
    var url: String? = null
    var user: String? = null
    var pass: String? = null
}

object Db {
    val url: String get() = DbConnectionOverride.url ?: System.getenv("RECOVERY_DB_URL") ?: "jdbc:postgresql://localhost:15433/incident_recovery"
    val user: String get() = DbConnectionOverride.user ?: System.getenv("RECOVERY_DB_USER") ?: "incident_recovery"
    val pass: String get() = DbConnectionOverride.pass ?: System.getenv("RECOVERY_DB_PASS") ?: "incident_recovery"

    fun connect(): Connection = DriverManager.getConnection(url, user, pass)
}
