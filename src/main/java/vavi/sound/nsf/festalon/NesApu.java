/*
 * Festalon - NSF Player
 * Copyright (C) 2004 Xodnizel
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package vavi.sound.nsf.festalon;


import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Arrays;

import static java.lang.System.getLogger;


public class NesApu {

    private static final Logger logger = getLogger(NesApu.class.getName());

    private static class UnitEnvironment {
        byte speed;
        /** Fixed volume(1), and loop(2) */
        byte mode;
        byte decCountTo1;
        byte decVolume;
        int reloadDec;
    }

    private final int[] wLookup1 = new int[32];
    private final int[] wLookup2 = new int[203];
    public final int[] waveHi = new int[200000]; // __attribute__ ((aligned (16)));
    private final float[] waveFloatBuffer = new float[200000];
    float[] waveFinal;
    int waveFinalLen;
    private byte triCount;
    private byte triMode;
    private int triStep;
    /** Wave length counters. */
    private final int[] wlCount = new int[4];
    /** $4017 / xx000000 */
    byte irqFrameMode;
    private final byte[] psg = new byte[0x10];
    /** $4011 0xxxxxxx */
    private byte rawDALatch;
    /** Byte written to $4015 */
    private byte enabledChannels;
    private final UnitEnvironment[] unitEnvs = new UnitEnvironment[3];
    private final int[] rectDutyCount = new int[2];
    private final byte[] sweepOn = new byte[2];
    private final int[] curFreq = new int[2];
    private final byte[] sweepCount = new byte[2];
    private int nReg;
    private byte fCount;
    private int fhCount;
    private int fhInc;
    private final int[] lengthCount = new int[4];
    private int dmcAcc;
    private int dmcPeriod;
    private byte dmcBitCount;
    /** writes to 4012 and 4013 */
    private byte dmcAddressLatch, dmcSizeLatch;
    /** Write to $4010 */
    byte dmcFormat;
    private int dmcAddress;
    private int dmcSize;
    private byte dmcShift;
    private byte sIrqStat;
    private int dmcHaveDMA;
    private byte dmcDMABuf;
    private int dmcHaveSample;
    private final int[] channels = new int[5];
    private int inBuf;
    private int lastPoo;
    public final X6502 cpu;
    Filter filter;
    private int disabled;
    private final ExpSound[] exp = new ExpSound[16];
    private int expCount;
    private final byte[] realMem;

    // ----

    /** */
    private static final int SQ_SHIFT = 26;
    /** */
    private static final int TRINPCM_SHIFT = 18;

    /** */
    private static final int[] RectDuties = {1,2,4,6};

    /** */
    private static final int[] lengthTable = {
        0x5 * 2, 0x7f * 2, 0xa * 2, 0x1 * 2,
        0x14 * 2, 0x2 * 2, 0x28 * 2, 0x3 * 2,
        0x50 * 2, 0x4 * 2, 0x1e * 2, 0x5 * 2,
        0x7 * 2, 0x6 * 2, 0x0e * 2, 0x7 * 2,
        0x6 * 2, 0x08 * 2, 0xc * 2, 0x9 * 2,
        0x18 * 2, 0xa * 2, 0x30 * 2, 0xb * 2,
        0x60 * 2, 0xc * 2, 0x24 * 2, 0xd * 2,
        0x8 * 2, 0xe * 2, 0x10 * 2, 0xf * 2
    };

    /** */
    private static final int[] NoiseFreqTable = {
        2, 4, 8, 0x10, 0x20, 0x30, 0x40, 0x50,
        0x65, 0x7f, 0xbe, 0xfe, 0x17d, 0x1fc, 0x3f9, 0x7f2
    };

    /** */
    private static final int[] NTSCDMCTable = {
        428, 380, 340, 320, 286, 254, 226, 214,
        190, 160, 142, 128, 106, 84, 72, 54
    };

    /** */
    private static final int[] PALDMCTable = {
        397, 353, 315, 297, 265, 235, 209, 198,
        176, 148, 131, 118, 98, 78, 66, 50,
    };

    /** */
    public int emulateFlush() {
        int end;
        int[] left = { 0 };

        if (cpu.timestamp == 0) {
            return 0;
        }

        doSQ1();
        doSQ2();
        doTriangle();
        doNoise();
        doPCM();

        {
            for (int j = 0; j < expCount; j++) {
//              if (exp[x].HiFill) {
                    exp[j].fillHi();
//              }
            }

            if (filter.inputFormat == Filter.FFI_INT16) {
                // Not implemented or rarely used path, keeping structure but might need fix if FFI_INT16 is used
                // For now, assuming float path (default)
                logger.log(Level.WARNING, "FFI_INT16 path triggered but not fully verified/ported");
            } else {
                int tmpO = lastPoo; // apu.waveHi
                if (wLookup1[15] == 0) ; // System.err.println("wLookup1 is empty!");
                // int nonZero = 0;
                // for (int k = 0; k < cpu.timestamp; k++) if (waveHi[k] != 0) nonZero++;
                // if (nonZero > 0) System.err.println("WaveHi has data: " + nonZero + " / " + cpu.timestamp);
                // else System.err.println("WaveHi is EMPTY. timestamp=" + cpu.timestamp);

                if (expCount != 0) {
                    for (int j = cpu.timestamp - lastPoo; j != 0; j--) {
                        int b = waveHi[tmpO];

                        waveFloatBuffer[tmpO] = (float) ((b & 0x3_ffff) + wLookup2[(b >>> TRINPCM_SHIFT) & 255] + wLookup1[b >>> SQ_SHIFT]);
                        tmpO++;
                    }
                } else {
                    double sumSq = 0;
                    for (int j = cpu.timestamp - lastPoo; j != 0; j--) {
                        int b = waveHi[tmpO];
                        waveFloatBuffer[tmpO] = (float) (wLookup2[(b >>> TRINPCM_SHIFT) & 255] + wLookup1[b >>> SQ_SHIFT]);
                        sumSq += waveFloatBuffer[tmpO] * waveFloatBuffer[tmpO];
                        if (tmpO < 10 && waveFloatBuffer[tmpO] != 0) {
                             logger.log(Level.TRACE, String.format("waveFloatBuffer[%d]: %f (raw=%08x)", tmpO, waveFloatBuffer[tmpO], b));
                        }
                        tmpO++;
                    }
                    logger.log(Level.TRACE, "WaveFloatBuffer pre-filter RMS: " + Math.sqrt(sumSq / (cpu.timestamp - lastPoo)));
                }
            }
{
    // int nonZero = 0;
    // float sum = 0;
    // float min = Float.MAX_VALUE;
    // float max = -Float.MAX_VALUE;
    for (int k = cpu.timestamp - lastPoo; k != 0; k--) {
        float v = waveFloatBuffer[lastPoo + k];
        // if (v != 0) nonZero++;
        // sum += Math.abs(v);
        // if (v < min) min = v;
        // if (v > max) max = v;
    }
    // if (nonZero > 0) System.err.println("WaveFloatBuffer: " + nonZero + " sum=" + sum + " min=" + min + " max=" + max + " rawWaveHi[0]=" + waveHi[lastPoo]);
}
            end = filter.exec(waveFloatBuffer, waveFinal, waveFinalLen, cpu.timestamp, left, 0);

            if (filter.inputFormat == Filter.FFI_INT16) {
                 // ...
            } else {
                System.arraycopy(waveFloatBuffer, cpu.timestamp - left[0], waveFloatBuffer, 0, left[0]);
                System.arraycopy(waveHi, cpu.timestamp - left[0], waveHi, 0, left[0]);
                // Clear the rest of waveHi (int accumulator) for next frame
                // Note: waveHi (int) accumulation starts at left[0] in doSQ etc (channels[i] = left[0])
                // So we clear from left[0] onwards.
                Arrays.fill(waveHi, left[0], waveHi.length, 0);
            }

            for (int j = 0; j < expCount; j++) {
//              if (exp[x].hiFill) {
                    exp[j].syncHi(left[0]);
//              }
            }
            for (int j = 0; j < 5; j++) {
                channels[j] = left[0];
            }
        }

        cpu.timestampBase += cpu.timestamp;
        cpu.timestamp = left[0];
        cpu.timestampBase -= cpu.timestamp;
        lastPoo = cpu.timestamp;
        inBuf = end;

        return end;
    }

    /*
     * FIXME: Find out what sound registers get reset on reset. I know
     * $4001/$4005 don't, due to that whole MegaMan 2 Game Genie thing.
     */

    public void reset() {

        irqFrameMode = 0x0;
        fhCount = fhInc;
        fCount = 0;

        nReg = 1;
        wLookup1[0] = 0;
        for (int i = 1; i < 32; i++) {
            this.wLookup1[i] = (int) (16 * 16 * 16 * 4 * 95.52 / (8128d / i + 100));
        }
        for (int i = 0; i < 2; i++) {
            wlCount[i] = 2048;
            sweepOn[i] = 0;
            curFreq[i] = 0;
        }
        wlCount[2] = 1; // 2048;
        wlCount[3] = 2048;
        dmcHaveDMA = dmcHaveSample = 0;
        sIrqStat = 0x00;

        rawDALatch = 0x00;
        triCount = 0;
        triMode = 0;
        triStep = 0;
        enabledChannels = 0;
        for (int i = 0; i < 4; i++) {
            lengthCount[i] = 0;
        }

        dmcAddressLatch = 0;
        dmcSizeLatch = 0;
        dmcFormat = 0;
        dmcAddress = 0;
        dmcSize = 0;
        dmcShift = 0;
    }

    /** */
    public void power() {
        cpu.setWriter(0x4000, 0x400F, psgWriter, this);
        cpu.setWriter(0x4010, 0x4013, dmcRegsWriter, this);
        cpu.setWriter(0x4017, 0x4017, irqFrameModeWriter, this);

        cpu.setWriter(0x4015, 0x4015, statusWriter, this);
        cpu.setReader(0x4015, 0x4015, statusReader, this);

        Arrays.fill(psg, (byte) 0x00);
        reset();

        Arrays.fill(waveHi, 0);
        for (int i = 0; i < unitEnvs.length; i++) {
            unitEnvs[i] = new UnitEnvironment();
        }

        for (int j = 0; j < 5; j++) {
            channels[j] = 0;
        }
        lastPoo = 0;
        loadDMCPeriod((byte) (dmcFormat & 0xf));
    }

    /** */
    public NesApu(X6502 cpu) {
        byte[] realMem;

        realMem = new byte[1];

//      apu = (NesApu) (((long) apu + 0xf) & ~0xf);
//      memset(apu, 0, sizeof(NESAPU));
        this.realMem = realMem;

        this.cpu = cpu;

        this.fhInc = cpu.pal ? 16626 : 14915; // * 2 CPU clock rate
        this.fhInc *= 24;

        this.wLookup1[0] = 0;
        for (int i = 1; i < 32; i++) {
            this.wLookup1[i] = (int) (16 * 16 * 16 * 4 * 95.52 / (8128d / i + 100));
        }

        this.wLookup2[0] = 0;
        for (int i = 1; i < 203; i++) {
            this.wLookup2[i] = (int) (16 * 16 * 16 * 4 * 163.67 / (24329d / i + 100));
        }
        logger.log(Level.TRACE, "wLookup1[15]: " + wLookup1[15]);
        logger.log(Level.TRACE, "wLookup2[45]: " + wLookup2[45]);

        loadDMCPeriod((byte) (dmcFormat & 0xf));
    }

    /** */
    public void disable(int d) {

        disabled = d & 0x1f;

        d >>= 5;

        for (int i = 0; i < expCount; i++) {
//          if (apu.exp[x].Disable != 0) {
                exp[i].disable(d);
//          }
            d >>= exp[i].channels;
        }
    }

    /** */
    public void addExp(ExpSound exp) {
        if (expCount < 16) {
            this.exp[expCount++] = exp;
        }
    }

    /** */
    private void doPCM() {
        if ((disabled & 0x10) == 0) {
            int count;
            int dp;
            int out;

            count = cpu.timestamp - channels[4];
            dp = channels[4];
            out = rawDALatch << TRINPCM_SHIFT;

            while (count > 0) {
//logger.log(Level.TRACE, "count: %d, dp: %d, out: %d, pc:%04x%n".formatted(count, dp, out, cpu.pc);
                waveHi[dp] += out;
                dp++;
                count--;
            }
//          for (int v = apu.ChannelBC[4]; v < x.timestamp; v++) {
//              apu.waveHi[v] += apu.rawDALatch << TRINPCM_SHIFT;
//          }
        }
        channels[4] = cpu.timestamp;
    }

    /** This has the correct phase. Don't mess with it. */
    private void doSQ(int i) {
//if (cpu.timestamp % 1000 < 100) {
//    logger.log(Level.TRACE, "doSQ(%d): ts=%d ch=%d freq=%d len=%d dis=%d".formatted(
//        i, cpu.timestamp, channels[i], curFreq[i], lengthCount[i], disabled));
//}

        if ((disabled & (1 << i)) != 0) {
            channels[i] = cpu.timestamp;
            return;
        }

        if (curFreq[i] < 8 || curFreq[i] > 0x7ff) {
            channels[i] = cpu.timestamp;
            return;
        }
        if (checkFreq(curFreq[i], psg[(i << 2) | 0x1]) == 0) {
            channels[i] = cpu.timestamp;
            return;
        }
        if (lengthCount[i] == 0) {
//if (i == 0 && cpu.timestamp % 1000 < 100) {
// logger.log(Level.DEBUG, String.format("doSQ(%d): SILENT (len=0, dis=%d)", i, disabled));
//}
            channels[i] = cpu.timestamp;
            return;
        }

        int amp;
        if ((unitEnvs[i].mode & 0x1) != 0) {
            amp = unitEnvs[i].speed;
        } else {
            amp = unitEnvs[i].decVolume;
        }
//logger.log(Level.DEBUG, "%d".formatted(amp));
        int rThresh = RectDuties[(psg[(i << 2)] & 0xC0) >> 6];

if (i == 0 && amp > 0 && cpu.timestamp % 1000 < 100) {
    logger.log(Level.TRACE, String.format("doSQ(%d): amp=%d rThresh=%d", i, amp, rThresh));
}
        amp <<= SQ_SHIFT;

        int d = channels[i];
        int curRdc = rectDutyCount[i];
        int cf = (curFreq[i] + 1) * 2;
        int rc = wlCount[i];

        while (d < cpu.timestamp) {
            if (rc <= 0) { // Safety: Force reload if invalid or wrapped
                rc = cf;
                curRdc = (curRdc + 1) & 7;
            }

            if (curRdc < rThresh) {
                waveHi[d] += amp;
            }
            rc--;
                if (rc == 0) {
                    rc = cf;
                    curRdc = (curRdc + 1) & 7;
                }
            d++;
        }

if (i == 0 && channels[i] < cpu.timestamp) {
    // System.err.println("doSQ(0): d diff=" + (cpu.timestamp - channels[i]) + " rc=" + rc + " curFreq=" + curFreq[i] + " cf=" + cf + " curRdc=" + curRdc + " rThresh=" + rThresh);
}

        rectDutyCount[i] = curRdc;
        wlCount[i] = rc;

        channels[i] = cpu.timestamp;
    }

    /** */
    private void doSQ1() {
        doSQ(0);
    }

    /** */
    private void doSQ2() {
        doSQ(1);
    }

    /** */
    private void doTriangle() {

        int tcout = (triStep & 0xF);
        if ((triStep & 0x10) == 0) {
            tcout ^= 0xf;
        }
        tcout = tcout * 3; // (tcout << 1);
        tcout <<= TRINPCM_SHIFT;

        if ((disabled & 4) != 0) {
            channels[2] = cpu.timestamp;
            return;
        }

        int v;
        if (lengthCount[2] == 0 || triCount == 0) { // Counter is halted, but we still need to output.
            for (v = channels[2]; v < cpu.timestamp; v++) {
                waveHi[v] += tcout;
            }
        } else {
            int wl = (psg[0xa] & 0xff | ((psg[0xb] & 7) << 8)) + 1;
            for (v = channels[2]; v < cpu.timestamp; v++) {
                waveHi[v] += tcout;
                wlCount[2]--;
                if (wlCount[2] == 0) {
                    wlCount[2] = wl;
                    triStep++;
                    tcout = (triStep & 0xf);
                    if ((triStep & 0x10) == 0) {
                        tcout ^= 0xf;
                    }
                    tcout = tcout * 3;
                    tcout <<= TRINPCM_SHIFT;
                }
            }
        }
        channels[2] = cpu.timestamp;
    }

    /** */
    private void doNoise() {
        int[] ampTab = new int[2];

        if ((unitEnvs[2].mode & 0x1) != 0) {
            ampTab[0] = unitEnvs[2].speed;
        } else {
            ampTab[0] = unitEnvs[2].decVolume;
        }

        ampTab[0] <<= TRINPCM_SHIFT;
        ampTab[1] = 0;

        ampTab[0] <<= 1;

        if ((disabled & 8) != 0) {
            channels[3] = cpu.timestamp;
            return;
        }

        int outO = ampTab[(nReg >> 14) & 1];

        if (lengthCount[3] == 0) {
            outO = ampTab[0] = 0;
        }

        int wl = NoiseFreqTable[psg[0xe] & 0xf] << 1;
        if ((psg[0xE] & 0x80) != 0) { // "short" noise
            for (int v = channels[3]; v < cpu.timestamp; v++) {
                waveHi[v] += outO;
                wlCount[3]--;
                if (wlCount[3] == 0) {
                    byte feedback;
                    wlCount[3] = wl;
                    feedback = (byte) (((nReg >> 8) & 1) ^ ((nReg >> 14) & 1));
                    nReg = (nReg << 1) + feedback;
                    nReg &= 0x7fff;
                    outO = ampTab[(nReg >> 0xe) & 1];
                }
            }
        } else {
            for (int v = channels[3]; v < cpu.timestamp; v++) {
                waveHi[v] += outO;
                wlCount[3]--;
                if (wlCount[3] == 0) {
                    byte feedback;
                    wlCount[3] = wl;
                    feedback = (byte) (((nReg >> 13) & 1) ^ ((nReg >> 14) & 1));
                    nReg = (nReg << 1) + feedback;
                    nReg &= 0x7fff;
                    outO = ampTab[(nReg >> 0xe) & 1];
                }
            }
        }
        channels[3] = cpu.timestamp;
    }

    /** */
    private void loadDMCPeriod(byte v) {
        if (cpu.pal) {
            dmcPeriod = PALDMCTable[v];
        } else {
            dmcPeriod = NTSCDMCTable[v];
        }
    }

    /** */
    private void prepDPCM() {
        dmcAddress = 0xc000 + ((dmcAddressLatch & 0xff) << 6);
        dmcSize = ((dmcSizeLatch & 0xff) << 4) + 1;
    }

    // Instantaneous? Maybe the new freq value is being calculated all of the
    // time...

    /** */
    private static int checkFreq(int cf, byte sr) {
        int mod;
        if ((sr & 0x8) == 0) {
            mod = cf >> (sr & 7);
            if (((mod + cf) & 0x800) != 0) {
                return 0;
            }
        }
        return 1;
    }

    /** */
    private void reloadSQ(int i, byte v) {
        if ((enabledChannels & (1 << i)) != 0) {
            if (i != 0) {
                doSQ2();
            } else {
                doSQ1();
            }
            lengthCount[i] = lengthTable[(v >> 3) & 0x1f];
        }

        sweepOn[i] = (byte) (psg[(i << 2) | 1] & 0x80);
        curFreq[i] = (psg[(i << 2) | 0x2] & 0xff) | ((v & 7) << 8);
        sweepCount[i] = (byte) (((psg[(i << 2) | 0x1] >> 4) & 7) + 1);

        if (i == 0) {
             // logger.log(Level.DEBUG, String.format("ReloadSQ(0): freq=%d len=%d", curFreq[i], lengthCount[i]));
        }

        rectDutyCount[i] = 7;
        unitEnvs[i].reloadDec = 1;
    }

    /** */
    private final Writer psgWriter = new Writer() {
        @Override
        public void exec(int address, int value) {
            logger.log(Level.TRACE, String.format("PSG Write: 40%02x val=%02x at ts=%d", address & 0x1f, value, cpu.timestamp));

            address &= 0x1f;

            switch (address) {
            case 0x0:
                doSQ1();
                unitEnvs[0].mode = (byte) ((value & 0x30) >> 4);
                unitEnvs[0].speed = (byte) (value & 0xf);
                break;
            case 0x1:
                sweepOn[0] = (byte) (value & 0x80);
                break;
            case 0x2:
                doSQ1();
                curFreq[0] &= 0xff00;
                curFreq[0] |= value;
                break;
            case 0x3:
                reloadSQ(0, (byte) value);
                break;
            case 0x4:
                doSQ2();
                unitEnvs[1].mode = (byte) ((value & 0x30) >> 4);
                unitEnvs[1].speed = (byte) (value & 0xf);
                break;
            case 0x5:
                sweepOn[1] = (byte) (value & 0x80);
                break;
            case 0x6:
                doSQ2();
                curFreq[1] &= 0xff00;
                curFreq[1] |= value;
                break;
            case 0x7:
                reloadSQ(1, (byte) value);
                break;
            case 0xa:
                doTriangle();
                break;
            case 0xb:
                doTriangle();
                if ((enabledChannels & 0x4) != 0) {
                    lengthCount[2] = lengthTable[(value >> 3) & 0x1f];
                }
                triMode = 1; // Load mode
                break;
            case 0xc:
                doNoise();
                unitEnvs[2].mode = (byte) ((value & 0x30) >> 4);
                unitEnvs[2].speed = (byte) (value & 0xf);
                break;
            case 0xe:
                doNoise();
                break;
            case 0xf:
                doNoise();
                if ((enabledChannels & 0x8) != 0)
                    lengthCount[3] = lengthTable[(value >> 3) & 0x1f];
                unitEnvs[2].reloadDec = 1;
                break;
            case 0x10:
                doPCM();
                loadDMCPeriod((byte) (value & 0xf));

                if ((sIrqStat & 0x80) != 0) {
                    if ((value & 0x80) == 0) {
                        cpu.endIRQ(X6502.FCEU_IQDPCM);
                        sIrqStat &= ~0x80;
                    } else
                        cpu.beginIRQ(X6502.FCEU_IQDPCM);
                }
                break;
            }
            psg[address] = (byte) value;
        }
    };

    private final Writer dmcRegsWriter = new Writer() {
        @Override
        public void exec(int address, int value) {

            address &= 0xf;

            switch (address) {
            case 0x00:
                doPCM();
                loadDMCPeriod((byte) (value & 0xf));

                if ((sIrqStat & 0x80) != 0) {
                    if ((value & 0x80) == 0) {
                        cpu.endIRQ(X6502.FCEU_IQDPCM);
                        sIrqStat &= ~0x80;
                    } else
                        cpu.beginIRQ(X6502.FCEU_IQDPCM);
                }
                dmcFormat = (byte) value;
                break;
            case 0x01:
                doPCM();
                rawDALatch = (byte) (value & 0x7f);
                break;
            case 0x02:
                dmcAddressLatch = (byte) value;
                break;
            case 0x03:
                dmcSizeLatch = (byte) value;
                break;
            }
        }
    };

    /** */
    private final Writer statusWriter = new Writer() {
        @Override
        public void exec(int address, int value) {

            doSQ1();
            doSQ2();
            doTriangle();
            doNoise();
            doPCM();
            for (int i = 0; i < 4; i++) {
                if ((value & (1 << i)) == 0) {
                    lengthCount[i] = 0; // Force length counters to 0.
                }
            }

            if ((value & 0x10) != 0) {
                if (dmcSize == 0) {
                    prepDPCM();
                }
            } else {
                dmcSize = 0;
            }
            sIrqStat &= ~0x80;
            cpu.endIRQ(X6502.FCEU_IQDPCM);
            enabledChannels = (byte) (value & 0x1f);
        }
    };

    /** */
    private final Reader statusReader = new Reader() {
        @Override
        public int exec(int address, int value) {
            byte ret;

            ret = sIrqStat;

            for (int i = 0; i < 4; i++) {
                ret |= lengthCount[i] != 0 ? (1 << i) : 0;
            }
            if (dmcSize != 0) {
                ret |= 0x10;
            }

            sIrqStat &= ~0x40;
            cpu.endIRQ(X6502.FCEU_IQFCOUNT);

            return ret;
        }
    };

    /** */
    private void stuffFrameSound(int v) {

        doSQ1();
        doSQ2();
        doNoise();
        doTriangle();

        if ((v & 1) == 0) { // Envelope decay, linear counter, length counter, freq sweep
            if ((psg[8] & 0x80) == 0) {
                if (lengthCount[2] > 0) {
                    lengthCount[2]--;
                }
            }

            if ((psg[0xC] & 0x20) == 0) { // Make sure loop flag is not set.
                if (lengthCount[3] > 0) {
                    lengthCount[3]--;
                }
            }

            for (int p = 0; p < 2; p++) {
                if ((psg[p << 2] & 0x20) == 0) { // Make sure loop flag is not set.
                    if (lengthCount[p] > 0) {
                        lengthCount[p]--;
                    }
                }

                // Frequency Sweep Code Here
                // xxxx 0000
                // xxxx = hz. 120/(x+1)
            if (sweepOn[p] != 0) {
                int mod = 0;

                if (sweepCount[p] > 0) {
                    sweepCount[p]--;
                }
                if (sweepCount[p] <= 0) {
                    sweepCount[p] = (byte) (((psg[(p << 2) + 0x1] >> 4) & 7) + 1); // +1;
                    if ((psg[(p << 2) + 0x1] & 0x8) != 0) {
                        mod -= (p ^ 1) + ((curFreq[p]) >> (psg[(p << 2) + 0x1] & 7));
                            if (curFreq[p] != 0 && ((psg[(p << 2) + 0x1] & 7) != 0) /* && sweepon[P] & 0x80 */) {
                                curFreq[p] += mod;
                            }
                        } else {
                            mod = curFreq[p] >> (psg[(p << 2) + 0x1] & 7);
                            if (((mod + curFreq[p]) & 0x800) != 0) {
                                sweepOn[p] = 0;
                                curFreq[p] = 0;
                            } else {
                                if (curFreq[p] != 0 && (psg[(p << 2) + 0x1] & 7) != 0 /* && sweepon[P] & 0x80 */) {
                                    curFreq[p] += mod;
                                }
                            }
                        }
                    }
                } else { // Sweeping is disabled:
                    // curfreq[P] &= 0xFF00;
                    // curfreq[P] |= apu.PSG[(P << 2)| 0x2]; // | ((apu.PSG[(P << 2) | 3] & 7) << 8);
                }
            }
        }

        // Now do envelope decay + linear counter.

        if (triMode != 0) { // In load mode?
            triCount = (byte) (psg[0x8] & 0x7f);
        } else if (triCount != 0) {
            triCount--;
        }

        if ((psg[0x8] & 0x80) == 0) {
            triMode = 0;
        }

        for (int p = 0; p < 3; p++) {
            if (unitEnvs[p].reloadDec != 0) {
                unitEnvs[p].decVolume = 0xf;
                unitEnvs[p].decCountTo1 = (byte) (unitEnvs[p].speed + 1);
                unitEnvs[p].reloadDec = 0;
                continue;
            }

            if (unitEnvs[p].decCountTo1 > 0) {
                unitEnvs[p].decCountTo1--;
            }
            if (unitEnvs[p].decCountTo1 == 0) {
                unitEnvs[p].decCountTo1 = (byte) (unitEnvs[p].speed + 1);
                if (unitEnvs[p].decVolume != 0 || (unitEnvs[p].mode & 0x2) != 0) {
                    unitEnvs[p].decVolume--;
                    unitEnvs[p].decVolume &= 0xf;
                }
            }
        }
    }

    /** */
    private void updateFrameSound() {
        // Linear counter: Bit 0-6 of $4008
        // Length counter: Bit 4-7 of $4003, $4007, $400b, $400f

        if (fCount == 0 && (irqFrameMode & 0x3) == 0) {
            sIrqStat |= 0x40;
            cpu.beginIRQ(X6502.FCEU_IQFCOUNT);
        }

        if (fCount == 3) {
            if ((irqFrameMode & 0x2) != 0) {
                fhCount += fhInc;
            }
        }
        stuffFrameSound(fCount);
        fCount = (byte) ((fCount + 1) & 3);
    }

    /** */
    private void test() {
        if (dmcBitCount == 0) {
            if (dmcHaveDMA == 0)
                dmcHaveSample = 0;
            else {
                dmcHaveSample = 1;
                dmcShift = dmcDMABuf;
                dmcHaveDMA = 0;
            }
        }
    }

    /** */
    private void dmcDMA() {
        if (dmcSize != 0 && dmcHaveDMA == 0) {
            cpu.readDm(dmcAddress);
            cpu.readDm(dmcAddress);
            cpu.readDm(dmcAddress);
            dmcDMABuf = (byte) cpu.readDm(dmcAddress);
            dmcHaveDMA = 1;
            dmcAddress = (dmcAddress + 1) & 0xffff;
            dmcSize--;
            if (dmcSize == 0) {
                if ((dmcFormat & 0x40) != 0) {
                    prepDPCM();
                } else {
                    sIrqStat |= 0x80;
                    if ((dmcFormat & 0x80) != 0) {
                        cpu.beginIRQ(X6502.FCEU_IQDPCM);
                    }
                }
            }
        }
    }

    /** */
    public void hookSoundCPU(int cycles) {

        fhCount -= cycles * 48;
        while (fhCount <= 0) {
            int rest = fhCount / 48;
//logger.log(Level.DEBUG, "%8d:%8d".formatted(x.timestamp, x.timestamp+rest));
            cpu.timestamp += rest; // Yet another ugly hack.
            if (cpu.timestamp < lastPoo) {
                logger.log(Level.DEBUG, "eep");
            }
            updateFrameSound();
            cpu.timestamp -= rest;
            fhCount += fhInc;
        }

        dmcDMA();
        dmcAcc -= cycles;

        while (dmcAcc <= 0) {
            dmcDMA();
            if (dmcHaveSample != 0) {
                byte bah = rawDALatch;
                int t = ((dmcShift & 1) << 2) - 2;
                int rest = dmcAcc;
                // Unbelievably ugly hack
                cpu.timestamp += rest;
//logger.log(Level.DEBUG, "%8d:%8d".formatted(x.timestamp, channelBC[4]));
                doPCM();
                cpu.timestamp -= rest;
                rawDALatch = (byte) (rawDALatch + t);
                if ((rawDALatch & 0x80) != 0) {
                    rawDALatch = bah;
                }
            }

            dmcAcc += dmcPeriod;
            dmcBitCount = (byte) ((dmcBitCount + 1) & 7);
            dmcShift >>= 1;
            test();
        }
    }

    /** */
    private final Writer irqFrameModeWriter = new Writer() {
        @Override
        public void exec(int address, int value) {

            value = (value & 0xc0) >> 6;
            fCount = 0;
            if ((value & 0x2) != 0) {
                updateFrameSound();
            }
            fCount = 1;
            fhCount = fhInc;
            cpu.endIRQ(X6502.FCEU_IQFCOUNT);
            sIrqStat &= ~0x40;
            irqFrameMode = (byte) value;
        }
    };
}
