/*
 * Permission is granted to anyone to use this software for any purpose,
 * including commercial applications. To alter this software and redistribute it freely,
 * if the origin of this software is not misrepresented.
 */

package vavi.sound.nsf.ext;


/**
 * YM2413 emulator.
 * <p>
 * This software has been heavily modified for VRC7.<br>
 * To get a stock YM2413 emulator, download MSXplug.
 * </p>
 * <pre>
 * References:
 * fmopl.c        -- 1999,2000 written by Tatsuyuki Satoh (MAME development).
 * fmopl.c(fixed) -- (C) 2002 Jarek Burczynski.
 * s_opl.c        -- 2001 written by Mamiya (NEZplug development).
 * fmgen.cpp      -- 1999,2000 written by cisc.
 * fmpac.ill      -- 2000 created by NARUTO.
 * MSX-Datapack
 * YMU757 data sheet
 * YM2143 data sheet
 * </pre>
 * @author Mitsutaka Okazaki 2001
 * @version 0.10 2001/01/08 1st version.<br>
 *          0.20 2001/01/15 semi-public version.<br>
 *          0.30 2001/01/16 1st public version.<br>
 *          0.31 2001/01/17 Fixed bassdrum problem.<br>
 *          0.32            LPF implemented.<br>
 *          0.33 2001/01/18 Fixed the drum problem, refine the mix-down method.<br>
 *                          Fixed the LFO bug.<br>
 *          0.35 2001/01/24 Fixed the drum problem,<br>
 *                          support undocumented EG behavior.<br>
 *          0.38 2001/02/02 Improved the performance.<br>
 *                          Fixed the hi-hat and cymbal model.<br>
 *                          Fixed the default percussive datas.<br>
 *                          Noise reduction.<br>
 *                          Fixed the feedback problem.<br>
 *          0.39 2001/03/03 Fixed some drum bugs.<br>
 *                          Improved the performance.<br>
 *          0.40 2001/03/04 Improved the feedback.<br>
 *                          Change the default table size.<br>
 *                          Clock and Rate can be changed during play.<br>
 *          0.50 2001/06/24 Improved the hi-hat and the cymbal tone.<br>
 *                          Added VRC7 patch (OPLL_reset_patch is changed).<br>
 *                          Fixed OPLL_reset() bug.<br>
 *                          Added OPLL_setMask, OPLL_getMask and OPLL_toggleMask.<br>
 *                          Added OPLL_writeIO.<br>
 *          0.51 2001/09/28 Removed the noise table.<br>
 *          0.52 2002/01/28 Added Stereo mode.<br>
 *          0.53 2002/02/07 Fixed some drum bugs.<br>
 *          0.54 2002/02/20 Added the best quality mode.<br>
 *          0.55 2002/03/02 Removed OPLL_init &amp; OPLL_close.<br>
 *          0.60 2002/05/30 Fixed HH&amp;CYM generator and all voice datas.<br>
 *               2004/01/24 Modified by xodnizel to remove code not needed for the VRC7,
 *                          among other things.<br>
 */
class Opll {

    /* Size of Sintable ( 8 -- 18 can be used. 9 recommended.) */
    private static final int PG_BITS = 9;
    private static final int PG_WIDTH = (1 << PG_BITS);

    /* Phase increment counter */
    private static final int DP_BITS = 18;
    private static final int DP_WIDTH = (1 << DP_BITS);
    private static final int DP_BASE_BITS = (DP_BITS - PG_BITS);

    /* Dynamic range (Accuracy of sin table) */
    private static final int DB_BITS = 8;
    private static final double DB_STEP = (48.0 / (1 << DB_BITS));
    private static final int DB_MUTE = (1 << DB_BITS);

    /* Dynamic range of envelope */
    private static final double EG_STEP = 0.375;
    private static final int EG_BITS = 7;
    private static final int EG_MUTE = (1 << EG_BITS);

    /* Dynamic range of total level */
    private static final double TL_STEP = 0.75;
    private static final int TL_BITS = 6;
    private static final int TL_MUTE = (1 << TL_BITS);

    /* Dynamic range of sustine level */
    private static final double SL_STEP = 3.0;
    private static final int SL_BITS = 4;
    private static final int SL_MUTE = (1 << SL_BITS);

    /* Bits for Pitch and Amp modulator */
    private static final int PM_PG_BITS = 8;
    private static final int PM_PG_WIDTH = (1 << PM_PG_BITS);
    private static final int PM_DP_BITS = 16;
    private static final int PM_DP_WIDTH = (1 << PM_DP_BITS);
    private static final int AM_PG_BITS = 8;
    private static final int AM_PG_WIDTH = (1 << AM_PG_BITS);
    private static final int AM_DP_BITS = 16;
    private static final int AM_DP_WIDTH = (1 << AM_DP_BITS);

//  enum X {
//      OPLL_VRC7_TONE
//  };

    /** voice data */
    private class Patch {
        int tl, fb, eg, ml, ar, dr, sl, rr, kr, kl, am, pm, wf;
    }

    /** slot */
    private class Slot {
        /** */
        Patch patch = new Patch();
        /** 0 : modulator 1 : carrier */
        int type;
        /** OUTPUT */
        int feedback;
        /** Output value of slot */
        /** for Phase Generator (PG) */
        int[] output = new int[2];
        /** Wavetable */
        short[] sintbl;
        /** Phase */
        int phase;
        /** Phase increment amount */
        int dphase;
        /** output for Envelope Generator (EG) */
        int pgout;
        /** F-Number */
        int fnum;
        /** Block */
        int block;
        /** Current volume */
        int volume;
        /** Sustine 1 = ON, 0 = OFF */
        int sustine;
        /** Total Level + Key scale level */
        int tll;
        /** Key scale offset (Rks) */
        int rks;
        /** Current state */
        EnvelopeMode eg_mode;
        /** Phase */
        int eg_phase;
        /** Phase increment amount */
        int eg_dphase;
        /** output */
        int egout;
        /** */
        Slot(int type) {
            this.type = type;
            this.sintbl = waveForm[0];
            this.phase = 0;
            this.dphase = 0;
            this.output[0] = 0;
            this.output[1] = 0;
            this.feedback = 0;
            this.eg_mode = EnvelopeMode.SETTLE;
            this.eg_phase = EG_DP_WIDTH;
            this.eg_dphase = 0;
            this.rks = 0;
            this.tll = 0;
            this.sustine = 0;
            this.fnum = 0;
            this.block = 0;
            this.volume = 0;
            this.pgout = 0;
            this.egout = 0;
        }

        /** */
        final void updatePG() {
            this.dphase = dphaseTable[this.fnum][this.block][this.patch.ml];
        }

        /** */
        final int updateTLL() {
            return ((this.type == 0) ? (this.tll = tllTable[(this.fnum) >> 5][this.block][this.patch.tl][this.patch.kl]) : (this.tll = tllTable[(this.fnum) >> 5][this.block][this.volume][this.patch.kl]));
        }

        /** */
        final void updateRKS() {
            this.rks = rksTable[(this.fnum) >> 8][this.block][this.patch.kr];
        }

        /** */
        final void updateWF() {
            this.sintbl = waveForm[this.patch.wf];
        }

        /** */
        final void updateEG() {
            this.eg_dphase = calcEgDPhase();
        }

        /** */
        final void updateALL() {
            updatePG();
            updateTLL();
            updateRKS();
            updateWF();
            updateEG(); // EG should be updated last.
        }

        /** Slot key on */
        final void slotOn() {
            this.eg_mode = EnvelopeMode.ATTACK;
            this.eg_phase = 0;
            this.phase = 0;
        }

        /** Slot key on without reseting the phase */
        final void slotOn2() {
            this.eg_mode = EnvelopeMode.ATTACK;
            this.eg_phase = 0;
        }

        /** Slot key off */
        final void slotOff() {
            if (this.eg_mode == EnvelopeMode.ATTACK) {
                this.eg_phase = expandBits(arAdjustTable[highBits(this.eg_phase, EG_DP_BITS - EG_BITS)], EG_BITS, EG_DP_BITS);
            }
            this.eg_mode = EnvelopeMode.RELEASE;
        }

        /**
         * Calc Parameters
         */
        private final int calcEgDPhase() {

            switch (this.eg_mode) {
            case ATTACK:
                return dPhaseARTable[this.patch.ar][this.rks];

            case DECAY:
                return dPhaseDRTable[this.patch.dr][this.rks];

            case SUSHOLD:
                return 0;

            case SUSTINE:
                return dPhaseDRTable[this.patch.rr][this.rks];

            case RELEASE:
                if (this.sustine != 0)
                    return dPhaseDRTable[5][this.rks];
                else if (this.patch.eg != 0)
                    return dPhaseDRTable[this.patch.rr][this.rks];
                else
                    return dPhaseDRTable[7][this.rks];

            case FINISH:
                return 0;

            default:
                return 0;
            }
        }

        /* EG */
        void calcEnvelope(int lfo) {

            int egout;

            switch (this.eg_mode) {

            case ATTACK:
                egout = arAdjustTable[highBits(this.eg_phase, EG_DP_BITS - EG_BITS)];
                this.eg_phase += this.eg_dphase;
                if ((EG_DP_WIDTH & this.eg_phase) != 0 || (this.patch.ar == 15)) {
                    egout = 0;
                    this.eg_phase = 0;
                    this.eg_mode = EnvelopeMode.DECAY;
                    this.updateEG();
                }
                break;

            case DECAY:
                egout = highBits(this.eg_phase, EG_DP_BITS - EG_BITS);
                this.eg_phase += this.eg_dphase;
                if (this.eg_phase >= SL[this.patch.sl]) {
                    if (this.patch.eg != 0) {
                        this.eg_phase = SL[this.patch.sl];
                        this.eg_mode = EnvelopeMode.SUSHOLD;
                        this.updateEG();
                    } else {
                        this.eg_phase = SL[this.patch.sl];
                        this.eg_mode = EnvelopeMode.SUSTINE;
                        this.updateEG();
                    }
                }
                break;

            case SUSHOLD:
                egout = highBits(this.eg_phase, EG_DP_BITS - EG_BITS);
                if (this.patch.eg == 0) {
                    this.eg_mode = EnvelopeMode.SUSTINE;
                    this.updateEG();
                }
                break;

            case SUSTINE:
            case RELEASE:
                egout = highBits(this.eg_phase, EG_DP_BITS - EG_BITS);
                this.eg_phase += this.eg_dphase;
                if (egout >= (1 << EG_BITS)) {
                    this.eg_mode = EnvelopeMode.FINISH;
                    egout = (1 << EG_BITS) - 1;
                }
                break;

            case FINISH:
                egout = (1 << EG_BITS) - 1;
                break;

            default:
                egout = (1 << EG_BITS) - 1;
                break;
            }

            if (this.patch.am != 0) {
                egout = eg2db(egout + this.tll) + lfo;
            } else {
                egout = eg2db(egout + this.tll);
            }

            if (egout >= DB_MUTE) {
                egout = DB_MUTE - 1;
            }

            this.egout = egout;
        }

        /** carrior */
        final int calcSlotCar(int fm) {
            this.output[1] = this.output[0];

            if (this.egout >= (DB_MUTE - 1)) {
                this.output[0] = 0;
            } else {
                this.output[0] = db2LinTable[this.sintbl[(this.pgout + wave2_8pi(fm)) & (PG_WIDTH - 1)] + this.egout];
            }

            return (this.output[1] + this.output[0]) >> 1;
        }

        /** modulator */
        final int calcSlotMod() {
            int fm;

            this.output[1] = this.output[0];

            if (this.egout >= (DB_MUTE - 1)) {
                this.output[0] = 0;
            } else if (this.patch.fb != 0) {
                fm = wave2_4pi(this.feedback) >> (7 - this.patch.fb);
                this.output[0] = db2LinTable[this.sintbl[(this.pgout + fm) & (PG_WIDTH - 1)] + this.egout];
            } else {
                this.output[0] = db2LinTable[this.sintbl[this.pgout] + this.egout];
            }

            this.feedback = (this.output[1] + this.output[0]) >> 1;

            return this.feedback;

        }

        /* PG */
        final void calcPhase(int lfo) {
            if (this.patch.pm != 0)
                this.phase += (this.dphase * lfo) >> PM_AMP_BITS;
            else
                this.phase += this.dphase;

            this.phase &= (DP_WIDTH - 1);

            this.pgout = highBits(this.phase, DP_BASE_BITS);
        }

        /** */
        final void setSlotVolume(int volume) {
            this.volume = volume;
        }
    }

    /* Mask */
    private static final int maskCh(int x) {
        return (1 << (x));
    }

    /* opll */
    private int adr;
    private int out;

    private int realstep;
    private int oplltime;
    private int opllstep;
    private int prev, next;

    /** Register */
    private byte[] lowFreq = new byte[6];
    private byte[] hiFreq = new byte[6];
    private byte[] instVol = new byte[6];
    private int[] custInst = new int[8];

    private int[] slotOnFlag = new int[6 * 2];

    /** Pitch Modulator */
    private int pmPhase;
    private int lfoPm;

    /** Amp Modulator */
    private int amPhase;
    private int lfoAm;
    private int quality;

    /** Channel Data */
    private int[] patchNumber = new int[6];
    private int[] keyStatus = new int[6];

    /** Slot */
    private Slot[] slot = new Slot[6 * 2];
    private int mask;

    /** Input clock */
    private int clk;

    /** WaveTable for each envelope amp */
    private short[] fullSinTable = new short[PG_WIDTH];
    private short[] halfSinTable = new short[PG_WIDTH];
    private short[][] waveForm = new short[2][];

    /** LFO Table */
    private int[] pmTable = new int[PM_PG_WIDTH];
    private int[] amTable = new int[AM_PG_WIDTH];

    /** Phase delta for LFO */
    private int pmDPhase;
    private int amDPhase;

    /** dB to Liner table */
    private short[] db2LinTable = new short[(DB_MUTE + DB_MUTE) * 2];

    /** Liner to Log curve conversion table (for Attack rate). */
    private short[] arAdjustTable = new short[1 << EG_BITS];

    /** Phase incr table for Attack */
    private int[][] dPhaseARTable = new int[16][16];

    /** Phase incr table for Decay and Release */
    private int[][] dPhaseDRTable = new int[16][16];

    /** KSL + TL Table */
    private int[][][][] tllTable = new int[16][8][1 << TL_BITS][4];
    private int[][][] rksTable = new int[2][8][2];

    /** Phase incr table for PG */
    private int[][][] dphaseTable = new int[512][8][16];

    /**
     * VRC7 instruments
     * @since January 17, 2004 update
     * @author Xodnizel
     */
    private static final int[][] defaultInst = {
        { 0x03, 0x21, 0x04, 0x06, 0x8d, 0xf2, 0x42, 0x17 },
        { 0x13, 0x41, 0x05, 0x0e, 0x99, 0x96, 0x63, 0x12 },
        { 0x31, 0x11, 0x10, 0x0a, 0xf0, 0x9c, 0x32, 0x02 },
        { 0x21, 0x61, 0x1d, 0x07, 0x9f, 0x64, 0x20, 0x27 },
        { 0x22, 0x21, 0x1e, 0x06, 0xf0, 0x76, 0x08, 0x28 },
        { 0x02, 0x01, 0x06, 0x00, 0xf0, 0xf2, 0x03, 0x95 },
        { 0x21, 0x61, 0x1c, 0x07, 0x82, 0x81, 0x16, 0x07 },
        { 0x23, 0x21, 0x1a, 0x17, 0xef, 0x82, 0x25, 0x15 },
        { 0x25, 0x11, 0x1f, 0x00, 0x86, 0x41, 0x20, 0x11 },
        { 0x85, 0x01, 0x1f, 0x0f, 0xe4, 0xa2, 0x11, 0x12 },
        { 0x07, 0xc1, 0x2b, 0x45, 0xb4, 0xf1, 0x24, 0xf4 },
        { 0x61, 0x23, 0x11, 0x06, 0x96, 0x96, 0x13, 0x16 },
        { 0x01, 0x02, 0xd3, 0x05, 0x82, 0xa2, 0x31, 0x51 },
        { 0x61, 0x22, 0x0d, 0x02, 0xc3, 0x7f, 0x24, 0x05 },
        { 0x21, 0x62, 0x0e, 0x00, 0xa1, 0xa0, 0x44, 0x17 },
    };

    private static final int eg2db(int d) {
        return (d) * (int) (EG_STEP / DB_STEP);
    }

    private static final int tl2eg(int d) {
        return ((d) * (int) (TL_STEP / EG_STEP));
    };

    private static final int sl2eg(int d) {
        return ((d) * (int) (SL_STEP / EG_STEP));
    };

    private static final int dbPos(int x) {
        return (int) ((x) / DB_STEP);
    }

    private static final int dbNeg(int x) {
        return (int) (DB_MUTE + DB_MUTE + (x) / DB_STEP);
    }

    /** Bits for liner value */
    private static final int DB2LIN_AMP_BITS = 11;

    private static final int SLOT_AMP_BITS = DB2LIN_AMP_BITS;

    /** Bits for envelope phase incremental counter */
    private static final int EG_DP_BITS = 22;

    private static final int EG_DP_WIDTH = 1 << EG_DP_BITS;

    /** PM table is calcurated by PM_AMP * pow(2,PM_DEPTH*sin(x)/1200) */
    private static final int PM_AMP_BITS = 8;

    private static final int PM_AMP = 1 << PM_AMP_BITS;

    /** PM speed(Hz) and depth(cent) */
    private static final double PM_SPEED = 6.4;

    private static final double PM_DEPTH = 13.75;

    /* AM speed(Hz) and depth(dB) */
    private static final double AM_SPEED = 3.7;

    // static final int AM_DEPTH 4.8
    private static final double AM_DEPTH = 2.4;

    /* Cut the lower b bit(s) off. */
    private static final int highBits(int c, int b) {
        return ((c) >> (b));
    }

    /* Leave the lower b bit(s). */
    private static final int lowBits(int c, int b) {
        return ((c) & ((1 << (b)) - 1));
    }

    /* Expand x which is s bits to d bits. */
    private static final int expandBits(int x, int s, int d) {
        return ((x) << ((d) - (s)));
    }

    /* Expand x which is s bits to d bits and fill expanded bits '1' */
    private static final int expandBitsX(int x, int s, int d) {
        return (((x) << ((d) - (s))) | ((1 << ((d) - (s))) - 1));
    }

    private final Slot mod(int x) {
        return (slot[(x) << 1]);
    }

    private final Slot car(int x) {
        return (slot[((x) << 1) | 1]);
    }

    private static final int bit(int s, int b) {
        return (((s) >> (b)) & 1);
    }

    /** Definition of envelope mode */
    private enum EnvelopeMode {
        SETTLE, ATTACK, DECAY, SUSHOLD, SUSTINE, RELEASE, FINISH
    }

    /*
     * Create tables
     */

    /** Table for AR to LogCurve. */
    private void makeAdjustTable() {

        arAdjustTable[0] = (1 << EG_BITS);
        for (int i = 1; i < 128; i++) {
            arAdjustTable[i] = (short) ((double) (1 << EG_BITS) - 1 - (1 << EG_BITS) * Math.log(i) / Math.log(128));
        }
    }

    /** Table for dB(0 -- (1<<DB_BITS)-1) to Liner(0 -- DB2LIN_AMP_WIDTH) */
    private void makeDB2LinTable() {

        for (int i = 0; i < DB_MUTE + DB_MUTE; i++) {
            db2LinTable[i] = (short) (((1 << DB2LIN_AMP_BITS) - 1) * Math.pow(10, -(double) i * DB_STEP / 20));
            if (i >= DB_MUTE) {
                db2LinTable[i] = 0;
            }
            // printf("%d\n",DB2LIN_TABLE[i]);
            db2LinTable[i + DB_MUTE + DB_MUTE] = (short) (-db2LinTable[i]);
        }
    }

    /** Liner(+0.0 - +1.0) to dB((1<<DB_BITS) - 1 -- 0) */
    private int lin2db(double d) {
        if (d == 0) {
            return (DB_MUTE - 1);
        } else {
            return Math.min(-(int) (20.0 * Math.log10(d) / DB_STEP), DB_MUTE - 1); // 0 -- 127
        }
    }

    /** Sin Table */
    private void makeSinTable() {

        for (int i = 0; i < PG_WIDTH / 4; i++) {
            fullSinTable[i] = (short) lin2db(Math.sin(2.0 * Math.PI * i / PG_WIDTH));
        }

        for (int i = 0; i < PG_WIDTH / 4; i++) {
            fullSinTable[PG_WIDTH / 2 - 1 - i] = fullSinTable[i];
        }

        for (int i = 0; i < PG_WIDTH / 2; i++) {
            fullSinTable[PG_WIDTH / 2 + i] = (short) (DB_MUTE + DB_MUTE + fullSinTable[i]);
        }

        for (int i = 0; i < PG_WIDTH / 2; i++) {
            halfSinTable[i] = fullSinTable[i];
        }
        for (int i = PG_WIDTH / 2; i < PG_WIDTH; i++) {
            halfSinTable[i] = fullSinTable[0];
        }
    }

    /** Table for Pitch Modulator */
    private void makePmTable() {

        for (int i = 0; i < PM_PG_WIDTH; i++) {
            pmTable[i] = (int) (PM_AMP * Math.pow(2, PM_DEPTH * Math.sin(2.0 * Math.PI * i / PM_PG_WIDTH) / 1200));
        }
    }

    /** Table for Amp Modulator */
    private void makeAmTable() {

        for (int i = 0; i < AM_PG_WIDTH; i++) {
            amTable[i] = (int) (AM_DEPTH / 2 / DB_STEP * (1.0 + Math.sin(2.0 * Math.PI * i / PM_PG_WIDTH)));
        }
    }

    /** Phase increment counter table */
    private void makeDPhaseTable() {
        final int[] mlTable = {
            1, 1 * 2, 2 * 2, 3 * 2,
            4 * 2, 5 * 2, 6 * 2, 7 * 2,
            8 * 2, 9 * 2, 10 * 2, 10 * 2,
            12 * 2, 12 * 2, 15 * 2, 15 * 2
        };

        for (int fnum = 0; fnum < 512; fnum++) {
            for (int block = 0; block < 8; block++) {
                for (int ml = 0; ml < 16; ml++) {
                    dphaseTable[fnum][block][ml] = (((fnum * mlTable[ml]) << block) >> (20 - DP_BITS));
                }
            }
        }
    }

    /** */
    private static final double dB2(double d) {
        return ((d) * 2);
    }

    /** */
    private static final double[] klTable = {
        dB2(0.000), dB2(9.000), dB2(12.000), dB2(13.875),
        dB2(15.000), dB2(16.125), dB2(16.875), dB2(17.625),
        dB2(18.000), dB2(18.750), dB2(19.125), dB2(19.500),
        dB2(19.875), dB2(20.250), dB2(20.625), dB2(21.000)
    };

    /** */
    private void makeTllTable() {

        for (int fnum = 0; fnum < 16; fnum++) {
            for (int block = 0; block < 8; block++) {
                for (int tl = 0; tl < 64; tl++) {
                    for (int kl = 0; kl < 4; kl++) {
                        if (kl == 0) {
                            tllTable[fnum][block][tl][kl] = tl2eg(tl);
                        } else {
                            int tmp = (int) (klTable[fnum] - dB2(3.000) * (7 - block));
                            if (tmp <= 0) {
                                tllTable[fnum][block][tl][kl] = tl2eg(tl);
                            } else {
                                tllTable[fnum][block][tl][kl] = (int) ((tmp >> (3 - kl)) / EG_STEP) + tl2eg(tl);
                            }
                        }
                    }
                }
            }
        }
    }

// #ifdef USE_SPEC_ENV_SPEED
    private static final double[][] attackTime = {
        { 0, 0, 0, 0 },
        { 1730.15, 1400.60, 1153.43, 988.66 },
        { 865.08, 700.30, 576.72, 494.33 },
        { 432.54, 350.15, 288.36, 247.16 },
        { 216.27, 175.07, 144.18, 123.58 },
        { 108.13, 87.54, 72.09, 61.79 },
        { 54.07, 43.77, 36.04, 30.90 },
        { 27.03, 21.88, 18.02, 15.45 },
        { 13.52, 10.94, 9.01, 7.72 },
        { 6.76, 5.47, 4.51, 3.86 },
        { 3.38, 2.74, 2.25, 1.93 },
        { 1.69, 1.37, 1.13, 0.97 },
        { 0.84, 0.70, 0.60, 0.54 },
        { 0.50, 0.42, 0.34, 0.30 },
        { 0.28, 0.22, 0.18, 0.14 },
        { 0.00, 0.00, 0.00, 0.00 }
    };

    private static final double[][] decayTime = {
        { 0, 0, 0, 0 },
        { 20926.60, 16807.20, 14006.00, 12028.60 },
        { 10463.30, 8403.58, 7002.98, 6014.32 },
        { 5231.64, 4201.79, 3501.49, 3007.16 },
        { 2615.82, 2100.89, 1750.75, 1503.58 },
        { 1307.91, 1050.45, 875.37, 751.79 },
        { 653.95, 525.22, 437.69, 375.90 },
        { 326.98, 262.61, 218.84, 187.95 },
        { 163.49, 131.31, 109.42, 93.97 },
        { 81.74, 65.65, 54.71, 46.99 },
        { 40.87, 32.83, 27.36, 23.49 },
        { 20.44, 16.41, 13.68, 11.75 },
        { 10.22, 8.21, 6.84, 5.87 },
        { 5.11, 4.10, 3.42, 2.94 },
        { 2.55, 2.05, 1.71, 1.47 },
        { 1.27, 1.27, 1.27, 1.27 }
    };
// #endif

    /* Rate Table for Attack */
    private void makeDPhaseARTable() {
// #ifdef USE_SPEC_ENV_SPEED
        int[][] attackTable = new int[16][4];

        for (int rm = 0; rm < 16; rm++) {
            for (int rl = 0; rl < 4; rl++) {
                if (rm == 0) {
                    attackTable[rm][rl] = 0;
                } else if (rm == 15) {
                    attackTable[rm][rl] = EG_DP_WIDTH;
                } else {
                    attackTable[rm][rl] = (int) ((1 << EG_DP_BITS) / (attackTime[rm][rl] * 3579545 / 72000));
                }
            }
        }
// #endif

        for (int ar = 0; ar < 16; ar++) {
            for (int rks = 0; rks < 16; rks++) {
                int rm = ar + (rks >> 2);
                int rl = rks & 3;
                if (rm > 15)
                    rm = 15;
                switch (ar) {
                case 0:
                    dPhaseARTable[ar][rks] = 0;
                    break;
                case 15:
                    dPhaseARTable[ar][rks] = 0;/* EG_DP_WIDTH; */
                    break;
                default:
// #ifdef USE_SPEC_ENV_SPEED
                    dPhaseARTable[ar][rks] = (attackTable[rm][rl]);
// #else
//                  dphaseARTable[AR][Rks] = ((3 * (RL + 4) << (RM + 1)));
// #endif
                    break;
                }
            }
        }
    }

    /* Rate Table for Decay and Release */
    private void makeDPhaseDRTable() {

// #ifdef USE_SPEC_ENV_SPEED
        int[][] decayTable = new int[16][4];

        for (int rm = 0; rm < 16; rm++) {
            for (int rl = 0; rl < 4; rl++) {
                if (rm == 0) {
                    decayTable[rm][rl] = 0;
                } else {
                    decayTable[rm][rl] = (int) ((1 << EG_DP_BITS) / (decayTime[rm][rl] * 3579545 / 72000));
                }
            }
        }
// #endif

        for (int dr = 0; dr < 16; dr++) {
            for (int rks = 0; rks < 16; rks++) {
                int rm = dr + (rks >> 2);
                int rl = rks & 3;
                if (rm > 15) {
                    rm = 15;
                }
                switch (dr) {
                case 0:
                    dPhaseDRTable[dr][rks] = 0;
                    break;
                default:
// #ifdef USE_SPEC_ENV_SPEED
                    dPhaseDRTable[dr][rks] = (decayTable[rm][rl]);
// #else
//                  dphaseDRTable[DR][Rks] = ((RL + 4) << (RM - 1));
// #endif
                    break;
                }
            }
        }
    }

    /** */
    private void makeRksTable() {

        for (int fnum8 = 0; fnum8 < 2; fnum8++) {
            for (int block = 0; block < 8; block++) {
                for (int kr = 0; kr < 2; kr++) {
                    if (kr != 0) {
                        rksTable[fnum8][block][kr] = (block << 1) + fnum8;
                    } else {
                        rksTable[fnum8][block][kr] = block >> 1;
                    }
                }
            }
        }
    }

    /*
     * OPLL internal interfaces
     */

    /** Channel key on */
    private final void keyOn(int i) {
        if (slotOnFlag[i * 2] == 0) {
            mod(i).slotOn();
        }
        if (slotOnFlag[i * 2 + 1] == 0) {
            car(i).slotOn();
        }
        keyStatus[i] = 1;
    }

    /** Channel key off */
    private final void keyOff(int i) {
        if (slotOnFlag[i * 2 + 1] != 0) {
            car(i).slotOff();
        }
        keyStatus[i] = 0;
    }

    /** Set sustine parameter */
    private final void setSustine(int c, int sustine) {
        car(c).sustine = sustine;
        if (mod(c).type != 0) {
            mod(c).sustine = sustine;
        }
    }

    /** Volume : 6bit ( Volume register << 2 ) */
    final private void setVolume(int c, int volume) {
        car(c).volume = volume;
    }

    /** Set F-Number ( fnum : 9bit ) */
    private final void setFNumber(int c, int fnum) {
        car(c).fnum = fnum;
        mod(c).fnum = fnum;
    }

    /** Set Block data (block : 3bit ) */
    private final void setBlock(int c, int block) {
        car(c).block = block;
        mod(c).block = block;
    }

    /** */
    private final void updateKeyStatus() {
        for (int ch = 0; ch < 6; ch++) {
            slotOnFlag[ch * 2] = slotOnFlag[ch * 2 + 1] = (hiFreq[ch]) & 0x10;
        }
    }

    /*
     * Initializing
     */

    /** */
    private void refreshInternal() {
        makeDPhaseTable();
        makeDPhaseARTable();
        makeDPhaseDRTable();
        pmDPhase = (int) (PM_SPEED * PM_DP_WIDTH / (clk / 72));
        amDPhase = (int) (AM_SPEED * AM_DP_WIDTH / (clk / 72));
    }

    /** */
    private void makeTables(int c) {
        clk = c;
        makePmTable();
        makeAmTable();
        makeDB2LinTable();
        makeAdjustTable();
        makeTllTable();
        makeRksTable();
        makeSinTable();
//      makeDefaultPatch();
        refreshInternal();
    }

    /** */
    public Opll(int clk) {
        makeTables(clk);

        this.waveForm[0] = this.fullSinTable;
        this.waveForm[1] = this.halfSinTable;

        this.mask = 0;

        reset();
    }

    /** Reset whole of OPLL except patch datas. */
    public void reset() {

        adr = 0;
        out = 0;

        pmPhase = 0;
        amPhase = 0;

        mask = 0;

        for (int i = 0; i < 12; i++) {
            slot[i] = new Slot(i % 2);
        }

        for (int i = 0; i < 6; i++) {
            keyStatus[i] = 0;
            // setPatch (i, 0);
        }

        for (int i = 0; i < 0x40; i++) {
            writeReg(i, 0);
        }
    }

    /** Force Refresh (When external program changes some parameters). */
    public void forceRefresh() {

        for (int i = 0; i < 12; i++) {
            slot[i].updatePG();
            slot[i].updateRKS();
            slot[i].updateTLL();
            slot[i].updateWF();
            slot[i].updateEG();
        }
    }

    /** */
    public void setRate(int r) {
        refreshInternal();
    }

    /*
     * Generate wave data
     */

    /** Convert Amp(0 to EG_HEIGHT) to Phase(0 to 2PI). */
    private static final int wave2_2pi(int e) {
        if ((SLOT_AMP_BITS - PG_BITS) > 0) {
            return ((e) >> (SLOT_AMP_BITS - PG_BITS));
        } else {
            return ((e) << (PG_BITS - SLOT_AMP_BITS));
        }
    }

    /** Convert Amp(0 to EG_HEIGHT) to Phase(0 to 4PI). */
    private static final int wave2_4pi(int e) {
        if ((SLOT_AMP_BITS - PG_BITS - 1) == 0) {
            return (e);
        } else if ((SLOT_AMP_BITS - PG_BITS - 1) > 0) {
            return ((e) >> (SLOT_AMP_BITS - PG_BITS - 1));
        } else {
            return ((e) << (1 + PG_BITS - SLOT_AMP_BITS));
        }
    }

    /** Convert Amp(0 to EG_HEIGHT) to Phase(0 to 8PI). */
    private static final int wave2_8pi(int e) {
        if ((SLOT_AMP_BITS - PG_BITS - 2) == 0) {
            return (e);
        } else if ((SLOT_AMP_BITS - PG_BITS - 2) > 0) {
            return ((e) >> (SLOT_AMP_BITS - PG_BITS - 2));
        } else {
            return ((e) << (2 + PG_BITS - SLOT_AMP_BITS));
        }
    }

    /* Update AM, PM unit */
    private void updateAmPm() {
        pmPhase = (pmPhase + pmDPhase) & (PM_DP_WIDTH - 1);
        amPhase = (amPhase + amDPhase) & (AM_DP_WIDTH - 1);
        lfoAm = amTable[highBits(amPhase, AM_DP_BITS - AM_PG_BITS)];
        lfoPm = pmTable[highBits(pmPhase, PM_DP_BITS - PM_PG_BITS)];
    }

    /** */
    private static final int s2e(double d) {
        return (sl2eg((int) (d / SL_STEP)) << (EG_DP_BITS - EG_BITS));
    }

    /** */
    private static final int[] SL = {
        s2e(0.0), s2e(3.0), s2e(6.0), s2e(9.0),
        s2e(12.0), s2e(15.0), s2e(18.0), s2e(21.0),
        s2e(24.0), s2e(27.0), s2e(30.0), s2e(33.0),
        s2e(36.0), s2e(39.0), s2e(42.0), s2e(48.0)
    };

    /** */
    private final short calcInternal() {
        int inst = 0, out = 0;

        updateAmPm();

        for (int i = 0; i < 12; i++) {
            slot[i].calcPhase(lfoPm);
            slot[i].calcEnvelope(lfoAm);
        }

        for (int i = 0; i < 6; i++) {
            if ((mask & maskCh(i)) == 0 && (car(i).eg_mode != EnvelopeMode.FINISH)) {
                inst += car(i).calcSlotCar(mod(i).calcSlotMod());
            }
        }

        out = inst;
        return (short) out;
    }

    /** */
    public short calc() {
        return calcInternal();
    }

    /** */
    public int setMask(int mask) {
        int ret = this.mask;
        this.mask = mask;
        return ret;
    }

    /** */
    public int toggleMask(int mask) {
        int ret = this.mask;
        this.mask ^= mask;
        return ret;
    }

    /*
     * I/O Ctrl
     */

    /** */
    private void setInstrument(int i, int inst) {
        final int[] src;
        Patch modp, carp;

        this.patchNumber[i] = inst;

        if (inst != 0) {
            src = defaultInst[inst - 1];
        } else {
            src = custInst;
        }

        modp = mod(i).patch;
        carp = car(i).patch;

        modp.am = (src[0] >> 7) & 1;
        modp.pm = (src[0] >> 6) & 1;
        modp.eg = (src[0] >> 5) & 1;
        modp.kr = (src[0] >> 4) & 1;
        modp.ml = (src[0] & 0xf);

        carp.am = (src[1] >> 7) & 1;
        carp.pm = (src[1] >> 6) & 1;
        carp.eg = (src[1] >> 5) & 1;
        carp.kr = (src[1] >> 4) & 1;
        carp.ml = (src[1] & 0xf);

        modp.kl = (src[2] >> 6) & 3;
        modp.tl = (src[2] & 0x3f);

        carp.kl = (src[3] >> 6) & 3;
        carp.wf = (src[3] >> 4) & 1;

        modp.wf = (src[3] >> 3) & 1;

        modp.fb = (src[3]) & 7;

        modp.ar = (src[4] >> 4) & 0xf;
        modp.dr = (src[4] & 0xf);

        carp.ar = (src[5] >> 4) & 0xf;
        carp.dr = (src[5] & 0xf);

        modp.sl = (src[6] >> 4) & 0xf;
        modp.rr = (src[6] & 0xf);

        carp.sl = (src[7] >> 4) & 0xf;
        carp.rr = (src[7] & 0xf);
    }

    /** */
    public void writeReg(int reg, int data) {

        data = data & 0xff;
        reg = reg & 0x3f;

        switch (reg) {
        case 0x00:
            custInst[0] = (byte) data;
            for (int i = 0; i < 6; i++) {
                if (patchNumber[i] == 0) {
                    setInstrument(i, 0);
                    mod(i).updatePG();
                    mod(i).updateRKS();
                    mod(i).updateEG();
                }
            }
            break;

        case 0x01:
            custInst[1] = (byte) data;
            for (int i = 0; i < 6; i++) {
                if (patchNumber[i] == 0) {
                    setInstrument(i, 0);
                    car(i).updatePG();
                    car(i).updateRKS();
                    car(i).updateEG();
                }
            }
            break;

        case 0x02:
            custInst[2] = (byte) data;
            for (int i = 0; i < 6; i++) {
                if (patchNumber[i] == 0) {
                    setInstrument(i, 0);
                    mod(i).updateTLL();
                }
            }
            break;

        case 0x03:
            custInst[3] = (byte) data;
            for (int i = 0; i < 6; i++) {
                if (patchNumber[i] == 0) {
                    setInstrument(i, 0);
                    mod(i).updateWF();
                    car(i).updateWF();
                }
            }
            break;

        case 0x04:
            custInst[4] = (byte) data;
            for (int i = 0; i < 6; i++) {
                if (patchNumber[i] == 0) {
                    setInstrument(i, 0);
                    mod(i).updateEG();
                }
            }
            break;

        case 0x05:
            custInst[5] = (byte) data;
            for (int i = 0; i < 6; i++) {
                if (patchNumber[i] == 0) {
                    setInstrument(i, 0);
                    car(i).updateEG();
                }
            }
            break;

        case 0x06:
            custInst[6] = (byte) data;
            for (int i = 0; i < 6; i++) {
                if (patchNumber[i] == 0) {
                    setInstrument(i, 0);
                    mod(i).updateEG();
                }
            }
            break;

        case 0x07:
            custInst[7] = (byte) data;
            for (int i = 0; i < 6; i++) {
                if (patchNumber[i] == 0) {
                    setInstrument(i, 0);
                    car(i).updateEG();
                }
            }
            break;

        case 0x10:
        case 0x11:
        case 0x12:
        case 0x13:
        case 0x14:
        case 0x15: {
            int ch = reg - 0x10;
            lowFreq[ch] = (byte) data;
            setFNumber(ch, data + ((hiFreq[ch] & 1) << 8));
            mod(ch).updateALL();
            car(ch).updateALL();
        }
            break;

        case 0x20:
        case 0x21:
        case 0x22:
        case 0x23:
        case 0x24:
        case 0x25: {
            int ch = reg - 0x20;
            hiFreq[ch] = (byte) data;

            setFNumber(ch, ((data & 1) << 8) + lowFreq[ch]);
            setBlock(ch, (data >> 1) & 7);
            setSustine(ch, (data >> 5) & 1);
            if ((data & 0x10) != 0) {
                keyOn(ch);
            } else {
                keyOff(ch);
            }
            mod(ch).updateALL();
            car(ch).updateALL();
            updateKeyStatus();
        }
            break;

        case 0x30:
        case 0x31:
        case 0x32:
        case 0x33:
        case 0x34:
        case 0x35:
            instVol[reg - 0x30] = (byte) data;
            int i = (data >> 4) & 15;
            int v = data & 15;
            setInstrument(reg - 0x30, i);
            setVolume(reg - 0x30, v << 2);
            mod(reg - 0x30).updateALL();
            car(reg - 0x30).updateALL();
            break;

        default:
            break;
        }
    }

    /** */
    public void writeIO(int adr, int val) {
        if ((adr & 1) != 0) {
            writeReg(this.adr, val);
        } else {
            this.adr = val;
        }
    }
}

/* */
