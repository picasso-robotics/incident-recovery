package dev.picasso.recovery.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkerStartTest {
    @Test
    fun `off is refused, since incident-recovery has nothing else to run`() {
        val e = assertFailsWith<IllegalStateException> { workerConfig(emptyMap()) }
        assertEquals(true, e.message!!.contains("RECOVERY_PICASSO"))
        val explicit = assertFailsWith<IllegalStateException> { workerConfig(mapOf("RECOVERY_PICASSO" to "off")) }
        assertEquals(true, explicit.message!!.contains("RECOVERY_PICASSO"))
    }

    @Test
    fun `mock is accepted`() {
        workerConfig(mapOf("RECOVERY_PICASSO" to "mock"))
    }
}
