package com.librepods.uhid;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * Test harness for the UHID head-tracker helper.
 *
 * <p>AOSP only activates the head-tracker sensor while the spatializer has an active
 * <em>immersive</em> track: {@code Spatializer::shouldUseHeadTracking_l()} is
 * {@code containsImmersiveChannelMask(mActiveTracksMasks)}. With no multichannel playback the
 * pose controller is never given a sensor, so {@code SpatializerPoseController} stays
 * {@code HeadSensor: INVALID} even when the UHID device enumerates correctly.
 *
 * <p>This probe writes silence to a 5.1 PCM {@link AudioTrack} with
 * {@code SPATIALIZATION_BEHAVIOR_AUTO} so the policy registers an immersive spatialized
 * playback client. Run it next to {@link UhidHeadTracker} to exercise the full path.
 *
 * <p>Runs under {@code app_process} as the shell UID; no app install is needed.
 */
public final class SpatialTrackProbe {

    public static void main(String[] args) {
        int seconds = 60;
        int channelMask = AudioFormat.CHANNEL_OUT_5POINT1;
        String maskName = "5.1";

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--seconds".equals(a) && i + 1 < args.length) {
                seconds = Integer.parseInt(args[++i]);
            } else if (a.startsWith("--seconds=")) {
                seconds = Integer.parseInt(a.substring("--seconds=".length()));
            } else if ("--mask".equals(a) && i + 1 < args.length) {
                String m = args[++i];
                if ("stereo".equals(m)) {
                    channelMask = AudioFormat.CHANNEL_OUT_STEREO;
                    maskName = "stereo";
                } else if ("5.0".equals(m)) {
                    channelMask = AudioFormat.CHANNEL_OUT_5POINT1 & ~AudioFormat.CHANNEL_OUT_LOW_FREQUENCY;
                    maskName = "5.0";
                } else if ("5.1".equals(m)) {
                    channelMask = AudioFormat.CHANNEL_OUT_5POINT1;
                    maskName = "5.1";
                } else {
                    System.out.println("ERROR unknown --mask " + m);
                    System.exit(2);
                }
            } else if ("--help".equals(a) || "-h".equals(a)) {
                System.out.println("usage: SpatialTrackProbe [--seconds N] [--mask 5.1|5.0|stereo]");
                return;
            } else {
                System.out.println("ERROR unknown argument " + a);
                System.exit(2);
            }
        }

        int sampleRate = 48000;
        int bytesPerFrame = 2 * (maskName.equals("stereo") ? 2 : (maskName.equals("5.0") ? 5 : 6));
        int minBuffer = AudioTrack.getMinBufferSize(
                sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(minBuffer, sampleRate * bytesPerFrame / 5);

        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build();
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build();

        AudioTrack track;
        try {
            AudioTrack.Builder builder = new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize);
            // AudioTrack.SPATIALIZATION_BEHAVIOR_AUTO (1) is a hidden constant; the SDK stub
            // does not expose it, so set it reflectively. The native default is AUTO anyway.
            try {
                builder.getClass()
                        .getMethod("setSpatializationBehavior", int.class)
                        .invoke(builder, 1 /* SPATIALIZATION_BEHAVIOR_AUTO */);
                System.out.println("STATE spatialization behavior AUTO (via hidden API)");
            } catch (Throwable t) {
                System.out.println("STATE setSpatializationBehavior unavailable (" + t
                        + "), relying on the AudioTrack default");
            }
            track = builder.build();
        } catch (Throwable t) {
            System.out.println("ERROR AudioTrack build failed: " + t);
            t.printStackTrace(System.out);
            System.exit(1);
            return;
        }

        System.out.println("STATE track mask=" + maskName + " channelMask=0x"
                + Integer.toHexString(channelMask)
                + " sampleRate=" + sampleRate
                + " bufferSize=" + bufferSize
                + " state=" + track.getState());
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            System.out.println("ERROR AudioTrack not initialized");
            System.exit(1);
        }

        track.play();
        System.out.println("STATE playing state=" + track.getPlayState());

        byte[] silence = new byte[bufferSize / 2];
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        long frames = 0;
        int lastLogged = -1;
        int rc = 0;
        while (System.currentTimeMillis() < deadline) {
            int n = track.write(silence, 0, silence.length);
            if (n < 0) {
                System.out.println("ERROR write failed: " + n);
                rc = 1;
                break;
            }
            frames += n;
            int elapsed = (int) ((System.currentTimeMillis() - (deadline - seconds * 1000L)) / 1000);
            if (elapsed / 5 != lastLogged) {
                lastLogged = elapsed / 5;
                System.out.println("STATE wrote=" + frames + " bytes elapsed=" + elapsed + "s"
                        + " playState=" + track.getPlayState());
            }
        }

        System.out.println("STATE stopping");
        try {
            track.stop();
        } catch (Throwable ignored) {
        }
        track.release();
        System.out.println("STATE done");
        System.exit(rc);
    }
}
