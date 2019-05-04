/*
 * Copyright (c) 2019 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.nsf;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

import vavi.util.Debug;
import vavi.util.StringUtil;

import static org.junit.jupiter.api.Assertions.*;


/**
 * NsfTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2019/04/18 umjammer initial version <br>
 */
class NsfTest {

    @Test
    void test() {
        fail("Not yet implemented");
    }

    // ----

    /** */
    public static void main(String[] args) throws Exception {
        File file = new File(args[0]);
        byte[] buffer = new byte[(int) file.length()];
        InputStream is = new FileInputStream(file);
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
        nsf.setVolume(100);
        nsf.setLowpass(false, 0, 0);
        int[] r = new int[1];
        do {
            float[] wave = nsf.emulate(r);
Debug.println("wave: " + (wave != null ? wave.length : null));
        } while (r[0] > 0);

//        AudioFormat audioFormat = new AudioFormat(22000, 8, 1, true, false);
//
//        DataLine.Info info = new DataLine.Info(Clip.class, audioFormat);
//        Clip clip = (Clip) AudioSystem.getLine(info);
//
//        clip.open(audioFormat, wave, 0, wave.length);
//        clip.setFramePosition(0);
//
//        clip.start();
//        clip.drain();
//        while (clip.isRunning()) {
//            try {
//                Thread.sleep(200);
//            } catch (InterruptedException e) {
//                e.printStackTrace(System.err);
//            }
//        }
//        clip.close();
    }
}

/* */
