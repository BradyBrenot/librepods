package me.kavishdevar.librepods.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class HeadTrackingParserTest {

    // AirPods 4 ANC (A3056), captured 2026-11-03. The blob carries the Q15 quaternion
    // (-2501, -20513, -1); MAC addresses are not part of the sample.
    private val singleSamplePacket = hex(
        "04 00 04 00 17 00 00 00 10 00 45 00 08 DD 07 10 03 3A 3E 08 0E 1A 3A " +
            "01 30 7E 99 A9 E0 05 00 00 03 00 43 9B DA B5 39 01 00 00 00 3B F6 " +
            "DF AF FF FF FF FF FB FF FB FF 68 83 03 00 00 00 01 00 00 00 00 00 " +
            "20 FC 78 00 30 FF 69 F8 2D 37 27 1F 00 00"
    )

    // Same stream, still in the zero-attitude warm-up window.
    private val warmupPacket = hex(
        "04 00 04 00 17 00 00 00 10 00 45 00 08 DC 07 10 03 3A 3E 08 0E 1A 3A " +
            "01 F0 9F 32 A7 E0 05 00 00 03 00 42 33 3D B5 39 01 00 00 00 00 00 " +
            "00 00 00 00 00 00 FC FF FE FF 68 83 03 00 00 00 20 FC 79 00 2E 03 " +
            "20 FC 78 00 31 FF 71 1A 88 37 1F 1F 00 00"
    )

    private val sampleBlob: ByteArray
        get() = singleSamplePacket.copyOfRange(23, 23 + 58)

    @Test
    fun parsesSingleAirPods4Sample() {
        val samples = HeadTrackingParser.parse(singleSamplePacket)
        assertEquals(1, samples.size)

        val sample = samples.single()
        assertArrayEquals(intArrayOf(-2501, -20513, -1), sample.attitudeRaw)
        assertArrayEquals(intArrayOf(-1, -5, -5), sample.rotationRate)
        assertArrayEquals(intArrayOf(1, 0, 0), sample.userAcceleration)
        assertArrayEquals(intArrayOf(-992, 120, -208), sample.gravity)
        assertEquals(6462476222000L, sample.timestampNanos)
        assertEquals(7975L, sample.counter)
        assertEquals(-2501 / 32768f, sample.quaternion.x, 1e-6f)
        assertEquals(-20513 / 32768f, sample.quaternion.y, 1e-6f)
        assertEquals(-1 / 32768f, sample.quaternion.z, 1e-6f)
        assertQuaternionNormalized(sample.quaternion)
    }

    @Test
    fun skipsZeroAttitudeWarmupSample() {
        assertTrue(HeadTrackingParser.parse(warmupPacket).isEmpty())
    }

    @Test
    fun acceptsValidNearIdentityAttitude() {
        val samples = HeadTrackingParser.parse(packet(1, command(14, blob(1000, 0, 0))))
        assertEquals(1, samples.size)
        assertQuaternionNormalized(samples.single().quaternion)
    }

    @Test
    fun handles180DegreeQuaternionWithoutNaN() {
        // |xyz| is slightly above 1 after Q15 rounding; w must clamp to zero, not NaN.
        val sample = HeadTrackingParser.parse(
            packet(1, command(14, blob(18920, 18920, 18920))),
        ).single()
        assertTrue(sample.quaternion.w.isFinite())
        assertTrue(sample.quaternion.x.isFinite())
        assertQuaternionNormalized(sample.quaternion)
    }

    @Test
    fun parsesBatchedSamplesInOrder() {
        val first = blob(4000, 0, 0, timestampNanos = 1_000_000_000L)
        val second = blob(5000, 0, 0, timestampNanos = 1_040_000_000L)
        val samples = HeadTrackingParser.parse(
            packet(1, command(14, first), command(14, second)),
        )
        assertEquals(2, samples.size)
        assertEquals(4000, samples[0].attitudeRaw[0])
        assertEquals(1_000_000_000L, samples[0].timestampNanos)
        assertEquals(5000, samples[1].attitudeRaw[0])
        assertEquals(1_040_000_000L, samples[1].timestampNanos)
    }

    @Test
    fun parsesMultipleSamplesInsideOneCommand() {
        val first = blob(4000, 0, 0)
        val second = blob(5000, 0, 0)
        val samples = HeadTrackingParser.parse(
            packet(1, commandWithBlobs(14, first, second)),
        )
        assertEquals(2, samples.size)
        assertEquals(4000, samples[0].attitudeRaw[0])
        assertEquals(5000, samples[1].attitudeRaw[0])
    }

    @Test
    fun parsesOneByteAndThreeByteSequenceFraming() {
        val blob = blob(4000, 0, 0)
        val oneByte = packet(1, command(14, blob))
        val threeByte = packet(0x3DD, command(14, blob))

        assertEquals(1, HeadTrackingParser.parse(oneByte).size)
        assertEquals(1, HeadTrackingParser.parse(threeByte).size)
        assertEquals(4000, HeadTrackingParser.parse(threeByte).single().attitudeRaw[0])
    }

    @Test
    fun rejectsNonHeadTrackingPacketsAndUnsupportedServices() {
        val notTracking = singleSamplePacket.copyOf()
        notTracking[4] = 0x18
        assertTrue(HeadTrackingParser.parse(notTracking).isEmpty())
        assertTrue(HeadTrackingParser.parse(ByteArray(4)).isEmpty())
        assertTrue(HeadTrackingParser.parse(ByteArray(0)).isEmpty())
        assertTrue(
            HeadTrackingParser.parse(packet(1, command(9, blob(4000, 0, 0)))).isEmpty(),
        )
    }

    @Test
    fun truncatedPacketsNeverThrow() {
        for (length in 0 until singleSamplePacket.size) {
            HeadTrackingParser.parse(singleSamplePacket.copyOf(length))
        }
    }

    @Test
    fun relativePoseIsExpressedInWorldFrame() {
        // Neutral is rotated around Y; a world-frame pitch of 0.5 rad around X must
        // survive the neutral removal as an X rotation, not a mixed body-frame axis.
        val neutral = Quaternion(cos(0.3f), 0f, sin(0.3f), 0f).normalized()
        val pitch = Quaternion(cos(0.25f), sin(0.25f), 0f, 0f)
        val current = pitch * neutral
        val relative = HeadTrackingParser.relativeRotation(current, neutral)
        assertArrayEquals(
            floatArrayOf(0.5f, 0f, 0f),
            HeadTrackingParser.toRotationVector(relative),
            1e-4f,
        )
    }

    @Test
    fun angularVelocityUsesWorldFrame() {
        // Previous orientation is rotated around Y; the next step is a world-frame pitch
        // around X. The resulting angular velocity must be pure X, not a body-frame mix.
        val previous = Quaternion(cos(0.3f), 0f, sin(0.3f), 0f).normalized()
        val current = Quaternion(cos(0.05f), sin(0.05f), 0f, 0f) * previous
        val omega = HeadTrackingParser.angularVelocity(previous, current, 0.1)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), omega, 1e-3f)
    }

    @Test
    fun rotationVectorRoundTripsSmallRotation() {
        val half = 0.25f // half angle: rotation of 0.5 rad around X
        val q = Quaternion(cos(half), sin(half), 0f, 0f)
        val vector = HeadTrackingParser.toRotationVector(q)
        assertArrayEquals(floatArrayOf(0.5f, 0f, 0f), vector, 1e-4f)
    }

    @Test
    fun computesAndClampsAngularVelocity() {
        val half = 0.25f
        val current = Quaternion(cos(half), sin(half), 0f, 0f)
        assertArrayEquals(
            floatArrayOf(5f, 0f, 0f),
            HeadTrackingParser.angularVelocity(Quaternion.IDENTITY, current, 0.1),
            1e-3f,
        )
        assertArrayEquals(
            FloatArray(3),
            HeadTrackingParser.angularVelocity(Quaternion.IDENTITY, current, 0.0),
            0f,
        )
        assertArrayEquals(
            FloatArray(3),
            HeadTrackingParser.angularVelocity(Quaternion.IDENTITY, current, 1e-4),
            0f,
        )

        // 180 degrees in 1 ms is ~3141 rad/s; the descriptor limit is 32 rad/s.
        val halfTurn = Quaternion(0f, 1f, 0f, 0f)
        val clamped = HeadTrackingParser.angularVelocity(Quaternion.IDENTITY, halfTurn, 0.001)
        assertEquals(32f, clamped[0], 0f)
    }

    @Test
    fun quaternionAverageAlignsSigns() {
        val q = Quaternion(0.9f, 0.1f, 0.2f, 0.3f).normalized()
        val average = Quaternion.average(listOf(q, q.negated()))
        assertTrue(abs(abs(average.dot(q)) - 1f) < 1e-4f)
    }

    @Test
    fun poseCalibrationWaitsForAStillHead() {
        HeadTracking.reset()
        // Noisy/moving samples must not establish the neutral reference.
        repeat(30) { index ->
            HeadTracking.processPacket(
                packet(
                    1,
                    command(14, blob(4000, 0, 0, index * 40_000_000L, rotationRate = 20_000)),
                ),
            )
        }
        assertNull(HeadTracking.pose.value)

        repeat(25) { index ->
            HeadTracking.processPacket(
                packet(
                    1,
                    command(14, blob(4000, 0, 0, index * 40_000_000L, rotationRate = 5)),
                ),
            )
        }
        assertNotNull(HeadTracking.pose.value)
        HeadTracking.reset()
    }

    @Test
    fun headTrackingCalibratesThenEmitsPose() {
        HeadTracking.reset()
        // qx = 4000 / 32768 is a valid (non-zero) neutral attitude on the X axis.
        repeat(25) { index ->
            HeadTracking.processPacket(
                packet(1, command(14, blob(4000, 0, 0, index * 40_000_000L))),
            )
        }
        assertNotNull(HeadTracking.pose.value)
        assertArrayEquals(FloatArray(3), HeadTracking.pose.value!!.rotationVector, 1e-3f)

        // asin(11927/32768) - asin(4000/32768) = 0.25 rad, so the relative
        // rotation around X is 0.5 rad.
        HeadTracking.processPacket(
            packet(1, command(14, blob(11927, 0, 0, 25 * 40_000_000L))),
        )

        val pose = HeadTracking.pose.value
        assertNotNull(pose)
        assertArrayEquals(floatArrayOf(0.5f, 0f, 0f), pose!!.rotationVector, 1e-2f)

        HeadTracking.recenter()
        assertNull(HeadTracking.pose.value)
        repeat(25) {
            HeadTracking.processPacket(packet(1, command(14, blob(4000, 0, 0))))
        }
        assertNotNull(HeadTracking.pose.value)

        HeadTracking.reset()
        assertNull(HeadTracking.pose.value)
    }

    private fun assertQuaternionNormalized(q: Quaternion) {
        val norm = q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z
        assertEquals(1f, norm, 1e-4f)
    }

    private fun blob(
        qx: Int,
        qy: Int,
        qz: Int,
        timestampNanos: Long = 1_000_000_000L,
        rotationRate: Int = 11,
    ): ByteArray {
        val blob = sampleBlob
        blob[20] = (qx and 0xFF).toByte()
        blob[21] = ((qx ushr 8) and 0xFF).toByte()
        blob[22] = (qy and 0xFF).toByte()
        blob[23] = ((qy ushr 8) and 0xFF).toByte()
        blob[24] = (qz and 0xFF).toByte()
        blob[25] = ((qz ushr 8) and 0xFF).toByte()
        blob[26] = (rotationRate and 0xFF).toByte()
        blob[27] = ((rotationRate ushr 8) and 0xFF).toByte()
        blob[28] = (rotationRate and 0xFF).toByte()
        blob[29] = ((rotationRate ushr 8) and 0xFF).toByte()
        blob[30] = (rotationRate and 0xFF).toByte()
        blob[31] = ((rotationRate ushr 8) and 0xFF).toByte()
        for (i in 0 until 8) {
            blob[1 + i] = ((timestampNanos ushr (8 * i)) and 0xFF).toByte()
        }
        return blob
    }

    private fun command(service: Int, blob: ByteArray): ByteArray =
        commandWithBlobs(service, blob)

    private fun commandWithBlobs(service: Int, vararg blobs: ByteArray): ByteArray {
        val body = mutableListOf<Byte>(0x08.toByte(), service.toByte())
        for (blob in blobs) {
            require(blob.size == 58)
            body.add(0x1A.toByte())
            body.add(58.toByte())
            body.addAll(blob.toList())
        }
        return byteArrayOf(0x3A) + varint(body.size) + body.toByteArray()
    }

    private fun packet(sequence: Int, vararg commands: ByteArray): ByteArray {
        val payload = mutableListOf<Byte>(0x08.toByte())
        payload.addAll(varint(sequence).toList())
        payload.addAll(listOf(0x10.toByte(), 0x03.toByte()))
        commands.forEach { payload.addAll(it.toList()) }
        return byteArrayOf(
            0x04, 0x00, 0x04, 0x00,
            0x17, 0x00, 0x00, 0x00,
            0x10, 0x00,
            (payload.size and 0xFF).toByte(),
            ((payload.size ushr 8) and 0xFF).toByte(),
        ) + payload.toByteArray()
    }

    private fun varint(value: Int): ByteArray {
        val bytes = mutableListOf<Byte>()
        var remaining = value
        while (remaining >= 0x80) {
            bytes.add(((remaining and 0x7F) or 0x80).toByte())
            remaining = remaining ushr 7
        }
        bytes.add(remaining.toByte())
        return bytes.toByteArray()
    }

    private fun hex(text: String): ByteArray =
        text.split(' ').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
}
