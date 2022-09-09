/*
 * Copyright (c) 2019 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.nsf.festalon;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineListener;
import javax.sound.sampled.SourceDataLine;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import vavi.util.Debug;
import vavi.util.StringUtil;

import static org.junit.jupiter.api.Assertions.fail;
import static vavi.sound.SoundUtil.volume;


/**
 * NsfTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2019/04/18 umjammer initial version <br>
 */
class NsfTest {

    @Test
    @Disabled
    void test() {
        fail("Not yet implemented");
    }

    // ----

    /** */
    public static void main(String[] args) throws Exception {
//        String fn = args[0];
        String fn = "src/test/resources/smb1.nsf";
        File file = new File(fn);
        byte[] buffer = new byte[(int) file.length()];
        InputStream is = Files.newInputStream(file.toPath());
        int l = 0;
        while (l < buffer.length) {
            int r = is.read(buffer, l, buffer.length - l);
            if (r < 0) {
                is.close();
                throw new EOFException();
            }
            l += r;
        }
        is.close();
        Nsf nsf = (Nsf) Nsf.load(buffer, buffer.length);
Debug.println("nsf: " + StringUtil.paramString(nsf));
        nsf.setSound(22000, 1);
        nsf.disable(0);
        nsf.setVolume(100);
        nsf.setLowpass(false, 0, 0);

//        Arrays.asList(nsf.songNames).forEach(System.err::println);

        CountDownLatch cdl = new CountDownLatch(1);

        AudioFormat audioFormat = new AudioFormat(22000, 8, 1, true, true);

        DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(audioFormat);
        volume(line, .2d);
        line.addLineListener(ev -> {
            if (LineEvent.Type.STOP == ev.getType()) {
                cdl.countDown();
            }
        });
//        line.start();

        int[] r = new int[1];
        do {
            float[] wave = nsf.emulate(r);
Debug.printf("%04x, wave: %d", r[0], (wave != null ? wave.length : null));

            ByteBuffer bb = ByteBuffer.allocate(4 * wave.length);
            for (float value : wave){
                bb.putFloat(value);
            }
            byte[] bytes = bb.array();
            line.write(bytes, 0, bytes.length);
        } while (r[0] > 0);

        line.drain();
        line.stop();
        cdl.await();
        line.close();
    }
}

/* */
