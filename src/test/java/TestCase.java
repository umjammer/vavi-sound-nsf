/*
 * Copyright (c) 2012 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

import java.io.BufferedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioFormat.Encoding;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.SourceDataLine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vavi.sound.sampled.nsf.Nsf2PcmAudioInputStream;
import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import static vavi.sound.SoundUtil.volume;


/**
 * TestCase.
 *
 * clip takes a bit time for loading all, so not tested
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2012/06/11 umjammer initial version <br>
 */
@PropsEntity(url = "file:local.properties")
public class TestCase {

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    @Property(name = "nsf")
    String inFile = "src/test/resources/smb1.nsf";

    @Property(name = "vavi.test.volume")
    double volume = 0.2;

    @BeforeEach
    void setup() throws Exception {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
Debug.println("volume: " + volume);
for (AudioFileFormat.Type type : AudioSystem.getAudioFileTypes()) {
 System.err.println(type);
}
    }

    static int time = System.getProperty("vavi.test", "").equals("ide") ? 1000 : 10;

    @Test
    @DisplayName("directly")
    void test0() throws Exception {
        Map<String, Object> props = new HashMap<>();
        props.put("track", 5);
        props.put("maxPlaySecs", time);

        AudioFormat targetAudioFormat = new AudioFormat(
                Encoding.PCM_SIGNED,
                44100,
                16,
                1,
                2,
                44100,
                false,
                props);

        AudioInputStream ais = new Nsf2PcmAudioInputStream(Files.newInputStream(Paths.get(inFile)), targetAudioFormat, -1);
Debug.println(targetAudioFormat);

        DataLine.Info info = new DataLine.Info(SourceDataLine.class, targetAudioFormat, AudioSystem.NOT_SPECIFIED);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
Debug.println(line.getClass().getName());
        line.addLineListener(event -> Debug.println(event.getType()));
Debug.println("buffer size: " + line.getBufferSize());

        line.open(targetAudioFormat);
        volume(line, volume);
        line.start();
        int r;
        byte[] buf = new byte[line.getBufferSize()];
        while (true) {
            r = ais.read(buf, 0, buf.length);
            if (r < 0) {
                break;
            }
//Debug.println("line: " + line.available());
            line.write(buf, 0, r);
        }
        line.drain();
        line.close();

        ais.close();
    }

    @Test
    @DisplayName("use spi")
    void test1() throws Exception {
        Map<String, Object> props = new HashMap<>();
        props.put("track", 1);
        props.put("maxPlaySecs", time);

        AudioFormat targetAudioFormat = new AudioFormat(
                Encoding.PCM_SIGNED,
                44100,
                16,
                1,
                2,
                44100,
                false,
                props);

        AudioInputStream sourceAis = AudioSystem.getAudioInputStream(new BufferedInputStream(Files.newInputStream(Path.of(inFile))));
        AudioFormat sourceAudioFormat = sourceAis.getFormat();
Debug.println(sourceAudioFormat);

        AudioInputStream ais = AudioSystem.getAudioInputStream(targetAudioFormat, sourceAis);

        DataLine.Info info = new DataLine.Info(SourceDataLine.class, targetAudioFormat, AudioSystem.NOT_SPECIFIED);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
Debug.println(line.getClass().getName());
        line.addLineListener(event -> Debug.println(event.getType()));
Debug.println("buffer size: " + line.getBufferSize());

        line.open(targetAudioFormat);
        volume(line, volume);
        line.start();
        int r;
        byte[] buf = new byte[line.getBufferSize()];
        while (true) {
            r = ais.read(buf, 0, buf.length);
            if (r < 0) {
                break;
            }
//Debug.println("line: " + line.available());
            line.write(buf, 0, r);
        }
        line.drain();
        line.close();

        ais.close();
    }
}
