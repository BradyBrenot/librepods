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

/**
 * AirPods-to-Android rotation-vector axis mapping.
 *
 * Measured AirPods 4 axes (nod down / turn left / tilt right plateaus):
 * X = roll axis (positive = tilt right), Y = pitch axis (positive = nod down),
 * Z = yaw axis (positive = turn left).
 *
 * Android's headset frame is X = right, Y = nose, Z = up. Positive pitch is looking up
 * (`quaternionToAngles` pitch = asin(nose.Z)), positive roll is tilting right, and positive
 * yaw is looking left. Therefore Android X = -AirPods Y, Android Y = AirPods X,
 * Android Z = AirPods Z.
 */
object HeadPoseAxes {
    fun map(vector: FloatArray): FloatArray {
        if (vector.size < 3) return FloatArray(3)
        return floatArrayOf(-vector[1], vector[0], vector[2])
    }
}
