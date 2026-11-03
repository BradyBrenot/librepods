package com.librepods.uhid;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Standalone Android UHID head-tracker helper (test tool).
 *
 * <p>Runs as the shell UID (2000) through {@code app_process}, opens {@code /dev/uhid}, registers a
 * Bluetooth-bus HID device that implements the AOSP head-tracker HID protocol, answers every
 * GET_REPORT / SET_REPORT request, and streams a synthetic input report 1 at 50 Hz while the host
 * has the device started and open.
 *
 * <p>This is a diagnostic helper for the head-tracked spatial audio spike. It is not part of the
 * app and it never talks to the AirPods: it only proves the OS-side UHID / dynamic-sensor HAL /
 * spatializer pose path with a fake pose.
 */
public final class UhidHeadTracker {

    private static final String DEVICE_NODE = "/dev/uhid";

    /** sizeof(struct uhid_event) on the target kernel: u32 type + 4372-byte union. */
    private static final int UHID_EVENT_SIZE = 4376;

    private static final int EV_DESTROY = 1;
    private static final int EV_START = 2;
    private static final int EV_STOP = 3;
    private static final int EV_OPEN = 4;
    private static final int EV_CLOSE = 5;
    private static final int EV_OUTPUT = 6;
    private static final int EV_GET_REPORT = 9;
    private static final int EV_GET_REPORT_REPLY = 10;
    private static final int EV_CREATE2 = 11;
    private static final int EV_INPUT2 = 12;
    private static final int EV_SET_REPORT = 13;
    private static final int EV_SET_REPORT_REPLY = 14;

    private static final int BUS_BLUETOOTH = 0x05;
    private static final int VENDOR_APPLE = 0x004C;

    private static final String DEVICE_NAME = "LibrePods Head Tracker";
    private static final String DEVICE_PHYS = "librepods";
    private static final String SENSOR_DESCRIPTION = "#AndroidHeadTracker#1.0";

    /**
     * Test-tool shortcut: start with All Events + Full Power + interval code 0 (10 ms).
     * The HID spec says the initial Reporting State must be No Events; a production helper
     * should start at No Events and wait for the host to enable reporting.
     */
    private static final byte INITIAL_POWER_STATE = 0x03;

    /** Input report period. 50 Hz is the AOSP minimum; 20 ms is inside the recommended 10-20 ms. */
    private static final int DEFAULT_RATE_HZ = 50;

    private static final long START_NANOS = System.nanoTime();

    /** 171-byte AOSP head-tracker report descriptor (HID v1.0), as a hex string. */
    private static final String DESCRIPTOR_HEX =
            "052009e1a10185020a0803150025ff75089517b103"
          + "0a0203150025ff75089510b103"
          + "85010a16031500250175019501a1020a40080a4108b100c0"
          + "0a19031500250175019501a1020a55080a5108b100c0"
          + "0a0e031500253f350a456475069501660110550db102"
          + "0a440516018026ff7f37604f46ed47a1b0b9125508751095038102"
          + "0a450516018026ff7f35e045205500751095038102"
          + "0a4605150026ff00350045005500750895018102c0";

    private final String mac;
    private final byte[] uuid;
    private final boolean reportIdInFeatureReply;
    private final long autoStopMs;

    private RandomAccessFile device;
    private FileChannel writeChannel;
    private final Object writeLock = new Object();

    private volatile boolean running = true;
    private volatile boolean created = false;
    private volatile boolean hostStarted = false;
    private volatile boolean hostOpen = false;
    private volatile boolean shutdownStarted = false;
    private volatile byte featureReport1State = INITIAL_POWER_STATE;

    private long sentReports = 0;
    private long receivedEvents = 0;
    private long getReportCount = 0;
    private long setReportCount = 0;
    private final int reportPeriodMs;

    private UhidHeadTracker(String mac, boolean reportIdInFeatureReply, long autoStopMs, int rateHz) {
        this.mac = mac.trim().toUpperCase(Locale.US);
        this.uuid = uuidFromMac(this.mac);
        this.reportIdInFeatureReply = reportIdInFeatureReply;
        this.autoStopMs = autoStopMs;
        this.reportPeriodMs = 1000 / rateHz;
    }

    // ---------------------------------------------------------------- entry point

    private static int poseAxis = 0;

    public static void main(String[] args) {
        String mac = null;
        long seconds = 0;
        boolean reportId = true;
        int rateHz = DEFAULT_RATE_HZ;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--seconds".equals(a) && i + 1 < args.length) {
                seconds = Long.parseLong(args[++i]);
            } else if (a.startsWith("--seconds=")) {
                seconds = Long.parseLong(a.substring("--seconds=".length()));
            } else if ("--no-report-id".equals(a)) {
                reportId = false;
            } else if ("--report-id".equals(a)) {
                reportId = true;
            } else if ("--rate".equals(a) && i + 1 < args.length) {
                rateHz = Integer.parseInt(args[++i]);
            } else if ("--axis".equals(a) && i + 1 < args.length) {
                String axis = args[++i].toLowerCase(Locale.US);
                if ("x".equals(axis)) {
                    poseAxis = 0;
                } else if ("y".equals(axis)) {
                    poseAxis = 1;
                } else if ("z".equals(axis)) {
                    poseAxis = 2;
                } else {
                    err("unknown axis: " + axis);
                    usage();
                    return;
                }
            } else if ("--help".equals(a) || "-h".equals(a)) {
                usage();
                return;
            } else if (a.startsWith("-")) {
                err("unknown option: " + a);
                usage();
                System.exit(2);
            } else if (mac == null) {
                mac = a;
            } else {
                err("unexpected argument: " + a);
                usage();
                System.exit(2);
            }
        }

        if (mac == null) {
            usage();
            System.exit(2);
        }
        if (rateHz < 50 || rateHz > 200) {
            err("--rate must be 50..200 Hz");
            System.exit(2);
        }

        try {
            UhidHeadTracker helper = new UhidHeadTracker(mac, reportId, seconds * 1000L, rateHz);
            helper.run();
        } catch (Throwable t) {
            err("fatal: " + t);
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void usage() {
        System.out.println("usage: UhidHeadTracker <BT-MAC> [--seconds N] [--no-report-id] [--rate HZ]");
        System.out.println();
        System.out.println("  <BT-MAC>         Bluetooth identity address of the headset, e.g. AA:BB:CC:DD:EE:FF");
        System.out.println("                   Its bytes become the persistent unique ID of the sensor.");
        System.out.println("  --seconds N      stop automatically after N seconds (default: run until killed)");
        System.out.println("  --no-report-id   omit the report ID as the first feature-report byte");
        System.out.println("  --rate HZ        input report rate, default 50");
        System.out.println("  --axis x|y|z     synthetic rotation axis for the pose test (default: x)");
        System.out.flush();
    }

    // ---------------------------------------------------------------- lifecycle

    private void run() throws IOException, InterruptedException {
        // NOTE: a FileChannel is deliberately NOT used for reads. FileChannelImpl.read()
        // holds its position lock while blocked in read(2), which would block every write
        // (input reports, DESTROY) on the same fd. RandomAccessFile.read/write go straight
        // to read(2)/write(2) without that lock. The FileChannel (from the same fd) is used
        // for writes only, so event byte order and sizes stay explicit.
        device = new RandomAccessFile(DEVICE_NODE, "rw");
        writeChannel = device.getChannel();
        log("STATE opened " + DEVICE_NODE + " uid=" + currentUid()
                + " reportIdInFeatureReply=" + reportIdInFeatureReply
                + " rateHz=" + (1000 / reportPeriodMs));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown("JVM shutdown hook")));

        writeCreate2();
        created = true;

        if (autoStopMs > 0) {
            Thread timer = new Thread(() -> {
                try {
                    Thread.sleep(autoStopMs);
                } catch (InterruptedException ignored) {
                    return;
                }
                log("STATE --seconds elapsed");
                // Do the teardown on a separate thread: if the kernel write ever blocks we
                // still exit, and closing the fd makes the kernel destroy the HID device.
                Thread teardown = new Thread(() -> shutdown("auto stop"));
                teardown.setDaemon(true);
                teardown.start();
                try {
                    teardown.join(3000);
                } catch (InterruptedException ignored) {
                }
                System.exit(0);
            }, "auto-stop");
            timer.setDaemon(true);
            timer.start();
        }

        Thread sender = new Thread(this::senderLoop, "pose-sender");
        sender.setDaemon(true);
        sender.start();

        readLoop();

        // The read loop only ends when the device is destroyed or the kernel side goes away.
        shutdown("read loop ended");
        sender.join(500);
        log("STATE exited events=" + receivedEvents + " reports=" + sentReports);
    }

    private void readLoop() {
        byte[] raw = new byte[UHID_EVENT_SIZE];
        while (running) {
            int got = 0;
            try {
                while (got < UHID_EVENT_SIZE) {
                    int n = device.read(raw, got, UHID_EVENT_SIZE - got);
                    if (n < 0) {
                        log("STATE /dev/uhid EOF");
                        running = false;
                        return;
                    }
                    if (n == 0) {
                        // Character devices should not return 0 for a blocking read; avoid a hot spin.
                        Thread.sleep(1);
                        continue;
                    }
                    got += n;
                }
            } catch (IOException e) {
                if (running) {
                    log("STATE read failed: " + e);
                }
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            receivedEvents++;
            try {
                handleEvent(buf);
            } catch (IOException e) {
                log("STATE reply failed: " + e);
                return;
            }
        }
    }

    private void senderLoop() {
        long next = System.nanoTime();
        long counter = 0;
        double maxRad = Math.PI;
        int rateHz = 1000 / reportPeriodMs;
        long periodNanos = 1_000_000_000L / rateHz;
        while (running) {
            long now = System.nanoTime();
            if (now < next) {
                long sleepNanos = Math.min(next - now, 5_000_000L);
                try {
                    Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }
            next += periodNanos;
            if (next < now) {
                next = now + periodNanos;
            }

            if (!created || !hostStarted || !hostOpen) {
                if (counter % (rateHz * 5L) == 0) {
                    log("STATE waiting hostStarted=" + hostStarted + " hostOpen=" + hostOpen
                            + " state=0x" + String.format(Locale.US, "%02x", featureReport1State & 0xff));
                }
                counter++;
                continue;
            }

            // Alternate +/-0.05 rad around X every 500 ms so the value is visibly non-static.
            double angle = ((counter / (rateHz / 2)) % 2 == 0) ? 0.05 : -0.05;
            if (angle > maxRad) {
                angle = maxRad;
            }
            try {
                double ax = poseAxis == 0 ? angle : 0.0;
                double ay = poseAxis == 1 ? angle : 0.0;
                double az = poseAxis == 2 ? angle : 0.0;
                sendInputReport1(ax, ay, az, 0.0, 0.0, 0.0, 0);
                sentReports++;
                if (sentReports % (rateHz * 5L) == 0) {
                    log("STATE tx reports=" + sentReports + " events=" + receivedEvents
                            + " getReport=" + getReportCount + " setReport=" + setReportCount);
                }
            } catch (IOException e) {
                if (running) {
                    log("STATE input write failed: " + e);
                }
                return;
            }
            counter++;
        }
    }

    private void shutdown(String reason) {
        synchronized (this) {
            if (shutdownStarted) {
                return;
            }
            shutdownStarted = true;
        }
        running = false;
        log("STATE shutdown: " + reason);
        if (device != null) {
            try {
                if (writeChannel != null && writeChannel.isOpen()) {
                    writeDestroy();
                }
            } catch (Throwable t) {
                log("STATE destroy write failed: " + t);
            }
            try {
                device.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- event handling

    private void handleEvent(ByteBuffer ev) throws IOException {
        int type = ev.getInt(0);
        switch (type) {
            case EV_START:
                hostStarted = true;
                log("EVENT START");
                break;
            case EV_STOP:
                hostStarted = false;
                log("EVENT STOP");
                break;
            case EV_OPEN:
                hostOpen = true;
                log("EVENT OPEN (hidraw opened by a HID client)");
                break;
            case EV_CLOSE:
                hostOpen = false;
                log("EVENT CLOSE (hidraw closed)");
                break;
            case EV_OUTPUT: {
                // struct uhid_output_req { u8 data[4096]; u16 size; u8 rtype; }
                int size = ev.getShort(4 + 4096) & 0xffff;
                int rtype = ev.get(4 + 4096 + 2) & 0xff;
                byte[] data = new byte[Math.min(size, 4096)];
                for (int i = 0; i < data.length; i++) {
                    data[i] = ev.get(4 + i);
                }
                log("EVENT OUTPUT rtype=" + rtype + " size=" + size + " data=" + hex(data));
                break;
            }
            case EV_GET_REPORT: {
                int id = ev.getInt(4);
                int rnum = ev.get(8) & 0xff;
                int rtype = ev.get(9) & 0xff;
                getReportCount++;
                log("EVENT GET_REPORT id=" + id + " rnum=" + rnum + " rtype=" + rtype);
                byte[] reply;
                if (rnum == 2) {
                    reply = featureReport2();
                } else if (rnum == 1) {
                    reply = featureReport1();
                } else {
                    log("EVENT GET_REPORT unsupported rnum=" + rnum + " -> empty err=0 reply");
                    reply = new byte[0];
                }
                sendGetReportReply(id, reply);
                log("TX GET_REPORT_REPLY id=" + id + " err=0 size=" + reply.length
                        + (rnum == 2 ? " data=<redacted>" : " data=" + hex(reply)));
                break;
            }
            case EV_SET_REPORT: {
                int id = ev.getInt(4);
                int rnum = ev.get(8) & 0xff;
                int rtype = ev.get(9) & 0xff;
                int size = ev.getShort(10) & 0xffff;
                byte[] data = new byte[Math.min(size, 4096)];
                for (int i = 0; i < data.length; i++) {
                    data[i] = ev.get(12 + i);
                }
                setReportCount++;
                log("EVENT SET_REPORT id=" + id + " rnum=" + rnum + " rtype=" + rtype
                        + " size=" + size + " data=" + hex(data));
                if (rnum == 1) {
                    applyFeatureReport1(data, size);
                } else {
                    log("STATE SET_REPORT for rnum=" + rnum + " ignored, replying success anyway");
                }
                sendSetReportReply(id, 0);
                log("TX SET_REPORT_REPLY id=" + id + " err=0");
                break;
            }
            case EV_GET_REPORT_REPLY:
                log("EVENT GET_REPORT_REPLY (unexpected) id=" + ev.getInt(4)
                        + " err=" + (ev.getShort(8) & 0xffff));
                break;
            case EV_SET_REPORT_REPLY:
                log("EVENT SET_REPORT_REPLY (unexpected) id=" + ev.getInt(4)
                        + " err=" + (ev.getShort(8) & 0xffff));
                break;
            default:
                log("EVENT unknown type=" + type);
                break;
        }
    }

    private void applyFeatureReport1(byte[] data, int size) {
        int state;
        boolean hadReportId;
        if (size >= 2 && (data[0] & 0xff) == 1) {
            state = data[1] & 0xff;
            hadReportId = true;
        } else if (size >= 1) {
            state = data[0] & 0xff;
            hadReportId = false;
        } else {
            log("STATE SET_REPORT rnum=1 with empty data, keeping 0x"
                    + String.format(Locale.US, "%02x", featureReport1State & 0xff));
            return;
        }
        featureReport1State = (byte) state;
        int reportingState = state & 0x01;
        int powerState = (state >> 1) & 0x01;
        int intervalCode = (state >> 2) & 0x3F;
        // The descriptor maps logical 0..63 linearly to physical 10..100 ms.
        int hostIntervalMs = 10 + (intervalCode * 90 + 31) / 63;
        log("STATE feature1 state=0x" + String.format(Locale.US, "%02x", state)
                + " reportIdInData=" + hadReportId
                + " reporting=" + ((reportingState != 0) ? "ALL_EVENTS" : "NO_EVENTS")
                + " power=" + ((powerState != 0) ? "FULL" : "OFF")
                + " intervalCode=" + intervalCode
                + " hostIntervalMs=" + hostIntervalMs
                + " actualPeriodMs=" + reportPeriodMs);
    }

    // ---------------------------------------------------------------- uhid writes

    private ByteBuffer newEvent() {
        return ByteBuffer.allocate(UHID_EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    }

    private void writeCreate2() throws IOException {
        byte[] descriptor = hexToBytes(DESCRIPTOR_HEX);
        ByteBuffer ev = newEvent();
        ev.putInt(0, EV_CREATE2);
        putCString(ev, 4, DEVICE_NAME, 128);
        putCString(ev, 4 + 128, DEVICE_PHYS, 64);
        putCString(ev, 4 + 128 + 64, mac, 64);
        ev.putShort(4 + 128 + 64 + 64, (short) descriptor.length);            // rd_size
        ev.putShort(4 + 128 + 64 + 64 + 2, (short) BUS_BLUETOOTH);            // bus
        ev.putInt(4 + 128 + 64 + 64 + 4, VENDOR_APPLE);                       // vendor
        ev.putInt(4 + 128 + 64 + 64 + 8, 0);                                  // product
        ev.putInt(4 + 128 + 64 + 64 + 12, 0);                                 // version
        ev.putInt(4 + 128 + 64 + 64 + 16, 0);                                 // country
        for (int i = 0; i < descriptor.length; i++) {
            ev.put(4 + 128 + 64 + 64 + 20 + i, descriptor[i]);
        }
        writeEvent(ev);
        log("STATE CREATE2 name=\"" + DEVICE_NAME + "\" uniq=<redacted>"
                + " descBytes=" + descriptor.length
                + " bus=0x05 vendor=0x004C product=0 version=0 country=0 uuid=<redacted>");
    }

    private void writeDestroy() throws IOException {
        ByteBuffer ev = newEvent();
        ev.putInt(0, EV_DESTROY);
        writeEvent(ev);
        log("TX DESTROY");
    }

    private void sendGetReportReply(int id, byte[] data) throws IOException {
        int size = data.length;
        ByteBuffer ev = newEvent();
        ev.putInt(0, EV_GET_REPORT_REPLY);
        ev.putInt(4, id);
        ev.putShort(8, (short) 0);   // err
        ev.putShort(10, (short) size);
        for (int i = 0; i < size; i++) {
            ev.put(12 + i, data[i]);
        }
        writeEvent(ev);
    }

    private void sendSetReportReply(int id, int err) throws IOException {
        ByteBuffer ev = newEvent();
        ev.putInt(0, EV_SET_REPORT_REPLY);
        ev.putInt(4, id);
        ev.putShort(8, (short) err);
        writeEvent(ev);
    }

    private void sendInputReport1(double rx, double ry, double rz,
                                  double vx, double vy, double vz, int discontinuity)
            throws IOException {
        int size = 1 + 6 + 6 + 1; // report id + 3 x i16 + 3 x i16 + u8
        ByteBuffer ev = newEvent();
        ev.putInt(0, EV_INPUT2);
        ev.putShort(4, (short) size);
        int p = 6;
        ev.put(p, (byte) 1); // report ID 1
        p += 1;
        p = putRad(ev, p, rx);
        p = putRad(ev, p, ry);
        p = putRad(ev, p, rz);
        p = putAngular(ev, p, vx);
        p = putAngular(ev, p, vy);
        p = putAngular(ev, p, vz);
        ev.put(p, (byte) (discontinuity & 0xff));
        writeEvent(ev);
    }

    private static int putRad(ByteBuffer ev, int offset, double radians) {
        double clamped = Math.max(-Math.PI, Math.min(Math.PI, radians));
        long scaled = Math.round(clamped * 32767.0 / Math.PI);
        ev.putShort(offset, (short) scaled);
        return offset + 2;
    }

    private static int putAngular(ByteBuffer ev, int offset, double radiansPerSecond) {
        double clamped = Math.max(-32.0, Math.min(32.0, radiansPerSecond));
        long scaled = Math.round(clamped * 32767.0 / 32.0);
        ev.putShort(offset, (short) scaled);
        return offset + 2;
    }

    private void writeEvent(ByteBuffer ev) throws IOException {
        ev.position(0);
        ev.limit(UHID_EVENT_SIZE);
        synchronized (writeLock) {
            int written = writeChannel.write(ev);
            if (written != UHID_EVENT_SIZE) {
                throw new IOException("short uhid write: " + written + "/" + UHID_EVENT_SIZE);
            }
        }
    }

    // ---------------------------------------------------------------- feature reports

    private byte[] featureReport2() {
        byte[] description = SENSOR_DESCRIPTION.getBytes(StandardCharsets.US_ASCII);
        int size = description.length + uuid.length;
        int offset = 0;
        byte[] out = new byte[size + (reportIdInFeatureReply ? 1 : 0)];
        if (reportIdInFeatureReply) {
            out[offset++] = 2;
        }
        System.arraycopy(description, 0, out, offset, description.length);
        offset += description.length;
        System.arraycopy(uuid, 0, out, offset, uuid.length);
        return out;
    }

    private byte[] featureReport1() {
        byte state = featureReport1State;
        if (reportIdInFeatureReply) {
            return new byte[]{1, state};
        }
        return new byte[]{state};
    }

    private static byte[] uuidFromMac(String mac) {
        String[] parts = mac.split(":");
        if (parts.length != 6) {
            throw new IllegalArgumentException("invalid MAC format");
        }
        byte[] out = new byte[16];
        out[8] = (byte) 'B';
        out[9] = (byte) 'T';
        for (int i = 0; i < 6; i++) {
            if (parts[i].length() != 2) {
                throw new IllegalArgumentException("invalid MAC byte");
            }
            out[10 + i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private static void putCString(ByteBuffer ev, int offset, String value, int maxLen) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        int n = Math.min(bytes.length, maxLen - 1);
        for (int i = 0; i < n; i++) {
            ev.put(offset + i, bytes[i]);
        }
    }

    private static byte[] hexToBytes(String hex) {
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hex(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static void log(String line) {
        long ms = (System.nanoTime() - START_NANOS) / 1_000_000L;
        System.out.println("[" + ms + "ms] " + line);
        System.out.flush();
    }

    private static void err(String line) {
        System.out.println("ERROR " + line);
        System.out.flush();
    }

    private static int currentUid() {
        // Avoid android.os dependencies: /proc/self/status is always available.
        try (java.io.BufferedReader r = java.nio.file.Files.newBufferedReader(
                Paths.get("/proc/self/status"), StandardCharsets.US_ASCII)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("Uid:")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length > 1) {
                        return Integer.parseInt(parts[1]);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }
}

