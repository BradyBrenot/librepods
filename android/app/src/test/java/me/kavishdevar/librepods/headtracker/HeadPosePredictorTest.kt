package me.kavishdevar.librepods.headtracker

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HeadPosePredictorTest {

    @Test
    fun zeroOrNegativeLeadLeavesThePoseUnchanged() {
        val rv = floatArrayOf(0.2f, -0.1f, 0.05f)
        val av = floatArrayOf(1f, 2f, 3f)
        assertArrayEquals(rv, HeadPosePredictor.advance(rv, av, 0), 0f)
        assertArrayEquals(rv, HeadPosePredictor.advance(rv, av, -50), 0f)
    }

    @Test
    fun advanceAddsTheTwistTimesLead() {
        assertArrayEquals(
            floatArrayOf(0.1f, 0f, 0f),
            HeadPosePredictor.advance(
                FloatArray(3),
                floatArrayOf(1f, 0f, 0f),
                100,
            ),
            1e-4f,
        )
        assertArrayEquals(
            floatArrayOf(0.3f, 0f, 0f),
            HeadPosePredictor.advance(
                floatArrayOf(0.2f, 0f, 0f),
                floatArrayOf(1f, 0f, 0f),
                100,
            ),
            1e-4f,
        )
    }

    @Test
    fun advanceClampsToTheMaximumLead() {
        assertArrayEquals(
            floatArrayOf(0.2f, 0f, 0f),
            HeadPosePredictor.advance(
                FloatArray(3),
                floatArrayOf(1f, 0f, 0f),
                1000,
            ),
            1e-4f,
        )
    }

    @Test
    fun rotationVectorQuaternionRoundTrip() {
        val rv = floatArrayOf(0.4f, -0.2f, 0.1f)
        val q = HeadPosePredictor.fromRotationVector(rv)
        val roundTrip = me.kavishdevar.librepods.utils.HeadTrackingParser.toRotationVector(q)
        assertArrayEquals(rv, roundTrip, 1e-4f)
        assertEquals(1f, q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z, 1e-4f)
    }
}
