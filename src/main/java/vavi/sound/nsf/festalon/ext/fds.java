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
public class fds extends ExpSound {

    /** */
    private static final double FDSClock = 1789772.7272727272727272 / 2;

    /** Cycles per PCM sample */
    private long cycles;
    /** Cycle counter */
    private long count;
    /** Envelope cycle counter */
    private long envcount;
    private int b19shiftreg60;
    private int b24adder66;
    private int b24latch68;
    private int b17latch76;
    /** Counter to divide frequency by 8. */
    private int clockcount;
    /** Modulation register. */
    private byte b8shiftreg88;
    /** Current amplitudes. */
    private byte[] amplitude = new byte[2];
    private byte[] speedo = new byte[2];
    private byte mwcount;
    private byte mwstart;
    /** Modulation waveform */
    private byte[] mwave = new byte[0x20];
    /** Game-defined waveform(carrier) */
    private byte[] cwave = new byte[0x40];
    private byte[] spsg = new byte[0xB];
    private int fbc;
    private int[] counto = new int[2];
    private int disabled;
    private int curout;
    private NesApu gapu;

    /** */
    private void redoCO() {
        int k = amplitude[0];
        if (k > 0x20) {
            k = 0x20;
        }
        curout = (cwave[b24latch68 >> 19] * k) * 4 / ((spsg[0x9] & 0x3) + 2);
    }

    /** */
    private Reader sReader = (address, dataBus) -> {

        return switch (address & 0xf) {
            case 0x0 -> (amplitude[0] | (dataBus & 0xc0));
            case 0x2 -> (amplitude[1] | (dataBus & 0xc0));
            default -> dataBus;
        };
    };

    /** */
    private Writer sWriter = new Writer() {
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
                spsg[0x5] = 0;
                // printf("$%04x:$%02x\n",A,V);
                break;
            case 0x8:
                b17latch76 = 0;
                // printf("%d:$%02x, $%02x\n",SPSG[0x5],V,b17latch76);
                mwave[spsg[0x5] & 0x1F] = (byte) (value & 0x7);
                spsg[0x5] = (byte) ((spsg[0x5] + 1) & 0x1F);
                break;
            }
            // if(A>=0x7 && A!=0x8 && A<=0xF)
            // if(A==0xA || A==0x9)
            // printf("$%04x:$%02x\n",A,V);
            spsg[address] = (byte) value;

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
            if ((spsg[x << 2] & 0x80) == 0 && (spsg[0x3] & 0x40) == 0) {
                if (counto[x] <= 0) {
                    if ((spsg[x << 2] & 0x80) == 0) {
                        if ((spsg[x << 2] & 0x40) != 0) {
                            if (amplitude[x] < 0x3F) {
                                amplitude[x]++;
                            }
                        } else {
                            if (amplitude[x] > 0) {
                                amplitude[x]--;
                            }
                        }
                    }
                    counto[x] = (spsg[x << 2] & 0x3F);
                    if (x == 0) {
                        redoCO();
                    }
                } else {
                    counto[x]--;
                }
            }
        }
    }

    /** */
    private Reader waveReader = (address, dataBus) -> (cwave[address & 0x3f] | (dataBus & 0xc0));

    /** */
    private Writer waveWriter = (address, value) -> {
        // printf("$%04x:$%02x, %d\n",A,V,SPSG[0x9]&0x80);
        if ((spsg[0x9] & 0x80) != 0) {
            cwave[address & 0x3f] = (byte) (value & 0x3F);
        }
    };

    /** */
    private void clockRised() {
        if (clockcount == 0) {
            b19shiftreg60 = (spsg[0x2] | ((spsg[0x3] & 0xF) << 8));
            b17latch76 = (spsg[0x6] | ((spsg[0x07] & 0xF) << 8)) + b17latch76;

            if ((spsg[0x7] & 0x80) == 0) {
                int t = mwave[(b17latch76 >> 13) & 0x1f] & 7;
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
                // //(amplitude[1])*(fdso.mwave[(b17latch76>>13)&0x1F]&7);

                b8shiftreg88 = (byte) t; // (t+0x80)*(amplitude[1]);

                // t=0;
                // t=(t-4)*(amplitude[1]);
                // FCEU_DispMessage("%d",amplitude[1]);
                // b8shiftreg88=((fdso.mwave[(b17latch76>>11)&0x1F]&7))|(amplitude[1]<<3);
            } else {
                b8shiftreg88 = (byte) 0x80;
            }
            // b8shiftreg88=0x80;
        } else {
            b19shiftreg60 <<= 1;
            b8shiftreg88 >>= 1;
        }
        // b24adder66=(b24latch68+b19shiftreg60)&0x3FFFFFF;
        b24adder66 = (b24latch68 + b19shiftreg60) & 0x1FFFFFF;
    }

    /** */
    private void clockFallen() {
        // if(!(SPSG[0x7]&0x80))
        {
            if ((b8shiftreg88 & 1) != 0) // || clockcount==7)
            {
                b24latch68 = b24adder66;
                redoCO();
            }
        }
        clockcount = (clockcount + 1) & 7;
    }

    /** */
    private int doSound() {
        count += cycles;
        if (count >= ((long) 1 << 40)) {
            count -= (long) 1 << 40;
            clockRised();
            clockFallen();
            envcount--;
            if (envcount <= 0) {
                envcount += spsg[0xA] * 3;
                doEnv();
            }
        }
        if (count >= 32768) {
            count -= (long) 1 << 40;
            clockRised();
            clockFallen();
            envcount--;
            if (envcount <= 0) {
                envcount += spsg[0xA] * 3;
                doEnv();
            }
        }

        return curout;
    }

    /** */
    public void fillHi() {
        int x;

        if ((spsg[0x9] & 0x80) == 0 && (disabled & 0x1) == 0) {
            for (x = fbc; x < gapu.cpu.timestamp; x++) {
                int t = doSound();
                t += t >> 1;
                gapu.waveHi[x] += t;
            }
        }
        fbc = gapu.cpu.timestamp;
    }

    /** */
    public void syncHi(int ts) {
        fbc = ts;
    }

    /** */
    public void kill() {
    }

    /** */
    public void disable(int mask) {
        disabled = mask;
    }

    /** */
    public fds(NesApu apu) {

        this.cycles = (long) 1 << 39;
        this.gapu = apu;

        apu.cpu.setReader(0x4040, 0x407f, waveReader, this);
        apu.cpu.setWriter(0x4040, 0x407f, waveWriter, this);
        apu.cpu.setWriter(0x4080, 0x408A, sWriter, this);
        apu.cpu.setReader(0x4090, 0x4092, sReader, this);

        this.channels = 1;
    }
}
