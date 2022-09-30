/*
 * Copyright (c) 2012 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

import java.io.BufferedInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.SourceDataLine;

import org.junit.jupiter.api.Test;
import vavi.sound.sampled.nsf.Nsf2PcmAudioInputStream;
import vavi.util.Debug;

import static vavi.sound.SoundUtil.volume;


/**
 * clip.
 * <p>
 * read all input stream then play
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2012/06/11 umjammer initial version <br>
 */
public class Test1 {

    static final String inFile = "src/test/resources/smb1.nsf";

    @Test
    void test1() throws Exception {
        t0(new String[] {inFile});
    }

    /**
     * @param args
     */
    public static void main(String[] args) throws Exception {
        t0(args);
    }

    /* directly */
    public static void t1(String[] args) throws Exception {
        for (AudioFileFormat.Type type : AudioSystem.getAudioFileTypes()) {
            System.err.println(type);
        }
        AudioFormat audioFormat = new AudioFormat(
            44100,
            16,
            1,
            true,
            false);
        audioFormat.properties().put("track", 1);
        AudioInputStream audioInputStream = new Nsf2PcmAudioInputStream(Files.newInputStream(Paths.get(inFile)), audioFormat, -1);
Debug.println(audioFormat);
        DataLine.Info info = new DataLine.Info(Clip.class, audioFormat, AudioSystem.NOT_SPECIFIED);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Clip clip = (Clip) AudioSystem.getLine(info);
Debug.println(clip.getClass().getName());
        clip.addLineListener(event -> {
System.err.println(event.getType());
            if (event.getType().equals(LineEvent.Type.STOP)) {
                countDownLatch.countDown();
            }
        });
        clip.open(audioInputStream);
//volume(clip, .2d);
        clip.start();
        countDownLatch.await();
        clip.close();
        audioInputStream.close();
    }

    public static void t3(String[] args) throws Exception {
        AudioFormat audioFormat = new AudioFormat(
            44100,
            16,
            1,
            true,
            false);
        audioFormat.properties().put("track", 5);
        audioFormat.properties().put("maxPlaySecs", 30);
        AudioInputStream originalAudioInputStream = new Nsf2PcmAudioInputStream(Files.newInputStream(Paths.get(inFile)), audioFormat, -1);
        AudioFormat originalAudioFormat = originalAudioInputStream.getFormat();
Debug.println(originalAudioFormat);
        AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(audioFormat, originalAudioInputStream);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat, AudioSystem.NOT_SPECIFIED);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
Debug.println(line.getClass().getName());
        line.addLineListener(event -> Debug.println(event.getType()));
Debug.println("buffer size: " + line.getBufferSize());

        line.open(audioFormat);
        byte[] buf = new byte[line.getBufferSize()];
volume(line, .2d);
        line.start();
        int r = 0;
        while (true) {
            r = audioInputStream.read(buf, 0, buf.length);
            if (r < 0) {
                break;
            }
//Debug.println("line: " + line.available());
            line.write(buf, 0, r);
        }
        line.drain();
        line.close();
        audioInputStream.close();
    }

    /* use spi */
    public static void t0(String[] args) throws Exception {
        AudioFormat audioFormat = new AudioFormat(
            44100,
            16,
            1,
            true,
            false);
        Map<String, Object> props = new HashMap<>();
        props.put("track", 5);
        props.put("maxPlaySecs", 30);
        AudioInputStream originalAudioInputStream = AudioSystem.getAudioInputStream(new BufferedInputStream(Files.newInputStream(Paths.get(inFile))));
        AudioFormat originalAudioFormat = originalAudioInputStream.getFormat();
Debug.println(originalAudioFormat);
        AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(audioFormat, originalAudioInputStream);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat, AudioSystem.NOT_SPECIFIED);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
Debug.println(line.getClass().getName());
        line.addLineListener(event -> Debug.println(event.getType()));

        line.open(audioFormat);
        byte[] buf = new byte[line.getBufferSize()];
volume(line, .2d);
        line.start();
        int r = 0;
        while (true) {
            r = audioInputStream.read(buf, 0, buf.length);
            if (r < 0) {
                break;
            }
//Debug.println("line: " + line.available());
            line.write(buf, 0, r);
        }
        line.drain();
        line.close();
        audioInputStream.close();
    }
}

/* */
