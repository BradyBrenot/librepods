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

import android.util.Log

/**
 * Shizuku UserService entry point. Runs as shell UID 2000 and owns the UHID device.
 */
class HeadTrackerUserService : IHeadTrackerService.Stub() {

    private var tracker: UhidHeadTracker? = null
    private var macAddress: String? = null

    @Synchronized
    override fun start(address: String) {
        Log.d(TAG, "start mac=<redacted>")
        if (address.isBlank()) return
        macAddress = address
        tracker?.stop()
        tracker = UhidHeadTracker(address).also { it.start() }
    }

    @Synchronized
    override fun updatePose(
        rotationVector: FloatArray?,
        angularVelocity: FloatArray?,
        discontinuity: Int,
    ) {
        // If the reader died (link loss, uhid error), recreate the device on the next pose.
        if (tracker?.isRunning() != true) {
            val address = macAddress ?: return
            tracker?.stop()
            tracker = try {
                UhidHeadTracker(address).also { it.start() }
            } catch (e: Exception) {
                Log.w(TAG, "restart failed: ${e.message}")
                null
            }
        }
        tracker?.updatePose(
            rotationVector ?: FloatArray(3),
            angularVelocity ?: FloatArray(3),
            discontinuity,
        )
    }

    @Synchronized
    override fun recenter() {
        // The app captures the neutral orientation; the service only mirrors it.
    }

    @Synchronized
    override fun stop() {
        Log.d(TAG, "stop")
        try {
            tracker?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop failed: ${e.message}")
        } finally {
            tracker = null
            macAddress = null
        }
    }

    @Synchronized
    override fun destroy() {
        Log.d(TAG, "destroy")
        try {
            tracker?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "destroy failed: ${e.message}")
        } finally {
            tracker = null
            macAddress = null
            System.exit(0)
        }
    }

    private companion object {
        const val TAG = "HeadTrackerUserService"
    }
}
