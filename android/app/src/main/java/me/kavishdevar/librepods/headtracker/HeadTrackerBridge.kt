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

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.utils.HeadPose
import rikka.shizuku.Shizuku

/**
 * App-side owner of the Shizuku head-tracker UserService.
 *
 * The UHID device must be created by a shell-UID process. A Shizuku UserService provides
 * exactly that identity on non-rooted devices (and is harmless when the device is rooted).
 */
object HeadTrackerBridge {
    private const val TAG = "HeadTrackerBridge"
    private const val PERMISSION_REQUEST_CODE = 0x4C50

    /**
     * Bump this whenever HeadTrackerUserService/UhidHeadTracker changes: Shizuku reuses a
     * running UserService with the same version. The app version code is combined into the
     * final version, but debug builds share it, so a manual bump is still required when
     * testing service-code changes.
     */
    private const val HEAD_TRACKER_SERVICE_VERSION = 2

    @Volatile
    private var service: IHeadTrackerService? = null

    @Volatile
    private var binding = false

    @Volatile
    private var permissionRequested = false

    @Volatile
    private var trackerStarted = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var pendingMac: String? = null

    @Volatile
    private var currentMac: String? = null

    @Volatile
    private var args: Shizuku.UserServiceArgs? = null

    @Volatile
    private var permissionListenerRegistered = false

    @Volatile
    private var binderListenerRegistered = false

    @Volatile
    private var loggedUnavailable = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            service = binder?.let { IHeadTrackerService.Stub.asInterface(it) }
            Log.d(TAG, "UserService connected")
            val mac = pendingMac
            val boundService = service
            if (mac != null && boundService != null) {
                try {
                    boundService.start(mac)
                    trackerStarted = true
                    currentMac = mac
                } catch (e: Exception) {
                    Log.w(TAG, "start failed: ${e.message}")
                    notifyUser("could not create the head tracker (${e.message})")
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "UserService disconnected")
            binding = false
            trackerStarted = false
            service = null
            currentMac = null
            val context = appContext
            val mac = pendingMac
            if (context != null && mac != null) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (service == null && pendingMac != null) bind(context, mac)
                }, 1000)
            }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        val context = appContext
        val mac = pendingMac
        if (context != null && mac != null) {
            Log.d(TAG, "Shizuku binder received, rebinding")
            bind(context, mac)
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == PERMISSION_REQUEST_CODE) {
            permissionRequested = false
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Shizuku permission granted")
                val context = appContext
                val mac = pendingMac
                if (context != null && mac != null) bind(context, mac)
            } else {
                Log.w(TAG, "Shizuku permission denied")
                notifyUser("Shizuku permission is required")
            }
        }
    }

    /**
     * Ensures the bridge is running for [macAddress]. Safe to call repeatedly.
     *
     * The caller decides whether the user enabled system spatial audio; this method only
     * handles Shizuku availability, permission and binding.
     */
    fun ensureStarted(context: Context, macAddress: String) {
        if (BuildConfig.PLAY_BUILD) return
        if (macAddress.isBlank()) return
        appContext = context.applicationContext
        pendingMac = macAddress

        try {
            if (!Shizuku.pingBinder()) {
                logUnavailable("Shizuku is not running")
                return
            }
            if (!permissionListenerRegistered) {
                Shizuku.addRequestPermissionResultListener(permissionListener)
                permissionListenerRegistered = true
            }
            if (!binderListenerRegistered) {
                Shizuku.addBinderReceivedListener(binderReceivedListener)
                binderListenerRegistered = true
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                if (!permissionRequested) {
                    permissionRequested = true
                    Log.d(TAG, "requesting Shizuku permission")
                    try {
                        Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
                    } catch (e: Exception) {
                        permissionRequested = false
                        throw e
                    }
                }
                return
            }
        } catch (e: Exception) {
            logUnavailable("Shizuku unavailable: ${e.message}")
            return
        }

        if (binding) return
        val boundService = service
        if (boundService != null) {
            if (trackerStarted && currentMac == macAddress) return
            if (trackerStarted) {
                try {
                    boundService.stop()
                } catch (ignored: Exception) {
                }
                trackerStarted = false
                currentMac = null
            }
            try {
                boundService.start(macAddress)
                trackerStarted = true
                currentMac = macAddress
            } catch (e: Exception) {
                Log.w(TAG, "restart failed: ${e.message}")
            }
            return
        }
        bind(context, macAddress)
    }

    fun updatePose(pose: HeadPose) {
        if (!trackerStarted) return
        try {
            service?.updatePose(
                HeadPoseAxes.map(pose.rotationVector),
                HeadPoseAxes.map(pose.angularVelocity),
                pose.discontinuity,
            )
        } catch (e: Exception) {
            Log.w(TAG, "updatePose failed: ${e.message}")
        }
    }

    fun stop() {
        pendingMac = null
        trackerStarted = false
        currentMac = null
        try {
            service?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop failed: ${e.message}")
        }
    }

    fun shutdown() {
        pendingMac = null
        service?.let {
            try {
                it.stop()
            } catch (ignored: Exception) {
            }
        }
        service = null
        trackerStarted = false
        currentMac = null
        args?.let {
            try {
                Shizuku.unbindUserService(it, connection, true)
            } catch (ignored: Exception) {
            }
        }
        args = null
        binding = false
    }

    private fun bind(context: Context, macAddress: String) {
        if (binding || service != null) return
        val userServiceArgs = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, HeadTrackerUserService::class.java.name),
        )
            .daemon(false)
            .tag("librepods_head_tracker")
            .processNameSuffix("head_tracker")
            .debuggable(BuildConfig.DEBUG)
            // Pack the manual protocol version in the high nibble and the app version in the
            // low 28 bits. This cannot alias a normal version-code bump and lets a debug
            // rebuild force a new UserService by incrementing the constant. The version code
            // must stay below 2^28 (~268M), which is far above the current scheme.
            .version(
                (HEAD_TRACKER_SERVICE_VERSION shl 28) or
                    (BuildConfig.VERSION_CODE and 0x0FFF_FFFF),
            )
        args = userServiceArgs
        pendingMac = macAddress
        binding = true
        try {
            Log.d(TAG, "binding UserService")
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (e: Exception) {
            binding = false
            Log.w(TAG, "bindUserService failed: ${e.message}")
            notifyUser("could not start the Shizuku service (${e.message})")
        }
    }

    private fun logUnavailable(message: String) {
        if (!loggedUnavailable) {
            loggedUnavailable = true
            Log.w(TAG, message)
            notifyUser(message)
        }
    }

    private fun notifyUser(message: String) {
        val context = appContext ?: return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "System spatial audio: $message", Toast.LENGTH_LONG).show()
        }
    }
}
