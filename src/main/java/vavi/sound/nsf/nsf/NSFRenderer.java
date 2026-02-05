/*
 * https://github.com/orangelando/nsf
 */

package vavi.sound.nsf.nsf;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.Validate;

import lando.nsf.apu.Divider;

import static java.lang.System.getLogger;


public final class NSFRenderer {

    private static final Logger logger = getLogger(NSFRenderer.class.getName());

    //NES system clock rate
    public static final int SYSTEM_CYCLES_PER_SEC = 21_477_270;

    //both the NES CPU and APU timers run off the
    //system clock divided by 12 or ~1.79MHz
    private final Divider cpuDivider = new Divider(12);

    //~239.996hz
    private final Divider frameSequencerDivider = new Divider(89490);

    private final NES nes;

    private final long maxSystemCycles;
    private final long fadeOutStartCycle;
    private final boolean disableFadeOut;

    private final PeriodTimestampFinder playPeriodFinder;
    private final SilenceDetector silenceDetector;

    private boolean splitChannels = false;
    private boolean disableBandPass = false;
    private long systemCycle;
    private long nextCycleToPlay;

    public interface Sink {
        void write(byte b);
        void finish();
    }

    public NSFRenderer(
            NES nes,
            int maxPlaySecs,
            int maxSilenceSecs) {

        Validate.isTrue(maxPlaySecs > 0);
        Validate.isTrue(maxSilenceSecs > 0);

        this.nes = Objects.requireNonNull(nes);

        this.maxSystemCycles = (long) SYSTEM_CYCLES_PER_SEC * maxPlaySecs;
        this.fadeOutStartCycle = this.maxSystemCycles - SYSTEM_CYCLES_PER_SEC; // 1 second fade out
        this.disableFadeOut = this.fadeOutStartCycle <= SYSTEM_CYCLES_PER_SEC; // do not fade out if max play is <= 1 second.
        this.silenceDetector = new SilenceDetector(SYSTEM_CYCLES_PER_SEC * maxSilenceSecs);
        this.playPeriodFinder = createPlayPeriodFinder();
    }

    public void splitChannels() {
        splitChannels = true;
    }

    public void disableBandPass() {
        disableBandPass = true;
    }

    /**
     * @param trackNum 1 origin
     */
    public void render(int trackNum, Sink sink) throws IOException {
        Validate.isTrue(trackNum >= 1 && trackNum <= nes.nsf.header.totalSongs);
        Validate.notNull(sink);

        try (APUSamplers samplers = new APUSamplers(sink, disableBandPass)) {

            samplers.setupSamplers(nes.apu, splitChannels);

            nes.initTune(trackNum - 1);
            nes.execInit();

            systemCycle = 0;
            cpuDivider.reset();
            silenceDetector.reset();
            nextCycleToPlay = playPeriodFinder.findNextPeriod(0);

            for (APUSamplePipe sampler : samplers.getSamplers()) {
                sampler.sampleConsumer.init();
            }

            if (samplers.getSamplers().size() > 1) {
                render(samplers.getSamplers());
            } else {
                render(samplers.getSamplers().get(0));
            }

            for (APUSamplePipe sampler : samplers.getSamplers()) {
                sampler.sampleConsumer.finish();
            }
        }
    }

    /** multiple samplers */
    private void render(List<APUSamplePipe> samplers) throws IOException {
        while (systemCycle < maxSystemCycles) {
            systemCycle += step(samplers);
//if (systemCycle % 10000000 == 0) {
//logger.log(Level.DEBUG, ".");
//}
            if (silenceDetector.wasSilenceDetected()) {
                break;
            }
        }
//logger.log(Level.DEBUG, );
    }

    /** single sampler */
    private void render(APUSamplePipe sampler) throws IOException {
        while (systemCycle < maxSystemCycles) {
            systemCycle += step(sampler);

            if (silenceDetector.wasSilenceDetected()) {
                break;
            }
        }
    }

    /** */
    private PeriodTimestampFinder createPlayPeriodFinder() {

        long playPeriodNanos = nes.nsf.getPlayPeriodNanos();
logger.log(Level.DEBUG, "playPeriodNanos: " + playPeriodNanos);

        long playPeriodSystemCycles = Math.round(
                playPeriodNanos/(1e9/SYSTEM_CYCLES_PER_SEC));

logger.log(Level.DEBUG, "playPeriodSystemCycles: " + playPeriodSystemCycles);

        return new PeriodTimestampFinder(0, playPeriodSystemCycles);
    }

    /* multiple samplers */
    private int step(List<APUSamplePipe> samplers) throws IOException {
        int cycles;

        if (systemCycle >= nextCycleToPlay) {
            nextCycleToPlay = playPeriodFinder.findNextPeriod(systemCycle + 1);

//logger.log(Level.DEBUG, "___________________________________________________________________________________");
//logger.log(Level.DEBUG, "t:%.3f".formatted((double) systemCycle / SYSTEM_CYCLES_PER_SEC));
//logger.log(Level.DEBUG, );

            nes.execPlay();

            cycles = nes.numCycles.get();
        } else {
            cycles = 1;
        }

        for (int i = 0; i < cycles; i++) {

            if (cpuDivider.clock()) {
                nes.apu.clockChannelTimers();
            }

            if (frameSequencerDivider.clock()) {
                nes.apu.clockFrameSequencer();
            }

            float sample = nes.apu.mixerOutput();

            silenceDetector.addSample(sample);

            float scale;

            if (!disableFadeOut && systemCycle >= fadeOutStartCycle) {
                scale = 1f - (float)(systemCycle - fadeOutStartCycle)/(maxSystemCycles - fadeOutStartCycle);
            } else {
                scale = 1f;
            }

            for (APUSamplePipe sampler : samplers) {
                sampler.sample(scale);
            }
        }

        return cycles;
    }

    /** */
    private float getScale() {
        float scale;
        if (!disableFadeOut && systemCycle >= fadeOutStartCycle) {
            scale = 1f - (float)(systemCycle - fadeOutStartCycle)/(maxSystemCycles - fadeOutStartCycle);
        } else {
            scale = 1f;
        }
        return scale;
    }

    /** single sampler */
    private int step(APUSamplePipe sampler) throws IOException {
        int cycles;

        if (systemCycle >= nextCycleToPlay) {
            nextCycleToPlay = playPeriodFinder.findNextPeriod(systemCycle + 1);

            nes.execPlay();

            cycles = nes.numCycles.get();
        } else {
            cycles = 1;
        }

        float scale = getScale();

        for (int i = 0; i < cycles; i++) {

            if (cpuDivider.clock()) {
                nes.apu.clockChannelTimers();
            }

            if (frameSequencerDivider.clock()) {
                nes.apu.clockFrameSequencer();
            }

            float sample = nes.apu.mixerOutput();

            silenceDetector.addSample(sample);

            sampler.sample(scale);
        }

        return cycles;
    }
}
