package dev.picasso.recovery.host

import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WatcherConfigTest {
    private val sep = File.pathSeparator
    private val on = "RECOVERY_PICASSO" to "mock"

    @Test fun `exports, carry, interval and policy come from the environment`() {
        val c = WatcherConfig.fromEnv(mapOf(
            on, "RECOVERY_WATCH_EXPORTS" to "a${sep}b", "RECOVERY_WATCH_CARRY" to "c", "RECOVERY_WATCH_INTERVAL_MS" to "500", "RECOVERY_EPISODE_POLICY" to "p.yaml",
        ))
        assertEquals(listOf(Path.of("a"), Path.of("b")), c.exports)
        assertEquals(Path.of("c"), c.carry)
        assertEquals(500, c.intervalMs)
        assertEquals(Path.of("p.yaml"), c.policyPath)
    }

    @Test fun `defaults`() {
        val c = WatcherConfig.fromEnv(mapOf(on, "RECOVERY_WATCH_EXPORTS" to "a"))
        assertNull(c.carry)
        assertEquals(2000, c.intervalMs)
        assertEquals(Path.of("policy/active.yaml"), c.policyPath)
    }

    @Test fun `the real window is episode mode too - the watcher starts with RECOVERY_PICASSO=picasso`() =
        assertEquals(listOf(Path.of("a")), WatcherConfig.fromEnv(mapOf("RECOVERY_PICASSO" to "picasso", "RECOVERY_WATCH_EXPORTS" to "a")).exports)

    @Test fun `what is wrong stops the start`() {
        listOf(
            mapOf(on),
            mapOf(on, "RECOVERY_WATCH_EXPORTS" to ""),
            mapOf(on, "RECOVERY_WATCH_EXPORTS" to "a$sep"),
            mapOf(on, "RECOVERY_WATCH_EXPORTS" to "a", "RECOVERY_WATCH_INTERVAL_MS" to "50"),
            mapOf(on, "RECOVERY_WATCH_EXPORTS" to "a", "RECOVERY_WATCH_INTERVAL_MS" to "x"),
            mapOf(on, "RECOVERY_WATCH_EXPORTS" to "a", "RECOVERY_WATCH_CARRY" to " "),
        ).forEach { assertFailsWith<IllegalArgumentException>(it.toString()) { WatcherConfig.fromEnv(it) } }
    }

    @Test fun `outside episode mode the watcher does not start - no worker would run what it opens`() {
        assertFailsWith<IllegalArgumentException> { WatcherConfig.fromEnv(mapOf("RECOVERY_WATCH_EXPORTS" to "a")) }
        assertFailsWith<IllegalArgumentException> { WatcherConfig.fromEnv(mapOf("RECOVERY_PICASSO" to "off", "RECOVERY_WATCH_EXPORTS" to "a")) }
        assertFailsWith<IllegalArgumentException> { WatcherConfig.fromEnv(mapOf("RECOVERY_PICASSO" to "real", "RECOVERY_WATCH_EXPORTS" to "a")) }
    }
}
