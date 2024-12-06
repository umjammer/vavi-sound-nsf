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
import java.util.concurrent.CountDownLatch;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.SourceDataLine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import vavi.util.Debug;
import vavi.util.StringUtil;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import static vavi.sound.SoundUtil.volume;


/**
 * NsfTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2019/04/18 umjammer initial version <br>
 */
@PropsEntity(url = "file:local.properties")
class NsfTest {

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    @Property(name = "nsf")
    String in = "src/test/resources/test.nsf";

    @Property(name = "track")
    int track = 0;

    @Property(name = "vavi.test.volume")
    double volume = 0.2;

    @BeforeEach
    void setup() throws Exception {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
Debug.println("volume: " + volume);
    }

    static boolean onIde = System.getProperty("vavi.test", "").equals("ide");
    static long time = onIde ? 1000 * 1000 : 10 * 1000;

    @Test
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ide")
    void test() throws Exception {
Debug.println(in);
        byte[] buffer = Files.newInputStream(Path.of(in)).readAllBytes();

        Nsf nsf = (Nsf) Nsf.load(buffer, buffer.length);
Debug.println("nsf: " + StringUtil.paramString(nsf));
        nsf.setSound(22000, 1);
        nsf.disable(0);
        nsf.setVolume(100);
        nsf.setLowPass(false, 0, 0);

//        Arrays.asList(nsf.songNames).forEach(System.err::println);

        CountDownLatch cdl = new CountDownLatch(1);

        AudioFormat audioFormat = new AudioFormat(22000, 16, 1, true, true);

        DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(audioFormat);
        volume(line, volume);
        line.addLineListener(ev -> { if (LineEvent.Type.STOP == ev.getType()) cdl.countDown(); });
        line.start();

        int[] r = new int[1];
        do {
            float[] wave = nsf.emulate(r);
//Debug.printf("count: %04x, wave: %d", r[0], (wave != null ? wave.length : null));

            ByteBuffer bb = ByteBuffer.allocate(2 * wave.length).order(ByteOrder.BIG_ENDIAN);
            ShortBuffer sb = bb.asShortBuffer();
            for (float value : wave) {
                sb.put((short) value);
            }
            byte[] bytes = bb.array();
//Debug.println("\n" + StringUtil.getDump(bytes, 64));
            line.write(bytes, 0, bytes.length);
        } while (r[0] > 0);

        line.drain();
        line.stop();
        cdl.await();
        line.close();
    }

    // ----

    /**
     * @param args 0: .nes file
     */
    public static void main(String[] args) throws Exception {
        NsfTest app = new NsfTest();
        app.setup();
        if (args.length > 0) app.in = args[0];
        app.test();
    }
}
