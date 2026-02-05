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
 * mmc5.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 060911 nsano initial version <br>
 */
public class Mmc5 extends ExpSound {

    private final int[] wl = new int[2];
    private final byte[] env = new byte[2];
    private byte enable;
    private byte running;
    private byte raw;
    private byte rawcontrol;
    private final byte[] mul = new byte[2];
    private final byte[] exRam = new byte[1024];
    private final int[] dcount = new int[2];
    private final int[] bc = new int[3];
    private final int[] vcount = new int[2];
    private int disabled;
    private final NesApu gapu;

    private final Writer mapper5Writer = (address, value) -> {
        switch (address) {
        case 0x5205:
            mul[0] = (byte) value;
            break;
        case 0x5206:
            mul[1] = (byte) value;
            break;
        }
    };

    private final Writer exRamWriter = (address, value) -> exRam[address & 0x3ff] = (byte) value;

    private final Reader exRamReader = (address, dataBus) -> exRam[address & 0x3ff] & 0xff;

    private final Reader reader = (address, dataBus) -> switch (address) {
        case 0x5205 -> (mul[0] & 0xff) * (mul[1] & 0xff);
        case 0x5206 -> ((mul[0] & 0xff) * (mul[1] & 0xff)) >> 8;
        default -> dataBus;
    };

    private void do5PCMHQ() {
        int v;
        if ((rawcontrol & 0x40) == 0 && raw != 0 && (disabled & 0x4) == 0) {
            for (v = bc[2]; v < gapu.cpu.timestamp; v++) {
                gapu.waveHi[v] += raw << 5;
            }
        }
        bc[2] = gapu.cpu.timestamp;
    }

    /** */
    private final Writer mapper5SWriter = new Writer() {
        @Override
        public void exec(int address, int value) {

            address &= 0x1F;

            switch (address) {
            case 0x10:
                do5PCMHQ();
                rawcontrol = (byte) value;
                break;
            case 0x11:
                do5PCMHQ();
                raw = (byte) value;
                break;

            case 0x0:
            case 0x4:
                do5SQHQ(address >> 2);
                env[address >> 2] = (byte) value;
                break;
            case 0x2:
            case 0x6:
                do5SQHQ(address >> 2);
                wl[address >> 2] &= ~0x00FF;
                wl[address >> 2] |= value & 0xFF;
                break;
            case 0x3:
            case 0x7:// printf("%04x:$%02x\n",A,V>>3);
                wl[address >> 2] &= ~0x0700;
                wl[address >> 2] |= (value & 0x07) << 8;
                running |= 1 << (address >> 2);
                break;
            case 0x15:
                do5SQHQ(0);
                do5SQHQ(1);
                running &= value;
                enable = (byte) value;
                break;
            }
        }
    };

    private void do5SQHQ(int p) {
        int[] tal = {
            1, 2, 4, 6
        };
        int V, amp, rthresh, wl;

        wl = this.wl[p] + 1;
        amp = ((env[p] & 0xF) << 8);
        rthresh = tal[(env[p] & 0xC0) >> 6];

        if (wl >= 8 && (running & (p + 1)) != 0 && (disabled & (0x1 << p)) == 0) {
            int dc, vc;
            int curout;
            wl <<= 1;

            dc = dcount[p];
            vc = vcount[p];

            curout = 0;
            if (dc < rthresh)
                curout = amp;

            for (V = bc[p]; V < gapu.cpu.timestamp; V++) {
                gapu.waveHi[V] += curout;
                // if(dc<rthresh)
                // gapu.WaveHi[V]+=amp;
                vc--;
                if (vc <= 0) /* Less than zero when first started. */
                {
                    vc = wl;
                    dc = (dc + 1) & 7;
                    curout = 0;
                    if (dc < rthresh)
                        curout = amp;
                }
            }
            dcount[p] = dc;
            vcount[p] = vc;
        }
        bc[p] = gapu.cpu.timestamp;
    }

    @Override
    public void fillHi() {
        do5SQHQ(0);
        do5SQHQ(1);
        do5PCMHQ();
    }

    @Override
    public void syncHi(int ts) {
        int x;
        for (x = 0; x < 3; x++)
            bc[x] = ts;
    }

    @Override
    public void kill() {
    }

    @Override
    public void disable(int mask) {
        disabled = mask;
    }

    public Mmc5(NesApu apu) {

        gapu = apu;

        this.channels = 3;

        apu.cpu.setWriter(0x5c00, 0x5fef, exRamWriter, this);
        apu.cpu.setReader(0x5c00, 0x5fef, exRamReader, this);
        apu.cpu.setWriter(0x5000, 0x5015, mapper5SWriter, this);
        apu.cpu.setWriter(0x5205, 0x5206, mapper5Writer, this);
        apu.cpu.setReader(0x5205, 0x5206, reader, this);
    }
}
