/*
 * Copyright (c) 2006 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.nsf.festalon;

import java.util.logging.Level;

import vavi.sound.fidlib.FidFilter;
import vavi.sound.fir.Constants;
import vavi.util.Debug;


/**
 * Filter.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 060410 nsano initial version <br>
 */
public class Filter {
    static final int NCOEFFS = 512;
    static final int FFI_FLOAT = 0;
    static final int FFI_INT16 = 1;

    // ----
    int inputFormat;
    private int mrindex;
    private int mrratio;
    private double acc1, acc2;
    private int soundq;
    private int rate;
    private float[] coeffs = new float[NCOEFFS];
    int soundVolume;
    private Object lrh;
    private double lrhfactor;
    private float[] boobuf = new float[8192];

    // 1789772.7272 / 16 / 60 = 1864
    // 1662607.1250 / 16 / 50 = 2078
    // Intermediate rate.
    private double imrate;
    private FidFilter[] fid;

    private int cpuext;

    private void execSexyFilter(float[] in, float[] out, int count) {
        double mul1, mul2, vmul;

        mul1 = (double) 94 / rate;
        mul2 = (double) 24 / rate;
        vmul = (double) soundVolume * 3 / 2 / 100;
        int inP = 0;
        int outP = 0;
        while (count != 0) {
            double ino = vmul * in[inP];
            acc1 += ((ino - acc1) * mul1);
            acc2 += ((ino - acc1 - acc2) * mul2);
            {
                float t = (float) (acc1 - ino + acc2);

                t += 32767;
                t /= 65535;
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
    short[] coeffs_i16 = new short[NCOEFFS];

    /** */
    Filter(int rate, double cpuclock, boolean pal, int quality) {
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

        if (soundq != 0) {
            tmp = tabs2[(pal ? 1 : 0)];
            div = 16;
//          srctype = SRC_SINC_BEST_QUALITY;
        } else {
            tmp = tabs[(pal ? 1 : 0)];
            div = 32;
//          srctype = SRC_SINC_FASTEST;
        }

        mrratio = div;
//      int max = 0;
        for (x = 0; x < NCOEFFS >> 1; x++) {
            coeffs_i16[x] = coeffs_i16[NCOEFFS - 1 - x] = (short) (tmp[x] * 65536);
            coeffs[x] = coeffs[NCOEFFS - 1 - x] = (float) tmp[x];
//          if (Math.abs(coeffs_i16[x]) > Math.abs(max)) {
//              max = abs(coeffs_i16[x]);
//          }
        }
        this.rate = rate;

        imrate = cpuclock / div;
        lrhfactor = rate / imrate;

//      int error;
//      lrh = src_new(srctype, 1, error);

//      cpuext = ac_mmflag();

        inputFormat = FFI_FLOAT;
    }

    /** */
    public int setLowpass(boolean on, int corner, int order) {
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
            // printf("%f, %s\n",imrate,spec);
            try {
                fid = FidFilter.Factory.newInstance().fid_parse(imrate, spec);
for (FidFilter f : fid) {
 Debug.println(Level.FINE, f);
}
                return 1;
            } catch (Exception e) {
e.printStackTrace(System.err);
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
        float[] flout = boobuf;

        max = inlen & ~0x1F;
        max -= NCOEFFS;
        if (max < 0)
            max = 0;

        int floutP = 0;
        for (x = 0; x < max; x += mrratio) {
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

        count = max / mrratio;
if (fid != null) {
        fid[0].filter_step(max / mrratio);
}
        {
//            SRC_DATA doot;
//            int error;
//
//            doot.data_in = boobuf;
//            doot.data_out = out;
//            doot.input_frames = count;
//            doot.output_frames = maxoutlen;
//            doot.src_ratio = lrhfactor;
//            doot.end_of_input = 0;
//
//            if ((error = src_process(lrh, doot))) {
//                 System.err.printf("Eeeek: %s, %d, %d\n", src_strerror(error), boobuf, out);
//                 exit(1);
//            }
//
//             if(doot.input_frames_used - count) exit(1);
//             printf("Oops: %d\n\n", doot.input_frames_used - count);
//
//             printf("%d\n",doot.output_frames_gen);
//            execSexyFilter(out, out, doot.output_frames_gen);
//            return doot.output_frames_gen;
        }

        return count;
    }
}

/* */
