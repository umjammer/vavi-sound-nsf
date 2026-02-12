/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.sampled.nsf;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

import vavi.io.OutputEngine;
import vavi.io.OutputEngineInputStream;
import vavi.sound.nsf.festalon.Nsf;

import static java.lang.System.getLogger;


/**
 * Festalon2PcmAudioInputStream.
 * <pre>
 *  property
 *   track = number
 * </pre>
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2026/02/12 umjammer initial version <br>
 */
public class Festalon2PcmAudioInputStream extends AudioInputStream {

    private static final Logger logger = getLogger(Festalon2PcmAudioInputStream.class.getName());

    /** use format's properties */
    public Festalon2PcmAudioInputStream(InputStream stream, AudioFormat format, long length) throws IOException {
        this(stream, format, length, format.properties());
    }

    /** format's properties are ignored */
    public Festalon2PcmAudioInputStream(InputStream stream, AudioFormat format, long length, Map<String, Object> props) throws IOException {
        super(new OutputEngineInputStream(new NSFOutputEngine(stream, props)), format, length);
    }

    /** */
    private static class NSFOutputEngine implements OutputEngine {

        /** */
        private OutputStream out;

        /** */
        private final Nsf nsf;

        private final Map<String, Object> props;

        private static Thread maxThreadFactory(Runnable r) {
            Thread thread = new Thread(r);
            thread.setPriority(Thread.MAX_PRIORITY);
            return thread;
        }

        private final ExecutorService executor = Executors.newSingleThreadExecutor(NSFOutputEngine::maxThreadFactory);

        /** */
        public NSFOutputEngine(InputStream in, Map<String, Object> props) throws IOException {
            this.props = props;

            byte[] buffer = in.readAllBytes();
            this.nsf = (Nsf) Nsf.load(buffer, buffer.length);
        }

        @Override
        public void initialize(OutputStream out) throws IOException {
            if (this.out != null) {
                throw new IOException("Already initialized");
            } else {
                this.out = new BufferedOutputStream(out);
            }

logger.log(Level.DEBUG, "props from target AudioFormat: " + props);

            int trackNumber = 0;
            if (props.containsKey("track")) {
                int t = (int) props.get("track");
                if (t >= 1 && t <= nsf.totalSongs) {
                    trackNumber = t - 1;
                }
            }

            nsf.controlSong(trackNumber);
logger.log(Level.TRACE, "Total Songs: " + nsf.totalSongs + ", Starting Song: " + nsf.startingSong);
            nsf.setSound(44100, 1);
            nsf.disable(0);

            nsf.setVolume(100);
            nsf.setLowPass(false, 0, 0);
        }

        @Override
        public void execute() throws IOException {
            int[] r = new int[1];
            float[] wave = nsf.emulate(r);
            if (r[0] > 0) {
                int validSamples = r[0];
                if (wave != null) {

                    ByteBuffer bb = ByteBuffer.allocate(2 * validSamples).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < validSamples; i++) {
                        // Convert 0.0-1.0 to -32768 to 32767
                        short s = (short) ((wave[i] - 0.5f) * 65535.0f);
                        bb.putShort(s);
                    }
                    byte[] bytes = bb.array();
                    out.write(bytes, 0, bytes.length);
                }

            } else {
                out.close();
            }
        }

        @Override
        public void finish() throws IOException {
logger.log(Level.DEBUG, "engine finish");
        }
    }
}
