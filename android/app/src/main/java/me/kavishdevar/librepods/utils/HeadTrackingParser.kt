/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.utils

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * One motion sample from the AirPods `0x17` head-tracking stream.
 *
 * The sample is the 58-byte `SensorDataWX` blob carried inside a protobuf `cmd`
 * message (field 7). Layout measured on AirPods 4 ANC (A3056):
 *
 * | Offset | Type       | Content                                      |
 * |--------|------------|----------------------------------------------|
 * | 0      | u8         | `0x01` for a sensor sample                   |
 * | 1      | u64 LE     | timestamp, nanoseconds                       |
 * | 9      | u8         | `0x03` marker                                |
 * | 20     | 3 x i16 LE | attitude: Q15 quaternion x, y, z             |
 * | 26     | 3 x i16 LE | rotation rate, raw signed 16-bit             |
 * | 38     | 3 x i16 LE | user acceleration, gravity removed           |
 * | 44     | 3 x i16 LE | gravity, about 1024 per g                    |
 * | 54     | u32 LE     | counter, in 5 ms ticks                       |
 *
 * `w` is reconstructed positive from the three Q15 components. Samples with a
 * zero or non-normalizable attitude are warm-up/status data and are skipped.
 */
data class HeadTrackingSample(
    val timestampNanos: Long,
    val quaternion: Quaternion,
    val attitudeRaw: IntArray,
    val rotationRate: IntArray,
    val userAcceleration: IntArray,
    val gravity: IntArray,
    val counter: Long,
)

/** A right-handed quaternion. */
data class Quaternion(val w: Float, val x: Float, val y: Float, val z: Float) {
    fun normalized(): Quaternion {
        val length = sqrt((w * w + x * x + y * y + z * z).toDouble()).toFloat()
        if (length <= 1e-6f) return IDENTITY
        return Quaternion(w / length, x / length, y / length, z / length)
    }

    fun conjugate(): Quaternion = Quaternion(w, -x, -y, -z)

    fun dot(other: Quaternion): Float =
        w * other.w + x * other.x + y * other.y + z * other.z

    operator fun times(other: Quaternion): Quaternion = Quaternion(
        w * other.w - x * other.x - y * other.y - z * other.z,
        w * other.x + x * other.w + y * other.z - z * other.y,
        w * other.y - x * other.z + y * other.w + z * other.x,
        w * other.z + x * other.y - y * other.x + z * other.w,
    )

    fun negated(): Quaternion = Quaternion(-w, -x, -y, -z)

    companion object {
        val IDENTITY = Quaternion(1f, 0f, 0f, 0f)

        fun average(samples: List<Quaternion>): Quaternion {
            if (samples.isEmpty()) return IDENTITY
            val reference = samples.first()
            var w = 0.0
            var x = 0.0
            var y = 0.0
            var z = 0.0
            for (sample in samples) {
                val sign = if (reference.dot(sample) < 0f) -1.0 else 1.0
                w += sample.w * sign
                x += sample.x * sign
                y += sample.y * sign
                z += sample.z * sign
            }
            return Quaternion(w.toFloat(), x.toFloat(), y.toFloat(), z.toFloat()).normalized()
        }
    }
}

/**
 * Parses AAP `0x17` head-tracking packets.
 *
 * The opcode payload is a protobuf message. Batched notifications can contain
 * several `cmd` messages (field 7), and each `cmd` can contain several 58-byte
 * blobs (field 3). Every valid sample is returned in stream order.
 */
object HeadTrackingParser {
    private val HEADER = byteArrayOf(0x04, 0x00, 0x04, 0x00)
    private const val HEADER_SIZE = 12
    private const val SAMPLE_SIZE = 58
    private const val QUATERNION_SCALE = 32768.0

    fun parse(packet: ByteArray): List<HeadTrackingSample> {
        if (packet.size < HEADER_SIZE) return emptyList()
        if (packet[4] != 0x17.toByte()) return emptyList()
        for (i in HEADER.indices) {
            if (packet[i] != HEADER[i]) return emptyList()
        }

        val samples = mutableListOf<HeadTrackingSample>()
        var offset = HEADER_SIZE
        while (offset < packet.size) {
            val tag = readVarint(packet, offset) ?: break
            offset = tag.second
            val field = (tag.first ushr 3).toInt()
            when ((tag.first and 0x07).toInt()) {
                0 -> offset = readVarint(packet, offset)?.second ?: break
                1 -> offset += 8
                2 -> {
                    val lengthField = readVarint(packet, offset) ?: break
                    offset = lengthField.second
                    val size = lengthField.first
                    if (size < 0 || size > packet.size - offset) break
                    if (field == 7) parseCommand(packet, offset, size.toInt(), samples)
                    offset += size.toInt()
                }
                5 -> offset += 4
                else -> break
            }
        }
        return samples
    }

    private fun parseCommand(
        packet: ByteArray,
        start: Int,
        length: Int,
        samples: MutableList<HeadTrackingSample>,
    ) {
        var offset = start
        val end = start + length
        var service = -1
        val blobs = mutableListOf<ByteArray>()
        while (offset < end) {
            val tag = readVarint(packet, offset, end) ?: break
            offset = tag.second
            val field = (tag.first ushr 3).toInt()
            when ((tag.first and 0x07).toInt()) {
                0 -> {
                    val value = readVarint(packet, offset, end) ?: break
                    offset = value.second
                    if (field == 1) service = value.first.toInt()
                }
                2 -> {
                    val lengthField = readVarint(packet, offset, end) ?: break
                    offset = lengthField.second
                    val size = lengthField.first
                    if (size < 0 || size > end - offset) break
                    if (field == 3) {
                        blobs.add(packet.copyOfRange(offset, offset + size.toInt()))
                    }
                    offset += size.toInt()
                }
                1 -> offset += 8
                5 -> offset += 4
                else -> break
            }
        }
        if (service != 6 && service != 14 && service != 16) return
        for (blob in blobs) {
            parseSample(blob)?.let(samples::add)
        }
    }

    private fun parseSample(blob: ByteArray): HeadTrackingSample? {
        if (blob.size < SAMPLE_SIZE) return null
        if (blob[0] != 0x01.toByte() || blob[9] != 0x03.toByte()) return null

        val qx = readInt16(blob, 20)
        val qy = readInt16(blob, 22)
        val qz = readInt16(blob, 24)
        // The all-zero vector is the warm-up value the accessory emits before the fused
        // attitude is ready. Any other (even near-identity) attitude is a real sample.
        if (qx == 0 && qy == 0 && qz == 0) return null
        val lengthSquared =
            (qx.toDouble() * qx + qy.toDouble() * qy + qz.toDouble() * qz) /
                (QUATERNION_SCALE * QUATERNION_SCALE)
        if (lengthSquared > 1.001) return null

        // CoreMotion/RTBuddy reports the imaginary part with a positive w. Clamp the
        // square-root argument: Q15 rounding can push |xyz| slightly above 1 for a 180°
        // rotation, which would otherwise produce NaN (and poison the neutral average).
        val quaternion = Quaternion(
            w = sqrt((1.0 - lengthSquared).coerceAtLeast(0.0)).toFloat(),
            x = (qx / QUATERNION_SCALE).toFloat(),
            y = (qy / QUATERNION_SCALE).toFloat(),
            z = (qz / QUATERNION_SCALE).toFloat(),
        ).normalized()

        return HeadTrackingSample(
            timestampNanos = readUInt64(blob, 1),
            quaternion = quaternion,
            attitudeRaw = intArrayOf(qx, qy, qz),
            rotationRate = intArrayOf(
                readInt16(blob, 26),
                readInt16(blob, 28),
                readInt16(blob, 30),
            ),
            userAcceleration = intArrayOf(
                readInt16(blob, 38),
                readInt16(blob, 40),
                readInt16(blob, 42),
            ),
            gravity = intArrayOf(
                readInt16(blob, 44),
                readInt16(blob, 46),
                readInt16(blob, 48),
            ),
            counter = readUInt32(blob, 54),
        )
    }

    /** Returns `(value, nextOffset)` or null when the varint is truncated. */
    private fun readVarint(
        data: ByteArray,
        start: Int,
        limit: Int = data.size,
    ): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var offset = start
        while (offset < limit && shift < 64) {
            val value = data[offset].toInt() and 0xFF
            result = result or ((value and 0x7F).toLong() shl shift)
            offset++
            if (value and 0x80 == 0) return result to offset
            shift += 7
        }
        return null
    }

    private fun readInt16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or (data[offset + 1].toInt() shl 8)

    private fun readUInt32(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)

    private fun readUInt64(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = value or ((data[offset + i].toLong() and 0xFF) shl (8 * i))
        }
        return value
    }

    /**
     * Rotation from [neutral] to [current], expressed in the world frame.
     *
     * `q` maps body to world, so `q * neutral^-1` is the world-frame delta. The sign is
     * canonicalised so the reconstructed rotation vector takes the shortest path.
     */
    fun relativeRotation(current: Quaternion, neutral: Quaternion): Quaternion {
        var relative = current * neutral.conjugate()
        if (relative.w < 0f) relative = relative.negated()
        return relative
    }

    /**
     * Converts a quaternion to the shortest rotation vector (axis * angle, radians).
     * The result is in the same frame as [quaternion].
     */
    fun toRotationVector(quaternion: Quaternion): FloatArray {
        var q = quaternion.normalized()
        if (q.w < 0f) q = q.negated()
        val sinHalf = sqrt((q.x * q.x + q.y * q.y + q.z * q.z).toDouble())
        if (sinHalf < 1e-6) return FloatArray(3)
        val angle = 2.0 * atan2(sinHalf, q.w.toDouble())
        val scale = (angle / sinHalf).toFloat()
        return floatArrayOf(q.x * scale, q.y * scale, q.z * scale)
    }

    /**
     * Estimates angular velocity (rad/s) from two absolute orientations.
     * Returns zeros when the timestamps are unusable.
     */
    fun angularVelocity(
        previous: Quaternion,
        current: Quaternion,
        deltaSeconds: Double,
    ): FloatArray {
        if (deltaSeconds < 1e-3 || deltaSeconds > 0.5) return FloatArray(3)
        // The HID/AOSP twist is intrinsic to the head frame, not the world frame.
        var delta = previous.conjugate() * current
        if (delta.w < 0f) delta = delta.negated()
        val sinHalf = sqrt((delta.x * delta.x + delta.y * delta.y + delta.z * delta.z).toDouble())
        if (sinHalf < 1e-6) return FloatArray(3)
        val angle = 2.0 * atan2(sinHalf, delta.w.toDouble())
        val scale = (angle / sinHalf / deltaSeconds).toFloat()
        // Android's head-tracker HID descriptor encodes angular velocity as +/-32 rad/s.
        return floatArrayOf(
            (delta.x * scale).coerceIn(-32f, 32f),
            (delta.y * scale).coerceIn(-32f, 32f),
            (delta.z * scale).coerceIn(-32f, 32f),
        )
    }
}
