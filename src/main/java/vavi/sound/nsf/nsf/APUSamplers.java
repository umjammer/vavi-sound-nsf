/*
 * https://github.com/orangelando/nsf
 */

package vavi.sound.nsf.nsf;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.Validate;

import vavi.sound.nsf.nsf.NSFRenderer.Sink;

import lando.nsf.apu.APU;


final class APUSamplers implements AutoCloseable {

    private final Sink sink;
    private final boolean disableBandPass;

    private final List<OutputStream> streams = new ArrayList<>();
    private final List<APUSamplePipe> samplers = new ArrayList<>();

    APUSamplers(Sink sink, boolean disableBandPass) {
        this.sink = Objects.requireNonNull(sink);
        this.disableBandPass = disableBandPass;
    }

    List<APUSamplePipe> getSamplers() {
        return samplers;
    }

    void setupSamplers(APU apu, boolean splitChannels) {

        Validate.notNull(apu);

        if (!splitChannels) {
            maybeAddSampler(apu::mixerOutput);
        } else {
            maybeAddSampler("p1",    apu.isPulse1Enabled(),   apu::pulse1Output);
            maybeAddSampler("p2",    apu.isPulse2Enabled(),   apu::pulse2Output);
            maybeAddSampler("tri",   apu.isTriangleEnabled(), apu::triangleOutput);
            maybeAddSampler("noise", apu.isNoiseEnabled(),    apu::noiseOutput);
            maybeAddSampler("dmc",   apu.isDmcEnabled(),      apu::dmcOutput);
        }
    }

    private void maybeAddSampler(APUSampleSupplier supplier) {
        maybeAddSampler(null, true, supplier);
    }

    private void maybeAddSampler(
            String channelName,
            boolean enabled,
            APUSampleSupplier supplier) {

        if (enabled) {
            APUSampleConsumer consumer = createSampleConsumer(sink);

            samplers.add(new APUSamplePipe(supplier, consumer));
        }
    }

    private APUSampleConsumer createSampleConsumer(Sink sink) {

        return new WavConsumer(sink, disableBandPass);
    }

    @Override
    public void close() throws IOException {
        IOException lastException = null;

        for (int i = streams.size() - 1; i >= 0; i--) {
            OutputStream s = streams.get(i);

            try {
                s.close();
            } catch(IOException e) {
                lastException = e;
            }
        }

        if (lastException != null) {
            throw lastException;
        }
    }
}
