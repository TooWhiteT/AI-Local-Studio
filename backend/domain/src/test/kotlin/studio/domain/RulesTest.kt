package studio.domain

import kotlin.test.*
import studio.domain.TaskStatus.*

class RulesTest {
    @Test fun checkedTransitions() {
        assertTrue(SUBMITTED.allows(SUCCEEDED))
        assertTrue(RUNNING.allows(CANCEL_REQUESTED))
        assertTrue(CANCEL_REQUESTED.allows(CANCELED))
        assertFalse(UNKNOWN.allows(READY))
        assertFalse(SUCCEEDED.allows(PENDING))
    }
    @Test fun detectsCycles() {
        assertFalse(acyclic(mapOf("a" to listOf("b"), "b" to listOf("a"))))
        assertTrue(acyclic(mapOf("a" to listOf("b", "c"), "b" to listOf("c"))))
    }
}
