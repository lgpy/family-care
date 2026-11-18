package io.github.lgpy.familycare.core.domain.circle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CareCircleIdTest {

    @Test
    fun `keeps the identifier it was created with`() {
        assertEquals("circle-1", CareCircleId("circle-1").value)
    }

    @Test
    fun `rejects a blank identifier`() {
        assertThrows(IllegalArgumentException::class.java) {
            CareCircleId("   ")
        }
    }
}
