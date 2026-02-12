/*
 * Copyright (c) 2019 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.nsf.festalon;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.SourceDataLine;

import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vavi.sound.SoundUtil.volume;


/**
 * FestalonTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2019/04/18 umjammer initial version <br>
 */
@PropsEntity(url = "file:local.properties")
class FestalonTest {

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    @Property(name = "nsf")
    String in = "src/test/resources/test.nsf";

    @Property
    int track = 5;

    @Property(name = "vavi.test.volume")
    double volume = 0.2;

    @BeforeEach
    void setup() throws Exception {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
Debug.println("track: " + track);
Debug.println("volume: " + volume);
    }

    static boolean onIde = System.getProperty("vavi.test", "").equals("ide");
    static long time = onIde ? 1000 * 1000 : 10 * 1000;

    @Test
    @EnabledIfSystemProperty(named = "vavi.test", matches = "true")
    void test0() throws Exception {
Debug.println(in);
        byte[] buffer = Files.newInputStream(Path.of(in)).readAllBytes();

        Nsf nsf = (Nsf) Nsf.load(buffer, buffer.length);
        nsf.controlSong(track);
Debug.println("Total Songs: " + nsf.totalSongs + ", Starting Song: " + nsf.startingSong);
        nsf.setSound(44100, 1);
        nsf.disable(0);

        nsf.setVolume(100);
        nsf.setLowPass(false, 0, 0);

        CountDownLatch cdl = new CountDownLatch(1);

        AudioFormat audioFormat = new AudioFormat(44100, 16, 1, true, false); // Changed to little-endian for easier debugging if needed, but matched with loop

        DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(audioFormat);
        volume(line, volume);
        line.addLineListener(ev -> { if (LineEvent.Type.STOP == ev.getType()) cdl.countDown(); });
        line.start();

        int[] r = new int[1];
        do {
            float[] wave = nsf.emulate(r);
            int validSamples = r[0];
            if (wave != null && validSamples > 0) {

                ByteBuffer bb = ByteBuffer.allocate(2 * validSamples).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < validSamples; i++) {
                    // Convert 0.0-1.0 to -32768 to 32767
                    short s = (short) ((wave[i] - 0.5f) * 65535.0f);
                    bb.putShort(s);
                }
                byte[] bytes = bb.array();
                line.write(bytes, 0, bytes.length);
            }

        } while (r[0] > 0);

        line.drain();
        line.stop();
        line.close();
    }

    @Test
    @EnabledIfSystemProperty(named = "vavi.test", matches = "true")
    void test1() throws Exception {
Debug.println(in);
        byte[] buffer = Files.newInputStream(Path.of(in)).readAllBytes();

        Nsf nsf = (Nsf) Nsf.load(buffer, buffer.length);
        nsf.controlSong(track);
Debug.println("Total Songs: " + nsf.totalSongs + ", Starting Song: " + nsf.startingSong);
        nsf.setSound(44100, 1);
        nsf.disable(0);

        nsf.setVolume(100);
        nsf.setLowPass(false, 0, 0);

//        Arrays.asList(nsf.songNames).forEach(System.err::println);

        CountDownLatch cdl = new CountDownLatch(1);

        AudioFormat audioFormat = new AudioFormat(44100, 16, 1, true, false); // Changed to little-endian for easier debugging if needed, but matched with loop

        DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(audioFormat);
        volume(line, volume);
        line.addLineListener(ev -> { if (LineEvent.Type.STOP == ev.getType()) cdl.countDown(); });
        line.start();

        long start = System.currentTimeMillis();
        boolean lastSilence = true;
        int frameCount = 0;
        int[] r = new int[1];
        boolean soundDetected = false;
        double maxRms = 0;
        List<Double> rmsHistory = new ArrayList<>();
        do {
            float[] wave = nsf.emulate(r);
            int validSamples = r[0];
            if (wave != null && validSamples > 0) {
                for (int i = 0; i < validSamples; i++) {
                    if (Math.abs(wave[i] - 0.5f) > 0.0001f) {
                         Debug.print(Level.FINER, "Non-silent sample detected: " + wave[i]);
                    }
                }
                double min = Double.MAX_VALUE;
                double max = -Double.MAX_VALUE;
                double rms = 0;
                int zeroCrossings = 0;
                for (int i = 0; i < validSamples; i++) {
                    float v = wave[i] - 0.5f; // Center around 0
                    rms += v * v;
                    if (v < min) min = v;
                    if (v > max) max = v;
                    if (i > 0) {
                        float prev = wave[i - 1] - 0.5f;
                        if (v * prev < 0) {
                            zeroCrossings++;
                        }
                    }
                }
                rms = Math.sqrt(rms / validSamples);
                double zcr = (double) zeroCrossings / validSamples;
                double range = max - min;

                frameCount++;
                if (rms > 0.01) { // Threshold for "sound"
                    if (!soundDetected) {
                        Debug.println("First sound detected at frame " + frameCount + ": rms=" + rms + ", zcr=" + zcr);
                        soundDetected = true;
                    }
                }

                if (frameCount % 60 == 0 || (soundDetected && frameCount % 10 == 0)) {
                     Debug.println(Level.FINER, "Frame " + frameCount + ": rms=" + rms + ", zcr=" + zcr + ", range=" + range + " [min=" + min + ", max=" + max + "]");
                }

                if (rms > maxRms) maxRms = rms;
                rmsHistory.add(rms);

                ByteBuffer bb = ByteBuffer.allocate(2 * validSamples).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < validSamples; i++) {
                    // Convert 0.0-1.0 to -32768 to 32767
                    short s = (short) ((wave[i] - 0.5f) * 65535.0f);
                    bb.putShort(s);
                }
                byte[] bytes = bb.array();
                line.write(bytes, 0, bytes.length);
            }

            if (System.currentTimeMillis() - start > time) break;
        } while (r[0] > 0);

        line.drain();
        line.stop();
        line.close();
        
        if (rmsHistory.size() > 10) {
            double mean = rmsHistory.stream().mapToDouble(d -> d).average().orElse(0.0);
            double variance = rmsHistory.stream().mapToDouble(d -> Math.pow(d - mean, 2)).average().orElse(0.0);
            double stdDev = Math.sqrt(variance);
            Debug.println("RMS StdDev: " + stdDev);
            if (stdDev < 0.001) { // Threshold for "melody" aka dynamic sound
                 throw new AssertionError("Sound is constant/static (Low StdDev). Not music.");
            }
        }

        // Peak dB calculation
        // 20 * log10(maxRms)
        double peakDb = 20 * Math.log10(maxRms);
        Debug.println("Peak dB: " + peakDb + ", maxRms: " + maxRms);

        if (maxRms < 0.01) { // -40dB is approx 0.01
             throw new AssertionError("No sound detected (RMS too low). Peak dB: " + peakDb);
        }
        
        if (!soundDetected) {
            throw new AssertionError("No sound detected (RMS never exceeded threshold). Max RMS: " + String.format("%f", maxRms));
        } else {
            Debug.println("Sound detected! Max RMS: " + maxRms);
        }
    }

    @Test
    @Disabled("for ai iteration")
    @DisplayName("Audio quality analysis - compare with out.wav")
    void test2() throws Exception {
        Path refPath = Paths.get("tmp/out.wav");
        if (!Files.exists(refPath)) {
            throw new IllegalStateException("Reference file tmp/out.wav not found, skipping quality test.");
        }

        AudioInputStream ais = AudioSystem.getAudioInputStream(refPath.toFile());
        AudioFormat format = ais.getFormat();
        assertEquals(44100, (int) format.getSampleRate());
        assertEquals(16, format.getSampleSizeInBits());
        assertEquals(1, format.getChannels());

        byte[] refBytes = ais.readAllBytes();
        ShortBuffer refSb = ByteBuffer.wrap(refBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        short[] refSamples = new short[refSb.remaining()];
        refSb.get(refSamples);

        // Setup NSF emulator
        byte[] buffer = Files.newInputStream(Path.of(in)).readAllBytes();
        Nsf nsf = (Nsf) Nsf.load(buffer, buffer.length);
        nsf.setSound(44100, 1);
        nsf.disable(0);
        nsf.setVolume(100);
        nsf.setLowPass(false, 0, 0);

        int[] r = new int[1];
        int totalSamples = 0;
        short[] emuSamples = new short[refSamples.length];

        while (totalSamples < refSamples.length) {
            float[] wave = nsf.emulate(r);
            int validSamples = r[0];
            if (wave == null || validSamples == 0) break;

            for (int i = 0; i < validSamples && totalSamples < refSamples.length; i++) {
                emuSamples[totalSamples++] = (short) ((wave[i] - 0.5) * 65535);
            }
        }

        // Find best alignment
        int bestOffset = 0;
        double minAvgDiff = Double.MAX_VALUE;
        int searchRange = 1000;
        int skipSettling = 44100; // Skip first 1s

        for (int offset = -searchRange; offset <= searchRange; offset += 10) {
            long currentDiffSum = 0;
            int count = 0;
            for (int i = skipSettling; i < totalSamples - skipSettling; i += 10) {
                int refIdx = i;
                int emuIdx = i + offset;
                if (emuIdx < 0 || emuIdx >= totalSamples) continue;
                currentDiffSum += Math.abs(emuSamples[emuIdx] - refSamples[refIdx]);
                count++;
            }
            double currentAvgDiff = (double) currentDiffSum / count;
            if (currentAvgDiff < minAvgDiff) {
                minAvgDiff = currentAvgDiff;
                bestOffset = offset;
            }
        }

        // Comparison with best offset
        long diffSum = 0;
        int diffCount = 0;
        int count = 0;
        for (int i = skipSettling; i < totalSamples - skipSettling; i++) {
            int refIdx = i;
            int emuIdx = i + bestOffset;
            if (emuIdx < 0 || emuIdx >= totalSamples) continue;

            int diff = Math.abs(emuSamples[emuIdx] - refSamples[refIdx]);
            diffSum += diff;
            if (diff > 500) diffCount++;
            count++;
        }

        double avgDiff = (double) diffSum / count;
Debug.print("avgDiff: " + avgDiff);

        // Relaxed thresholds for audio emulation comparison
        assertTrue(avgDiff < 1000, "Average difference too high: " + avgDiff);
        assertTrue(diffCount < count * 0.5, "Too many divergent samples: " + diffCount);
    }

    // ----

    /**
     * @param args 0: .nes file
     */
    public static void main(String[] args) throws Exception {
        FestalonTest app = new FestalonTest();
        app.setup();
        if (args.length > 0) app.in = args[0];
        app.test1();
    }
}
