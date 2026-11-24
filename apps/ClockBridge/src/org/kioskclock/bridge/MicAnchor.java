package org.kioskclock.bridge;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

/**
 * Holds an AudioRecord open at 48 kHz stereo, the MT8167's native capture format, and discards
 * the samples. Apps that later ask for 16 kHz mono share this HAL stream and get AudioFlinger's
 * working resampler instead of MediaTek's broken BesRecord conversion.
 * Same idea as fritzbytes/MicAnchor (Apache-2.0); reimplemented here so it can start at boot
 * inside a persistent system app. Nothing is stored or transmitted.
 */
final class MicAnchor implements Runnable {
    private static final int RATE = 48000;
    private static final int CHANNELS = AudioFormat.CHANNEL_IN_STEREO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private volatile boolean running = true;
    volatile String state = "starting";

    void stop() { running = false; }

    @Override
    public void run() {
        long backoff = 1000;
        while (running) {
            AudioRecord rec = null;
            try {
                int min = AudioRecord.getMinBufferSize(RATE, CHANNELS, ENCODING);
                if (min <= 0) throw new IllegalStateException("getMinBufferSize=" + min);
                int buf = min * 4;
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, CHANNELS, ENCODING, buf);
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("not initialized");
                rec.startRecording();
                if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("not recording");
                state = "holding " + rec.getSampleRate() + " Hz x" + rec.getChannelCount();
                Log.i(App.TAG, "mic anchor " + state);
                backoff = 1000;
                short[] sink = new short[buf / 2];
                while (running) {
                    int n = rec.read(sink, 0, sink.length);
                    if (n < 0) { Log.w(App.TAG, "mic read " + n + ", reopening"); break; }
                }
            } catch (Throwable t) {
                state = "error: " + t.getMessage();
                Log.w(App.TAG, "mic anchor failed: " + t);
            } finally {
                if (rec != null) {
                    try { rec.stop(); } catch (Throwable ignored) { }
                    rec.release();
                }
            }
            if (!running) break;
            try { Thread.sleep(backoff); } catch (InterruptedException e) { return; }
            backoff = Math.min(backoff * 2, 30000);
        }
        state = "stopped";
    }
}
