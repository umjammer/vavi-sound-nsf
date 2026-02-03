/*
 * Copyright (c) 2006 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.nsf.festalon;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import vavi.sound.fidlib.FidFilter;
import vavi.sound.fir.Constants;

import static java.lang.System.getLogger;


/**
 * Filter.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 060410 nsano initial version <br>
 */
public class Filter {

    private static final Logger logger = getLogger(Filter.class.getName());

    static final int NCOEFFS = 512;
    static final int FFI_FLOAT = 0;
    static final int FFI_INT16 = 1;

    // ----
    final int inputFormat;
    private int mrIndex;
    private final int mrRatio;
    private double acc1, acc2;
    private final int soundQ;
    private final int rate;
    private final float[] coeffs = new float[NCOEFFS];
    int soundVolume;
    private Object lrh;
    private final double lrhFactor;
    private final float[] booBuf = new float[200000];

    // 1789772.7272 / 16 / 60 = 1864
    // 1662607.1250 / 16 / 50 = 2078
    // Intermediate rate.
    private final double imRate;
    private FidFilter[] fid;

    private int cpuExt;
    private double resamplePos = 0;

    private void execSexyFilter(float[] in, float[] out, int count) {
        double mul1, mul2, vmul;

        mul1 = 94.0 / rate;
        mul2 = 24.0 / rate;
        vmul = (double) soundVolume * 3 / 2 / 100;

        int inP = 0;
        int outP = 0;

        if (count > 0 && inP < 10) { // Print first few samples of a batch
        }

        while (count != 0) {
            double ino = vmul * in[inP];
            acc1 += ((ino - acc1) * mul1);
            acc2 += ((ino - acc1 - acc2) * mul2);
            {
                float t = (float) (acc1 - ino + acc2);

                if (count % 1000 == 0) { // Debug sampling
                     // System.err.println("t (pre-norm)=" + t + " ino=" + ino + " acc1=" + acc1);
                }

                t += 32767;
                t /= 65535;

                if (outP < 10) {
                    logger.log(Level.TRACE, "Filter out[" + outP + "]: t=" + t + " ino=" + ino + " acc1=" + acc1 + " acc2=" + acc2);
                }

                if (t < 0.0)
                    t = 0.0f;
                if (t > 1.0)
                    t = 1.0f;
                // if(t>32767 || t<-32768) printf("Flow: %d\n",t);
                // if(t>32767) t=32767;
                // if(t<-32768) t=-32768;
                out[outP] = t;
            }
            inP++;
            outP++;
            count--;
        }
    }

    // filter.h
    final short[] coeffs_i16 = new short[NCOEFFS];

    /** */
    Filter(int rate, double cpuclock, boolean pal, int quality) {
        this.soundQ = quality;
        double[][] tabs = {
            Constants.coefNTSC, Constants.coefPAL
        };
        double[][] tabs2 = {
            Constants.coefNTSChi, Constants.coefPALhi
        };
        double[] tmp;
        int x;
//      int nco;
        int div;
//      int srctype;

//      nco = NCOEFFS;

        if (soundQ != 0) {
            tmp = tabs2[(pal ? 1 : 0)];
            div = 16;
//          srctype = SRC_SINC_BEST_QUALITY;
        } else {
            tmp = tabs[(pal ? 1 : 0)];
            div = 32;
//          srctype = SRC_SINC_FASTEST;
        }

        mrRatio = div;
//      int max = 0;
        for (x = 0; x < NCOEFFS >> 1; x++) {
            coeffs_i16[x] = coeffs_i16[NCOEFFS - 1 - x] = (short) (tmp[x] * 65536);
            coeffs[x] = coeffs[NCOEFFS - 1 - x] = (float) tmp[x];
//          if (Math.abs(coeffs_i16[x]) > Math.abs(max)) {
//              max = abs(coeffs_i16[x]);
//          }
        }
        this.rate = rate;

        float sum = 0;
        for (float c : coeffs) sum += c;
        logger.log(Level.DEBUG, "Filter coeffs sum: " + sum);

        imRate = cpuclock / div;
        lrhFactor = rate / imRate;

//      int error;
//      lrh = src_new(srctype, 1, error);

//      cpuExt = ac_mmflag();

        inputFormat = FFI_FLOAT;
    }

    /** */
    public int setLowPass(boolean on, int corner, int order) {
        // FESTAFILT *ff = fe->apu->ff;
        String[] spec = new String[1];
        // /* char * */int speca;

        // on = true;
        if (on) {
            // snprintf(spec,256,"HpBuZ%d/%d",2,1000);
            spec[0] = String.format("LpBuZ%d/%d", order, corner);
            // speca = spec;
            // printf("%s\n",spec);
            // p = "LpBuZ2/5000";
            // printf("%f, %s\n",imRate,spec);
            try {
                fid = FidFilter.Factory.newInstance().fid_parse(imRate, spec);
                for (FidFilter f : fid) {
                    logger.log(Level.DEBUG, f);
                }
                return 1;
            } catch (Exception e) {
                logger.log(Level.ERROR, e.getMessage(), e);
                fid = null;
                return 0;
            }
        }
        return 1;
    }

    /*
     * Returns number of samples written to out. <p> leftover is set to the
     * number of samples that need to be copied from the end of in to the
     * beginning of in. </p> <p> This filtering code assumes that almost all
     * input values stay below 32767. Do not adjust the volume in the wlookup
     * tables and the expansion sound code to be higher, or you *might* overflow
     * the FIR code. </p>
     */
    public int exec(float[] in, float[] out, int maxoutlen, int inlen, int[] leftover, int sinput) {
        int x;
        int max;
        int count = 0;
        float[] flout = booBuf;

        max = inlen & ~0x1F;
        max -= NCOEFFS;
        if (max < 0)
            max = 0;

        int floutP = 0;
        for (x = 0; x < max; x += mrRatio) {
            float acc = 0;
            int c;
            int wave; //
            float[] coeffs;

            for (c = 0, wave = x, coeffs = this.coeffs; c < NCOEFFS; c += 2) {
                acc += in[wave + c] * coeffs[c];
                acc += in[wave + 1 + c] * coeffs[1 + c];
            }
            flout[floutP] = acc;
            floutP++;
            count++;
        }

        leftover[0] = inlen - max;

        count = max / mrRatio;
        // Simple linear interpolation resampler
        float step = 1.0f / (float) lrhFactor;
        double pos = resamplePos;
        int outIndex = 0;

        while (outIndex < maxoutlen && pos < count) {
            int idx = (int) pos;
            float frac = (float) (pos - idx);
            float s1 = booBuf[idx];
            float s2 = (idx + 1 < count) ? booBuf[idx + 1] : s1; // Boundary check

            out[outIndex] = s1 + frac * (s2 - s1);

            outIndex++;
            pos += step;
        }

        resamplePos = pos - count;
    if (resamplePos < 0) resamplePos = 0;


        execSexyFilter(out, out, outIndex);
        return outIndex;
    }
}
