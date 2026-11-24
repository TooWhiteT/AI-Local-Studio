package studio.comfy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BoardMemoryTest {
    @Test fun acceptsOnlySingleDeviceCudaBoardMeasurements() {
        val gpu = """{"vram_total":34190917632,"vram_used":24568774656}"""
        fun frame(devices: String, type: String = "cuda") =
            """{"type":"crystools.monitor","data":{"device_type":"$type","gpus":[$devices]}}"""
        val reading = assertNotNull(readBoardMemory(frame(gpu)))
        assertEquals(9_622_142_976L, reading.total - reading.used)
        assertNull(readBoardMemory(frame("$gpu,$gpu")))
        assertNull(readBoardMemory(frame(gpu, "cpu")))
        assertNull(readBoardMemory(frame("""{"vram_total":20,"vram_used":21}""")))
        assertNull(readBoardMemory("""{"type":"progress","data":{"value":1}}"""))
        assertNull(readBoardMemory("invalid"))
    }
}
