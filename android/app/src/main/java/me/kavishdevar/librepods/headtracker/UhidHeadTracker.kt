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
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Android head-tracker HID device backed by `/dev/uhid`.
 *
 * This is the production version of `tools/uhid-headtracker/UhidHeadTracker.java`. It runs
 * inside a Shizuku UserService (shell UID 2000) and receives poses from the app over Binder.
 * The class owns the complete feature-report state machine and only emits input reports
 * while the host has enabled reporting (`SET_REPORT` report 1: All Events + Full Power).
 */
class UhidHeadTracker(private val macAddress: String) {

    internal companion object {
        const val TAG = "UhidHeadTracker"
        const val DEVICE_NODE = "/dev/uhid"
        const val EVENT_SIZE = 4376
        const val DEVICE_NAME = "LibrePods Head Tracker"
        const val DEVICE_PHYS = "librepods"

        const val EV_DESTROY = 1
        const val EV_START = 2
        const val EV_STOP = 3
        const val EV_OPEN = 4
        const val EV_CLOSE = 5
        const val EV_GET_REPORT = 9
        const val EV_GET_REPORT_REPLY = 10
        const val EV_CREATE2 = 11
        const val EV_INPUT2 = 12
        const val EV_SET_REPORT = 13
        const val EV_SET_REPORT_REPLY = 14

        const val BUS_BLUETOOTH = 0x05
        const val VENDOR_APPLE = 0x004C

        /** AOSP head-tracker HID descriptor (171 bytes). */
        val DESCRIPTOR = hex(
            "052009e1a10185020a0803150025ff75089517b1030a0203150025ff75089510b103" +
                "85010a16031500250175019501a1020a40080a4108b100c00a190315002501750195" +
                "01a1020a55080a5108b100c00a0e031500253f350a456475069501660110550db102" +
                "0a440516018026ff7f37604f46ed47a1b0b91255087510950381020a450516018026" +
                "ff7f35e0452055007510950381020a4605150026ff00350045005500750895018102c0",
        )

        val SENSOR_DESCRIPTION = "#AndroidHeadTracker#1.0".toByteArray(Charsets.US_ASCII)

        fun hex(text: String): ByteArray =
            text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        /** Persistent Unique ID: eight zero bytes, ASCII `BT`, then the identity MAC. */
        internal fun bluetoothUuidForMac(macAddress: String): ByteArray {
            val parts = macAddress.split(':')
            require(parts.size == 6) { "invalid MAC address" }
            val result = ByteArray(16)
            result[8] = 'B'.code.toByte()
            result[9] = 'T'.code.toByte()
            for (i in 0 until 6) {
                result[10 + i] = parts[i].toInt(16).toByte()
            }
            return result
        }

        /** Input report 1: report ID, 3 x i16 rotation vector, 3 x i16 angular velocity, u8. */
        internal fun encodeInputReport(
            rotationVector: FloatArray,
            angularVelocity: FloatArray,
            discontinuity: Int,
        ): ByteArray {
            val report = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
            report.put(0x01)
            for (i in 0 until 3) {
                report.putShort(
                    ((rotationVector.getOrElse(i) { 0f }) * 32767.0 / PI)
                        .roundToInt().coerceIn(-32767, 32767).toShort(),
                )
            }
            for (i in 0 until 3) {
                report.putShort(
                    ((angularVelocity.getOrElse(i) { 0f }) * 32767.0 / 32.0)
                        .roundToInt().coerceIn(-32767, 32767).toShort(),
                )
            }
            report.put((discontinuity and 0xFF).toByte())
            return report.array()
        }
    }

    private val running = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val reporting = AtomicBoolean(false)
    private val powered = AtomicBoolean(false)
    private val writeLock = Any()

    @Volatile
    private var rotationVector = FloatArray(3)

    @Volatile
    private var angularVelocity = FloatArray(3)

    @Volatile
    private var discontinuity = 0

    @Volatile
    private var lastPoseNanos = 0L

    private var txReports = 0L
    private var txWindowStart = 0L

    private var device: RandomAccessFile? = null
    private var channel: FileChannel? = null
    private var reader: Thread? = null
    private var writer: ScheduledExecutorService? = null

    /** Feature report 1, including the report ID. Starts at No Events / Power Off. */
    private var featureReport1 = byteArrayOf(0x01, 0x00)

    private val uuid: ByteArray = bluetoothUuidForMac(macAddress)

    fun isRunning(): Boolean = running.get()

    fun start() {
        if (running.get()) return
        val file = RandomAccessFile(DEVICE_NODE, "rw")
        device = file
        channel = file.channel
        sendCreate2()
        running.set(true)
        txReports = 0
        txWindowStart = 0L

        reader = Thread({ readLoop() }, "uhid-headtracker-reader").apply {
            isDaemon = true
            start()
        }
        writer = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "uhid-headtracker-writer").apply { isDaemon = true }
        }.also {
            // The HAL delivers ~44 samples/s even when the UHID writer runs at 100 Hz, so
            // 20 ms (50 Hz, the AOSP minimum) is the useful rate. More writes only burn CPU.
            it.scheduleAtFixedRate({ sendInputReport() }, 0, 20, TimeUnit.MILLISECONDS)
        }
        Log.d(TAG, "started")
    }

    fun updatePose(rotationVector: FloatArray, angularVelocity: FloatArray, discontinuity: Int) {
        if (rotationVector.size >= 3) {
            this.rotationVector = floatArrayOf(
                rotationVector[0], rotationVector[1], rotationVector[2],
            )
        }
        if (angularVelocity.size >= 3) {
            this.angularVelocity = floatArrayOf(
                angularVelocity[0], angularVelocity[1], angularVelocity[2],
            )
        }
        this.discontinuity = discontinuity
        lastPoseNanos = System.nanoTime()
    }

    fun stop() {
        if (!running.getAndSet(false) && writer == null && device == null) return
        val executor = writer
        writer = null
        try {
            executor?.shutdown()
            executor?.awaitTermination(200, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "writer shutdown failed: ${e.message}")
        }
        try {
            sendDestroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroy write failed: ${e.message}")
        }
        try {
            device?.close()
        } catch (ignored: Exception) {
        }
        device = null
        channel = null
        reader?.interrupt()
        reader = null
        Log.d(TAG, "stopped")
    }

    private fun readLoop() {
        val buffer = ByteArray(EVENT_SIZE)
        val file = device ?: return
        while (running.get()) {
            var read = 0
            try {
                while (read < EVENT_SIZE) {
                    val count = file.read(buffer, read, EVENT_SIZE - read)
                    if (count < 0) {
                        Log.d(TAG, "uhid EOF")
                        running.set(false)
                        return
                    }
                    if (count == 0) {
                        Thread.sleep(1)
                        continue
                    }
                    read += count
                }
                handleEvent(ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN))
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "read failed: ${e.message}")
                running.set(false)
                return
            }
        }
    }

    private fun handleEvent(event: ByteBuffer) {
        when (event.getInt(0)) {
            EV_START, EV_OPEN -> {
                started.set(true)
                Log.d(TAG, "host ${if (event.getInt(0) == EV_START) "started" else "opened"} device")
            }
            EV_STOP, EV_CLOSE -> {
                started.set(false)
                Log.d(TAG, "host stopped/closed device")
            }
            EV_GET_REPORT -> {
                val id = event.getInt(4)
                val reportNumber = event.get(8).toInt() and 0xFF
                val reply = when (reportNumber) {
                    2 -> byteArrayOf(0x02) + SENSOR_DESCRIPTION + uuid
                    1 -> featureReport1
                    else -> byteArrayOf()
                }
                sendGetReportReply(id, reply)
                Log.d(TAG, "GET_REPORT rnum=$reportNumber size=${reply.size}")
            }
            EV_SET_REPORT -> {
                val id = event.getInt(4)
                val reportNumber = event.get(8).toInt() and 0xFF
                val size = event.getShort(10).toInt() and 0xFFFF
                val data = ByteArray(size)
                for (i in 0 until size) data[i] = event.get(12 + i)
                if (reportNumber == 1 && data.isNotEmpty()) {
                    featureReport1 = if (data[0].toInt() == 1 && data.size > 1) {
                        data.copyOf()
                    } else {
                        byteArrayOf(0x01, data[0])
                    }
                    val state = if (data[0].toInt() == 1 && data.size > 1) data[1].toInt() else data[0].toInt()
                    reporting.set(state and 0x01 != 0)
                    powered.set(state and 0x02 != 0)
                    Log.d(
                        TAG,
                        "SET_REPORT state=0x%02x reporting=%b power=%b".format(state, reporting.get(), powered.get()),
                    )
                } else {
                    Log.d(TAG, "SET_REPORT rnum=$reportNumber size=$size")
                }
                sendSetReportReply(id)
            }
            EV_DESTROY -> {
                Log.d(TAG, "destroyed by host")
                running.set(false)
            }
        }
    }

    private fun sendInputReport() {
        if (!running.get() || !started.get() || !reporting.get() || !powered.get()) return
        // Stop before AOSP's 120 ms freshness timeout, and drop the twist after one sample
        // interval so a paused AirPods stream cannot keep predicting motion.
        val age = System.nanoTime() - lastPoseNanos
        if (lastPoseNanos == 0L || age > 120_000_000L) return
        val twist = if (age > 80_000_000L) FloatArray(3) else angularVelocity
        try {
            sendInput2(encodeInputReport(rotationVector, twist, discontinuity))
            txReports++
            val now = System.nanoTime()
            if (txWindowStart == 0L) txWindowStart = now
            val elapsed = now - txWindowStart
            if (elapsed >= 1_000_000_000L) {
                Log.d(TAG, "tx reports/s=${txReports * 1_000_000_000L / elapsed}")
                txReports = 0
                txWindowStart = now
            }
        } catch (e: Exception) {
            Log.w(TAG, "input report failed: ${e.message}")
        }
    }

    private fun sendCreate2() {
        val event = newEvent()
        event.putInt(0, EV_CREATE2)
        putCString(event, 4, DEVICE_NAME, 128)
        putCString(event, 4 + 128, DEVICE_PHYS, 64)
        putCString(event, 4 + 128 + 64, macAddress, 64)
        event.putShort(4 + 128 + 64 + 64, DESCRIPTOR.size.toShort())
        event.putShort(4 + 128 + 64 + 64 + 2, BUS_BLUETOOTH.toShort())
        event.putInt(4 + 128 + 64 + 64 + 4, VENDOR_APPLE)
        event.putInt(4 + 128 + 64 + 64 + 8, 0)
        event.putInt(4 + 128 + 64 + 64 + 12, 0)
        event.putInt(4 + 128 + 64 + 64 + 16, 0)
        for (i in DESCRIPTOR.indices) {
            event.put(4 + 128 + 64 + 64 + 20 + i, DESCRIPTOR[i])
        }
        writeEvent(event)
        Log.d(TAG, "CREATE2 descriptor=${DESCRIPTOR.size} bytes")
    }

    private fun sendGetReportReply(id: Int, data: ByteArray) {
        val event = newEvent()
        event.putInt(0, EV_GET_REPORT_REPLY)
        event.putInt(4, id)
        event.putShort(8, 0)
        event.putShort(10, data.size.toShort())
        for (i in data.indices) event.put(12 + i, data[i])
        writeEvent(event)
    }

    private fun sendSetReportReply(id: Int) {
        val event = newEvent()
        event.putInt(0, EV_SET_REPORT_REPLY)
        event.putInt(4, id)
        event.putShort(8, 0)
        writeEvent(event)
    }

    private fun sendInput2(data: ByteArray) {
        val event = newEvent()
        event.putInt(0, EV_INPUT2)
        event.putShort(4, data.size.toShort())
        for (i in data.indices) event.put(6 + i, data[i])
        writeEvent(event)
    }

    private fun sendDestroy() {
        if (channel == null) return
        val event = newEvent()
        event.putInt(0, EV_DESTROY)
        writeEvent(event)
    }

    private fun newEvent(): ByteBuffer =
        ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)

    private fun writeEvent(event: ByteBuffer) {
        val out = channel ?: return
        synchronized(writeLock) {
            event.position(0)
            event.limit(EVENT_SIZE)
            while (event.hasRemaining()) {
                out.write(event)
            }
        }
    }

    private fun putCString(buffer: ByteBuffer, offset: Int, value: String, maxLength: Int) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val length = minOf(bytes.size, maxLength - 1)
        for (i in 0 until length) buffer.put(offset + i, bytes[i])
        buffer.put(offset + length, 0)
    }

}
