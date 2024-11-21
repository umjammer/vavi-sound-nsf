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
import vavi.sound.nsf.festalon.Writer;


/**
 * ay.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 060911 nsano initial version <br>
 */
public class ay extends ExpSound {
    byte index;
    byte[] PSG = new byte[0x10];
    int[] vcount = new int[3];
    int[] dcount = new int[3];
    int[] CAYBC = new int[3];
    NesApu gapu;
    int disabled;

    /** */
    private Writer Mapper69_SWL = new Writer() {
        public void exec(int A, int V) {
            index = (byte) (V & 0xF);
        }
    };

    /** */
    private Writer Mapper69_SWH = new Writer() {
        public void exec(int A, int V) {
            int x;

            switch (index) {
            case 0:
            case 1:
            case 8:
                doAYSQHQ(0);
                break;
            case 2:
            case 3:
            case 9:
                doAYSQHQ(1);
                break;
            case 4:
            case 5:
            case 10:
                doAYSQHQ(2);
                break;
            case 7:
                for (x = 0; x < 2; x++) {
                    doAYSQHQ(x);
                }
                break;
            }
            PSG[index] = (byte) V;
        }
    };

    private void doAYSQHQ(int x) {
        int V;
        int freq = ((PSG[x << 1] | ((PSG[(x << 1) + 1] & 15) << 8)) + 1) << 4;
        int amp = (PSG[0x8 + x] & 15) << 6;
        int curout;
        int timestamp = gapu.cpu.timestamp;
        float[] WaveHi;
        amp += amp >> 1;

        if ((PSG[0x7] & (1 << x)) == 0 && (disabled & (0x1 << x)) == 0) {
            int vcount = this.vcount[x];
            int dcount = this.dcount[x];
            curout = this.dcount[x] * amp;

            WaveHi = gapu.waveHi;
            for (V = CAYBC[x]; V < timestamp; V++) {
                WaveHi[V] += curout;
                vcount--;

                if (vcount <= 0) {
                    dcount ^= 1;
                    curout ^= amp;
                    vcount = freq;
                }
            }
            this.vcount[x] = vcount;
            this.dcount[x] = dcount;
        }
        CAYBC[x] = gapu.cpu.timestamp;
    }

    public void fillHi() {
        doAYSQHQ(0);
        doAYSQHQ(1);
        doAYSQHQ(2);
    }

    public void syncHi(int ts) {
        int x;

        for (x = 0; x < 3; x++) {
            CAYBC[x] = ts;
        }
    }

    public void kill() {
    }

    public void disable(int mask) {
        disabled = mask;
    }

    public ay(NesApu apu) {
        gapu = apu;

        this.channels = 3;

        apu.cpu.setWriter(0xc000, 0xdfff, Mapper69_SWL, this);
        apu.cpu.setWriter(0xe000, 0xffff, Mapper69_SWH, this);
    }
}
