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
 * fds.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 060911 nsano initial version <br>
 */
public class Fds extends ExpSound {

    /** */
    private static final double FDSClock = 1789772.7272727272727272 / 2;

    /** Cycles per PCM sample */
    private final long cycles;
    /** Cycle counter */
    private long count;
    /** Envelope cycle counter */
    private long envCount;
    private int b19ShiftReg60;
    private int b24adder66;
    private int b24latch68;
    private int b17latch76;
    /** Counter to divide frequency by 8. */
    private int clockCount;
    /** Modulation register. */
    private byte b8ShiftReg88;
    /** Current amplitudes. */
    private final byte[] amplitude = new byte[2];
    private final byte[] speedO = new byte[2];
    private byte mwCount;
    private byte mwStart;
    /** Modulation waveform */
    private final byte[] mWave = new byte[0x20];
    /** Game-defined waveform(carrier) */
    private final byte[] cWave = new byte[0x40];
    private final byte[] sPsg = new byte[0xB];
    private int fbc;
    private final int[] countO = new int[2];
    private int disabled;
    private int curOut;
    private final NesApu gApu;

    /** */
    private void redoCO() {
        int k = amplitude[0];
        if (k > 0x20) {
            k = 0x20;
        }
        curOut = (cWave[b24latch68 >> 19] * k) * 4 / ((sPsg[0x9] & 0x3) + 2);
    }

    /** */
    private final Reader sReader = (address, dataBus) -> switch (address & 0xf) {
        case 0x0 -> (amplitude[0] | (dataBus & 0xc0));
        case 0x2 -> (amplitude[1] | (dataBus & 0xc0));
        default -> dataBus;
    };

    /** */
    private final Writer sWriter = new Writer() {
        @Override
        public void exec(int address, int value) {
            fillHi();

            address -= 0x4080;
            switch (address) {
            case 0x0:
            case 0x4:
                if ((value & 0x80) != 0) {
                    amplitude[(address & 0xF) >> 2] = (byte) (value & 0x3F); // )>0x20?0x20:(V&0x3F);
                }
                break;
            case 0x5:
                // printf("$%04x:$%02x\n",A,V);
                break;
            case 0x7:
                b17latch76 = 0;
                sPsg[0x5] = 0;
                // printf("$%04x:$%02x\n",A,V);
                break;
            case 0x8:
                b17latch76 = 0;
                // printf("%d:$%02x, $%02x\n",SPSG[0x5],V,b17latch76);
                mWave[sPsg[0x5] & 0x1F] = (byte) (value & 0x7);
                sPsg[0x5] = (byte) ((sPsg[0x5] + 1) & 0x1F);
                break;
            }
            // if(A>=0x7 && A!=0x8 && A<=0xF)
            // if(A==0xA || A==0x9)
            // printf("$%04x:$%02x\n",A,V);
            sPsg[address] = (byte) value;

            if (address == 0x9) {
                redoCO();
            }
        }
    };

    // $4080 - Fundamental wave amplitude data register 92
    // $4082 - Fundamental wave frequency data register 58
    // $4083 - Same as $4082($4083 is the upper 4 bits).

    // $4084 - Modulation amplitude data register 78
    // $4086 - Modulation frequency data register 72
    // $4087 - Same as $4086($4087 is the upper 4 bits)

    /** */
    private void doEnv() {
        int x;

        for (x = 0; x < 2; x++) {
            if ((sPsg[x << 2] & 0x80) == 0 && (sPsg[0x3] & 0x40) == 0) {
                if (countO[x] <= 0) {
                    if ((sPsg[x << 2] & 0x80) == 0) {
                        if ((sPsg[x << 2] & 0x40) != 0) {
                            if (amplitude[x] < 0x3F) {
                                amplitude[x]++;
                            }
                        } else {
                            if (amplitude[x] > 0) {
                                amplitude[x]--;
                            }
                        }
                    }
                    countO[x] = (sPsg[x << 2] & 0x3F);
                    if (x == 0) {
                        redoCO();
                    }
                } else {
                    countO[x]--;
                }
            }
        }
    }

    /** */
    private final Reader waveReader = (address, dataBus) -> (cWave[address & 0x3f] | (dataBus & 0xc0));

    /** */
    private final Writer waveWriter = (address, value) -> {
        // printf("$%04x:$%02x, %d\n",A,V,SPSG[0x9]&0x80);
        if ((sPsg[0x9] & 0x80) != 0) {
            cWave[address & 0x3f] = (byte) (value & 0x3F);
        }
    };

    /** */
    private void clockRisen() {
        if (clockCount == 0) {
            b19ShiftReg60 = (sPsg[0x2] | ((sPsg[0x3] & 0xF) << 8));
            b17latch76 = (sPsg[0x6] | ((sPsg[0x07] & 0xF) << 8)) + b17latch76;

            if ((sPsg[0x7] & 0x80) == 0) {
                int t = mWave[(b17latch76 >> 13) & 0x1f] & 7;
                int t2 = amplitude[1];

                if (t2 > 0x20)
                    t2 = 0x20;

                /*
                 * if(t&4) { if(t==4) t=0; else t=t2*((3-t)&3); t=0x80-t; } else {
                 * t=t2*(t&3); t=0x80+t; }
                 */
                t = 0x80 + t2 * t / 2;

                // if(t&4) {
                // t=0x80-((t&3))*t2;
                // } else {
                // t=0x80+((t&3))*t2; //(0x80+(t&3))*(amplitude[1]); //t;
                // }
                // //amplitude[1]*3; //t;
                // //(amplitude[1])*(fdso.mWave[(b17latch76>>13)&0x1F]&7);

                b8ShiftReg88 = (byte) t; // (t+0x80)*(amplitude[1]);

                // t=0;
                // t=(t-4)*(amplitude[1]);
                // FCEU_DispMessage("%d",amplitude[1]);
                // b8ShiftReg88=((fdso.mWave[(b17latch76>>11)&0x1F]&7))|(amplitude[1]<<3);
            } else {
                b8ShiftReg88 = (byte) 0x80;
            }
            // b8ShiftReg88=0x80;
        } else {
            b19ShiftReg60 <<= 1;
            b8ShiftReg88 >>= 1;
        }
        // b24adder66=(b24latch68+b19ShiftReg60)&0x3FFFFFF;
        b24adder66 = (b24latch68 + b19ShiftReg60) & 0x1FFFFFF;
    }

    /** */
    private void clockFallen() {
        // if(!(SPSG[0x7]&0x80))
        {
            if ((b8ShiftReg88 & 1) != 0) // || clockCount==7)
            {
                b24latch68 = b24adder66;
                redoCO();
            }
        }
        clockCount = (clockCount + 1) & 7;
    }

    /** */
    private int doSound() {
        count += cycles;
        if (count >= ((long) 1 << 40)) {
            count -= (long) 1 << 40;
            clockRisen();
            clockFallen();
            envCount--;
            if (envCount <= 0) {
                envCount += sPsg[0xA] * 3;
                doEnv();
            }
        }
        if (count >= 32768) {
            count -= (long) 1 << 40;
            clockRisen();
            clockFallen();
            envCount--;
            if (envCount <= 0) {
                envCount += sPsg[0xA] * 3;
                doEnv();
            }
        }

        return curOut;
    }

    @Override
    public void fillHi() {
        int x;

        if ((sPsg[0x9] & 0x80) == 0 && (disabled & 0x1) == 0) {
            for (x = fbc; x < gApu.cpu.timestamp; x++) {
                int t = doSound();
                t += t >> 1;
                gApu.waveHi[x] += t;
            }
        }
        fbc = gApu.cpu.timestamp;
    }

    @Override
    public void syncHi(int ts) {
        fbc = ts;
    }

    @Override
    public void kill() {
    }

    @Override
    public void disable(int mask) {
        disabled = mask;
    }

    /** */
    public Fds(NesApu apu) {

        this.cycles = (long) 1 << 39;
        this.gApu = apu;

        apu.cpu.setReader(0x4040, 0x407f, waveReader, this);
        apu.cpu.setWriter(0x4040, 0x407f, waveWriter, this);
        apu.cpu.setWriter(0x4080, 0x408A, sWriter, this);
        apu.cpu.setReader(0x4090, 0x4092, sReader, this);

        this.channels = 1;
    }
}
