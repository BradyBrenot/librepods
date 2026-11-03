package me.kavishdevar.librepods.headtracker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI

class UhidHeadTrackerTest {

    @Test
    fun bluetoothUuidUsesDisplayOrderMac() {
        val uuid = UhidHeadTracker.bluetoothUuidForMac("AA:BB:CC:DD:EE:FF")
        assertArrayEquals(
            byteArrayOf(
                0, 0, 0, 0, 0, 0, 0, 0,
                'B'.code.toByte(), 'T'.code.toByte(),
                0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(),
                0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
            ),
            uuid,
        )
    }

    @Test
    fun inputReportEncodesRotationVectorAndAngularVelocity() {
        val report = UhidHeadTracker.encodeInputReport(
            floatArrayOf((PI / 2).toFloat(), 0f, 0f),
            floatArrayOf(32f, 0f, -32f),
            7,
        )
        assertEquals(14, report.size)
        assertEquals(0x01, report[0].toInt())
        // PI/2 * 32767/PI = 16383.5 -> 16384 (0x4000 LE)
        assertEquals(0x00, report[1].toInt())
        assertEquals(0x40, report[2].toInt())
        // 32 rad/s is the descriptor maximum -> 32767 (0x7FFF LE)
        assertEquals(0xFF.toByte(), report[7])
        assertEquals(0x7F, report[8].toInt())
        // -32 rad/s -> -32767 (0x8001 LE)
        assertEquals(0x01, report[11].toInt())
        assertEquals(0x80.toByte(), report[12])
        assertEquals(7, report[13].toInt())
    }

    @Test
    fun inputReportClampsOutOfRangeValues() {
        val report = UhidHeadTracker.encodeInputReport(
            floatArrayOf(PI.toFloat() * 2f, -PI.toFloat() * 2f, 0f),
            floatArrayOf(100f, -100f, 0f),
            0xFF,
        )
        // Both rotation components clamp to the signed 16-bit logical range.
        assertEquals(0xFF.toByte(), report[1])
        assertEquals(0x7F, report[2].toInt())
        assertEquals(0x01, report[3].toInt())
        assertEquals(0x80.toByte(), report[4])
        assertEquals(0xFF.toByte(), report[7])
        assertEquals(0x7F, report[8].toInt())
        assertEquals(0x01, report[9].toInt())
        assertEquals(0x80.toByte(), report[10])
        assertEquals(0xFF.toByte(), report[13])
    }
}
