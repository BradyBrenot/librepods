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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

data class Orientation(val pitch: Float = 0f, val yaw: Float = 0f)
data class Acceleration(val vertical: Float = 0f, val horizontal: Float = 0f)

/**
 * A head pose relative to the neutral orientation captured after the stream starts.
 *
 * [rotationVector] is an axis * angle rotation vector in radians (magnitude <= pi),
 * [angularVelocity] is in radians/second, and [discontinuity] increments whenever the
 * reference frame is reset.
 */
data class HeadPose(
    val rotationVector: FloatArray,
    val angularVelocity: FloatArray,
    val discontinuity: Int,
)

object HeadTracking {
    private val _orientation = MutableStateFlow(Orientation())
    val orientation = _orientation.asStateFlow()

    private val _acceleration = MutableStateFlow(Acceleration())
    val acceleration = _acceleration.asStateFlow()

    private val _pose = MutableStateFlow<HeadPose?>(null)
    val pose = _pose.asStateFlow()

    private val calibrationSamples = mutableListOf<Triple<Int, Int, Int>>()
    private var isCalibrated = false
    private var o1Neutral = 19000
    private var o2Neutral = 0
    private var o3Neutral = 0

    private val poseCalibrationSamples = mutableListOf<Quaternion>()
    private var poseNeutral: Quaternion? = null
    private var previousPoseQuaternion: Quaternion? = null
    private var previousTimestampNanos = 0L
    private var discontinuity = 0

    private const val CALIBRATION_SAMPLE_COUNT = 10
    private const val POSE_CALIBRATION_SAMPLE_COUNT = 25
    private const val ORIENTATION_OFFSET = 5500

    /**
     * Parses and consumes a `0x17` packet, returning every motion sample it carried.
     *
     * The visualizer orientation keeps the legacy pitch/yaw math so existing UI behaviour
     * is unchanged; [pose] carries the full quaternion-derived rotation vector used by the
     * system head-tracker bridge.
     */
    @Synchronized
    fun processPacket(packet: ByteArray): List<HeadTrackingSample> {
        val samples = HeadTrackingParser.parse(packet)
        for (sample in samples) {
            processSample(sample)
        }
        return samples
    }

    private fun processSample(sample: HeadTrackingSample) {
        val (o1, o2, o3) = sample.attitudeRaw
        if (!isCalibrated) {
            calibrationSamples.add(Triple(o1, o2, o3))
            if (calibrationSamples.size >= CALIBRATION_SAMPLE_COUNT) {
                calibrate()
            }
        } else {
            _orientation.value = calculateOrientation(o1, o2, o3)
            _acceleration.value = Acceleration(
                vertical = sample.rotationRate[2].toFloat(),
                horizontal = sample.rotationRate[1].toFloat(),
            )
        }

        val neutral = poseNeutral
        if (neutral == null) {
            poseCalibrationSamples.add(sample.quaternion)
            if (poseCalibrationSamples.size >= POSE_CALIBRATION_SAMPLE_COUNT) {
                poseNeutral = Quaternion.average(poseCalibrationSamples)
                previousPoseQuaternion = sample.quaternion
                previousTimestampNanos = sample.timestampNanos
                discontinuity++
                _pose.value = HeadPose(FloatArray(3), FloatArray(3), discontinuity)
            }
            return
        }

        val relative = HeadTrackingParser.relativeRotation(sample.quaternion, neutral)
        val deltaSeconds =
            (sample.timestampNanos - previousTimestampNanos) / 1_000_000_000.0
        val angularVelocity = previousPoseQuaternion?.let {
            HeadTrackingParser.angularVelocity(it, sample.quaternion, deltaSeconds)
        } ?: FloatArray(3)

        previousPoseQuaternion = sample.quaternion
        previousTimestampNanos = sample.timestampNanos
        _pose.value = HeadPose(
            rotationVector = HeadTrackingParser.toRotationVector(relative),
            angularVelocity = angularVelocity,
            discontinuity = discontinuity,
        )
    }

    private fun calibrate() {
        if (calibrationSamples.size < 3) return

        // Add offset during calibration
        o1Neutral = calibrationSamples.map { it.first + ORIENTATION_OFFSET }.average().roundToInt()
        o2Neutral = calibrationSamples.map { it.second + ORIENTATION_OFFSET }.average().roundToInt()
        o3Neutral = calibrationSamples.map { it.third + ORIENTATION_OFFSET }.average().roundToInt()

        isCalibrated = true
    }

    @Suppress("UnusedVariable")
    private fun calculateOrientation(o1: Int, o2: Int, o3: Int): Orientation {
        if (!isCalibrated) return Orientation()

        val o1Norm = (o1 + ORIENTATION_OFFSET) - o1Neutral
        val o2Norm = (o2 + ORIENTATION_OFFSET) - o2Neutral
        val o3Norm = (o3 + ORIENTATION_OFFSET) - o3Neutral

        val pitch = (o2Norm + o3Norm) / 2f / 32000f * 180f
        val yaw = (o2Norm - o3Norm) / 2f / 32000f * 180f

        return Orientation(pitch, yaw)
    }

    /** Re-captures the neutral orientation without clearing the visualizer calibration. */
    @Synchronized
    fun recenter() {
        poseCalibrationSamples.clear()
        poseNeutral = null
        previousPoseQuaternion = null
        previousTimestampNanos = 0L
        discontinuity++
        _pose.value = null
    }

    @Synchronized
    fun reset() {
        calibrationSamples.clear()
        isCalibrated = false
        poseCalibrationSamples.clear()
        poseNeutral = null
        previousPoseQuaternion = null
        previousTimestampNanos = 0L
        discontinuity++
        _orientation.value = Orientation()
        _acceleration.value = Acceleration()
        _pose.value = null
    }
}
