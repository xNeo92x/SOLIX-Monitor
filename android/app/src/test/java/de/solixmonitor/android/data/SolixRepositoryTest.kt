package de.solixmonitor.android.data

import kotlin.test.Test
import kotlin.test.assertEquals

class SolixRepositoryTest {
    @Test
    fun decodesSignedBigEndianValues() {
        assertEquals(300, SolixRepository.int32(intArrayOf(0x0000, 0x012C), 100, 100))
        assertEquals(-300, SolixRepository.int32(intArrayOf(0xFFFF, 0xFED4), 100, 100))
    }

    @Test
    fun decodesUnsignedBigEndianValues() {
        assertEquals(4_294_967_295L, SolixRepository.uint32(intArrayOf(0xFFFF, 0xFFFF), 100, 100))
    }

    @Test
    fun decodesRegisterStrings() {
        assertEquals("A17X8", SolixRepository.decodeString(intArrayOf(0x4131, 0x3758, 0x3800)))
    }
}
