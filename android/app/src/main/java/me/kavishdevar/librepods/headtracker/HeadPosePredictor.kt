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

package me.kavishdevar.librepods.headtracker

import me.kavishdevar.librepods.utils.HeadTrackingParser
import me.kavishdevar.librepods.utils.Quaternion
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * App-side pose lead for the audio output latency that AOSP's 120 ms predictor does
 * not cover.
 *
 * AOSP's `TWIST` predictor composes `P * exp(twist * 120 ms)`; its default
 * `LEAST_SQUARES` predictor instead extrapolates the pose history and ignores the
 * twist. A positive lead advances the reported pose by the same intrinsic
 * composition before it reaches the HID report:
 * `P_lead = P * exp(twist * lead)`. The angular velocity is left unchanged; the
 * lead is additive to either platform predictor.
 */
object HeadPosePredictor {
    const val MAX_LEAD_MS = 200

    fun advance(
        rotationVector: FloatArray,
        angularVelocity: FloatArray,
        leadMs: Int,
    ): FloatArray {
        if (leadMs <= 0 || rotationVector.size < 3 || angularVelocity.size < 3) {
            return rotationVector.copyOf()
        }
        val leadSeconds = minOf(leadMs, MAX_LEAD_MS) / 1000.0
        val pose = fromRotationVector(rotationVector)
        val delta = fromRotationVector(
            floatArrayOf(
                (angularVelocity[0] * leadSeconds).toFloat(),
                (angularVelocity[1] * leadSeconds).toFloat(),
                (angularVelocity[2] * leadSeconds).toFloat(),
            ),
        )
        val composed = pose * delta
        if (!composed.w.isFinite() || !composed.x.isFinite() ||
            !composed.y.isFinite() || !composed.z.isFinite()
        ) {
            return rotationVector.copyOf()
        }
        return HeadTrackingParser.toRotationVector(composed.normalized())
    }

    /** Axis-angle rotation vector (radians) to a unit quaternion. */
    fun fromRotationVector(rotationVector: FloatArray): Quaternion {
        if (rotationVector.size < 3) return Quaternion.IDENTITY
        val x = rotationVector[0]
        val y = rotationVector[1]
        val z = rotationVector[2]
        val angle = sqrt((x * x + y * y + z * z).toDouble())
        if (angle < 1e-6) return Quaternion.IDENTITY
        val half = angle / 2.0
        val scale = (sin(half) / angle).toFloat()
        return Quaternion(cos(half).toFloat(), x * scale, y * scale, z * scale).normalized()
    }
}
