/*
 * Festalon - NSF Player
 * Copyright (C) 2004 Xodnizel
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package vavi.sound.nsf.festalon.ext;

import vavi.sound.nsf.festalon.ExpSound;
import vavi.sound.nsf.festalon.NesApu;
import vavi.sound.nsf.festalon.Reader;
import vavi.sound.nsf.festalon.Writer;


/**
 * n106.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 060911 nsano initial version <br>
 */
public class n106 extends ExpSound {
    private byte[] iram = new byte[128];
    private byte dopol;
    private int[] freqCache = new int[8];
    private int[] envCache = new int[8];
    private int[] lengthCache = new int[8];
    private int[] playIndex = new int[8];
    private int[] vcount = new int[8];
    private int cvbc;
    private int disabled;
    private NesApu gapu;

    private Reader namco4800Reader = new Reader() {
        public int exec(int address, int dataBus) {

            byte ret = iram[dopol & 0x7f];

            /* Maybe I should call DoNamcoSoundHQ() here? */
            if ((dopol & 0x80) != 0) {
                dopol = (byte) ((dopol & 0x80) | ((dopol + 1) & 0x7f));
            }
            return ret;
        }
    };

    private void fixCache(int address, int value) {
        int w = (address >> 3) & 0x7;
        switch (address & 0x07) {
        case 0x00:
            freqCache[w] &= ~0x000000ff;
            freqCache[w] |= value;
            break;
        case 0x02:
            freqCache[w] &= ~0x0000ff00;
            freqCache[w] |= value << 8;
            break;
        case 0x04:
            freqCache[w] &= ~0x00030000;
            freqCache[w] |= (value & 3) << 16;
            lengthCache[w] = (8 - ((value >> 2) & 7)) << 2;
            break;
        case 0x07:
            envCache[w] = (int) ((double) (value & 0xf) * 576716);
            break;
        }

    }

    /** */
    private Writer mapper19Writer = new Writer() {
        public void exec(int address, int value) {
            address &= 0xf800;

            switch (address) {
            case 0x4800:
                if ((dopol & 0x40) != 0) {
                    fillHi();
                    fixCache(dopol, value);
                }
                iram[dopol & 0x7f] = (byte) value;

                if ((dopol & 0x80) != 0)
                    dopol = (byte) ((dopol & 0x80) | ((dopol + 1) & 0x7f));
                break;

            case 0xf800:
                dopol = (byte) value;
                break;
            }
        }
    };

    /** */
    private static final int TOINDEX = 16 + 1;

    /** 16:15 */
    public void syncHi(int ts) {
        cvbc = ts;
    }

    /*
     * Things to do: 1 Read freq low 2 Read freq mid 3 Read freq high 4 Read
     * envelope ...?
     */

    private int fetchDuff(byte[] IRAM, int P, int envelope, int PlayIndex) {
        int duff;
        duff = IRAM[((IRAM[0x46 + (P << 3)] + (PlayIndex >> TOINDEX)) & 0xFF) >> 1];
        if (((IRAM[0x46 + (P << 3)] + (PlayIndex >> TOINDEX)) & 1) != 0) {
            duff >>= 4;
        }
        duff &= 0xF;
        duff = (duff * envelope) >> 16;
        return duff;
    }

    public void fillHi() {
        int P, V;
        int cyclesuck;
        byte[] IRAM = this.iram;
        int timestamp = gapu.cpu.timestamp;
        cyclesuck = (((IRAM[0x7F] >> 4) & 7) + 1) * 15;

        for (P = 7; P >= (7 - ((IRAM[0x7F] >> 4) & 7)); P--) {
            int WaveHi = cvbc; // gapu.WaveHi
            if ((IRAM[0x44 + (P << 3)] & 0xE0) != 0 && (IRAM[0x47 + (P << 3)] & 0xF) != 0 && (disabled & (0x1 << P)) == 0) {
                int freq;
                int vco;
                int duff2, lengo, envelope;
                int PlayIndex;

                vco = vcount[P];
                freq = freqCache[P];
                envelope = envCache[P];
                lengo = lengthCache[P];
                PlayIndex = this.playIndex[P];

                duff2 = fetchDuff(IRAM, P, envelope, PlayIndex);

                // V = timestamp - CVBC;
                for (V = cvbc; V < timestamp; V++) {
                // for(;V;V--)

                    gapu.waveHi[WaveHi] += duff2;
                    if (vco == 0) {
                        PlayIndex += freq;
                        while ((PlayIndex >> TOINDEX) >= lengo)
                            PlayIndex -= lengo << TOINDEX;
                        duff2 = fetchDuff(IRAM, P, envelope, PlayIndex);
                        vco = cyclesuck;
                    }
                    vco--;

                    gapu.waveHi[WaveHi] += duff2;
                    if (vco == 0) {
                        PlayIndex += freq;
                        while ((PlayIndex >> TOINDEX) >= lengo)
                            PlayIndex -= lengo << TOINDEX;
                        duff2 = fetchDuff(IRAM, P, envelope, PlayIndex);
                        vco = cyclesuck;
                    }
                    WaveHi++;
                    vco--;
                }
                vcount[P] = vco;
                this.playIndex[P] = PlayIndex;
            }
        }
        cvbc = timestamp;
    }

    public void kill() {
    }

    public void disable(int mask) {
        disabled = mask;
    }

    public n106(NesApu apu) {

        this.gapu = apu;

        apu.cpu.setWriter(0xf800, 0xffff, mapper19Writer, this);
        apu.cpu.setWriter(0x4800, 0x4fff, mapper19Writer, this);
        apu.cpu.setReader(0x4800, 0x4fff, namco4800Reader, this);

        this.channels = 8;
    }
}

/* */
