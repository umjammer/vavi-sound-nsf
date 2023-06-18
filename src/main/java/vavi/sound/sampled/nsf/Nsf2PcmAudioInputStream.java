/*
 * Copyright (c) 2020 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.sampled.nsf;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.logging.Level;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

import vavi.io.OutputEngine;
import vavi.io.OutputEngineInputStream;
import vavi.sound.nsf.nsf.NES;
import vavi.sound.nsf.nsf.NSFRenderer;
import vavi.sound.nsf.nsf.NSFRenderer.Sink;
import vavi.util.Debug;


/**
 * NSFAudioInputStream.
 * <pre>
 *  property
 *   disableChannels = [12tnq]*
 *   maxPlaySecs = number
 *   maxSilenceSecs = number
 *   splitChannels = boolean
 *   disableBandPass = boolean
 *   track = number
 * </pre>
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2020/10/27 umjammer initial version <br>
 */
public class Nsf2PcmAudioInputStream extends AudioInputStream {

    /** use format's properties */
    public Nsf2PcmAudioInputStream(InputStream stream, AudioFormat format, long length) throws IOException {
        this(stream, format, length, format.properties());
    }

    /** format's properties are ignored */
    public Nsf2PcmAudioInputStream(InputStream stream, AudioFormat format, long length, Map<String, Object> props) throws IOException {
        super(new OutputEngineInputStream(new NSFOutputEngine(stream, props)), format, length);
    }

    /** */
    private static class NSFOutputEngine implements OutputEngine {

        /** */
        private OutputStream out;

        /** */
        private NES nes;

        /** */
        private BlockingDeque<Byte> buffer = new LinkedBlockingDeque<>();

        int maxPlaySecs = 90;
        int maxSilenceSecs = 3;

        private Map<String, Object> props;

        private Thread maxThreadFactory(Runnable r) {
            Thread thread = new Thread(r);
            thread.setPriority(Thread.MAX_PRIORITY);
            return thread;
        }

        private ExecutorService executor = Executors.newSingleThreadExecutor(this::maxThreadFactory);

        /** */
        public NSFOutputEngine(InputStream in, Map<String, Object> props) throws IOException {
            this.nes = NES.buildForPathNoMemMonitor(in);
            this.props = props;

            if (props.containsKey("disableChannels")) {
                String d = ((String) props.get("disableChannels")).toLowerCase();
                if (d.contains("1")) {
                    nes.apu.setPulse1Enabled(false);
                }
                if (d.contains("2")) {
                    nes.apu.setPulse2Enabled(false);
                }
                if (d.contains("t")) {
                    nes.apu.setTriangleEnabled(false);
                }
                if (d.contains("n")) {
                    nes.apu.setNoiseEnabled(false);
                }
                if (d.contains("d")) {
                    nes.apu.setDmcEnabled(false);
                }
            }
        }

        private int trackNumber = 1;

        /** the thread which executes buffer#take() */
        private Thread blockingDequeThread;

        /** */
        public void initialize(OutputStream out) throws IOException {
            if (this.out != null) {
                throw new IOException("Already initialized");
            } else {
                this.out = new BufferedOutputStream(out);
            }

Debug.println(Level.FINE, props);
            if (props.containsKey("maxPlaySecs")) {
                maxPlaySecs = (int) props.get("maxPlaySecs");
            }

            if (props.containsKey("maxSilenceSecs")) {
                maxSilenceSecs = (int) props.get("maxSilenceSecs");
            }

            NSFRenderer renderer = new NSFRenderer(nes, maxPlaySecs, maxSilenceSecs);

            if (props.containsKey("splitChannels") && (boolean) props.get("splitChannels")) {
                renderer.splitChannels();
            }

            if (props.containsKey("disableBandPass") && (boolean) props.get("disableBandPass")) {
                renderer.disableBandPass();
            }

            if (props.containsKey("track")) {
                int t = (int) props.get("track");
                if (trackNumber >= 1 && trackNumber <= nes.nsf.header.totalSongs) {
                    trackNumber = t;
                }
            }

            executor.submit(() -> {
                try {
                    renderer.render(trackNumber, new Sink() {
                        @Override
                        public void write(byte b) {
                            try {
                                buffer.put(b);
                            } catch (InterruptedException e) {
                                e.printStackTrace(); // TODO consider more
                            }
                        }

                        @Override
                        public void finish() {
                            blockingDequeThread.interrupt();
Debug.println(Level.FINE, "sink finish");
                            try {
                                out.close(); // TODO wait write finished
                            } catch (IOException e) {
                                finish();
                                throw new UncheckedIOException(e);
                            }
                        }
                    });
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });

            try {
                // rendering is too slow, wait buffering
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            blockingDequeThread = Thread.currentThread();
        }

        static final int BUFFER_SIZE = 16;

        byte[] buf = new byte[BUFFER_SIZE];

        /** */
        public void execute() throws IOException {
            try {
                int c = 0;
                while (buffer.peek() != null || c++ < BUFFER_SIZE) {
                    out.write(buffer.take());
                }
                Thread.yield();
//Debug.println(Level.FINER, "write: " + i + ", " + buffer.size());
            } catch (InterruptedException e) {
Debug.println(Level.FINE, "BlockingDeque#take() interrupted");
            }
        }

        /** */
        public void finish() throws IOException {
Debug.println(Level.FINE, "engine finish");
            executor.shutdown();
        }
    }
}

/* */
