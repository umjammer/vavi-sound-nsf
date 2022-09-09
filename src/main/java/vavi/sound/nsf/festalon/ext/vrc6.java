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
 * vrc6.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 060911 nsano initial version <br>
 */
public class vrc6 extends ExpSound {
    private int[] cvbc = new int[3];
    private int[] vcount = new int[3];
    private int[] dcount = new int[2];
    private byte b3;
    private int phaseacc;
    private byte[] vpsg = new byte[8];
    private byte[] vpsg2 = new byte[4];
    private int disabled;
    private NesApu gapu;

    private Writer sWriter = (address, value) -> {

        address &= 0xF003;
        if (address >= 0x9000 && address <= 0x9002) {
            doSQV1HQ();
            vpsg[address & 3] = (byte) value;
        } else if (address >= 0xa000 && address <= 0xa002) {
            doSQV2HQ();
            vpsg[4 | (address & 3)] = (byte) value;
        } else if (address >= 0xb000 && address <= 0xb002) {
            doSawVHQ();
            vpsg2[address & 3] = (byte) value;
        }
    };

    private void doSQVHQ(int i) {

        int amp = ((vpsg[i << 2] & 15) << 8) * 6 / 8;

        if ((vpsg[(i << 2) | 0x2] & 0x80) != 0 && (disabled & (0x1 << i)) == 0) {
            if ((vpsg[i << 2] & 0x8) != 0) {
                for (int v = cvbc[i]; v < gapu.cpu.timestamp; v++) {
                    gapu.waveHi[v] += amp;
                }
            } else {
                int thresh = (vpsg[i << 2] >> 4) & 7;
                int curout;

                curout = 0;
                if (dcount[i] > thresh) { /* Greater than, not >=. Important. */
                    curout = amp;
                }

                for (int v = cvbc[i]; v < gapu.cpu.timestamp; v++) {
                    gapu.waveHi[v] += curout;
                    vcount[i]--;
                    if (vcount[i] <= 0) { /* Should only be <0 in a few circumstances. */

                        vcount[i] = (vpsg[(i << 2) | 0x1] | ((vpsg[(i << 2) | 0x2] & 15) << 8)) + 1;
                        dcount[i] = (dcount[i] + 1) & 15;
                        curout = 0;
                        if (dcount[i] > thresh) { /* Greater than, not >=. Important. */
                            curout = amp;
                        }
                    }
                }
            }
        }
        cvbc[i] = gapu.cpu.timestamp;
    }

    private void doSQV1HQ() {
        doSQVHQ(0);
    }

    private void doSQV2HQ() {
        doSQVHQ(1);
    }

    private void doSawVHQ() {
        int curout = (((phaseacc >> 3) & 0x1f) << 8) * 6 / 8;
        if ((vpsg2[2] & 0x80) != 0 && (disabled & 0x4) == 0) {
            for (int V = cvbc[2]; V < gapu.cpu.timestamp; V++) {
                gapu.waveHi[V] += curout;
                vcount[2]--;
                if (vcount[2] <= 0) {
                    vcount[2] = (vpsg2[1] + ((vpsg2[2] & 15) << 8) + 1) << 1;
                    phaseacc += vpsg2[0] & 0x3f;
                    b3++;
                    if (b3 == 7) {
                        b3 = 0;
                        phaseacc = 0;
                    }
                    curout = (((phaseacc >> 3) & 0x1f) << 8) * 6 / 8;
                }
            }
        }
        cvbc[2] = gapu.cpu.timestamp;
    }

    public void fillHi() {
        doSQV1HQ();
        doSQV2HQ();
        doSawVHQ();
    }

    public void syncHi(int ts) {
        for (int x = 0; x < 3; x++) {
            cvbc[x] = ts;
        }
    }

    public void kill() {
    }

    public void disable(int mask) {
        disabled = mask;
    }

    public vrc6(NesApu apu) {
        this.channels = 3;

        apu.cpu.setWriter(0x8000, 0xbfff, sWriter, this);
    }
}

/* */
