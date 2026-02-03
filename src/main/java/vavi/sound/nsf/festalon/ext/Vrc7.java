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
 * vrc7.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 060503 nsano initial version <br>
 */
public class Vrc7 extends ExpSound {

    /** */
    private final Opll ym;
    /** */
    private int bc;
    /** */
    private int index;
    /** */
    private int divC;
    /** */
    private int out;
    /** */
    private final NesApu gApu;

    @Override
    public void fillHi() {

        for (int V = bc; V < gApu.cpu.timestamp; V++) {
            if (divC == 0) {
                out = (ym.calc() + (2048 * 6)) << 1;
            }
            divC = (divC + 1) % 36;
            gApu.waveHi[V] += out;
        }

        bc = gApu.cpu.timestamp;
    }

    @Override
    public void syncHi(int ts) {
        bc = ts;
    }

    /** */
    private final Writer mapper85Writer = new Writer() {
        @Override
        public void exec(int address, int value) {

            address |= (address & 8) << 1;
            address &= 0xf030;

            if (address == 0x9030) {
                fillHi();
                ym.writeReg(index, value);
            } else if (address == 0x9010) {
                index = value;
            }
        }
    };

    @Override
    public void kill() {
    }

    @Override
    public void disable(int mask) {
        ym.setMask(mask);
    }

    /** */
    public Vrc7(NesApu apu) {

        this.divC = 0;
        apu.cpu.setWriter(0x9000, 0x9fff, mapper85Writer, this);

//      apu.x.setWriter(0x9010, 0x901F, mapper85Writer, this);
//      apu.x.setWriter(0x9030, 0x903F, mapper85Writer, this);

        this.ym = new Opll(3579545);
        this.gApu = apu;
        this.ym.reset();

        this.channels = 6;
    }
}
