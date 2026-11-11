package dev.picasso.recovery.host

import dev.picasso.recovery.runtime.Db
import java.sql.Connection
import java.sql.DriverManager

/**
 * The login for the episode tables only (EpisodeStore, WatchStore; design §13, plan D-lite): the runtime role of
 * scripts/db-roles.sql, so an account that writes only the episode tables can be given.
 * RECOVERY_EPISODE_DB_USER / _PASS, else Db's own login (its override, RECOVERY_DB_USER / _PASS, its defaults). The URL is Db's.
 * A blank RECOVERY_EPISODE_DB_USER is unset. One of the pair without the other is refused: never mixed with Db's login.
 */
data class EpisodeDbLogin(val user: String, val pass: String) {
    fun connect(): Connection = DriverManager.getConnection(Db.url, user, pass)

    /** The password never reaches a log line. */
    override fun toString(): String = "EpisodeDbLogin(user=$user, pass=***)"

    companion object {
        fun fromEnv(env: Map<String, String>): EpisodeDbLogin {
            val user = env["RECOVERY_EPISODE_DB_USER"]?.takeIf { it.isNotBlank() }
            val pass = env["RECOVERY_EPISODE_DB_PASS"]
            if (user != null) {
                requireNotNull(pass) { "RECOVERY_EPISODE_DB_USER is set without RECOVERY_EPISODE_DB_PASS" }
                return EpisodeDbLogin(user, pass)
            }
            require(pass == null) { "RECOVERY_EPISODE_DB_PASS is set without RECOVERY_EPISODE_DB_USER" }
            return EpisodeDbLogin(Db.user, Db.pass)   // exactly Db's login (Db reads its override and env)
        }
    }
}
