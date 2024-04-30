/*
 * Fidlib digital filter designer code
 *
 * Copyright (c) 2002-2004 Jim Peters <http://uazu.net/>.  This
 * file is released under the GNU Lesser General Public License
 * (LGPL) version 2.1 as published by the Free Software
 * Foundation.  See the file COPYING_LIB for details, or visit
 * <http://www.fsf.org/licenses/licenses.html>.
 *
 * The code in this file was written to go with the Fiview app
 * (http://uazu.net/fiview/), but it may be used as a library for
 * other applications.  The idea behind this library is to allow
 * filters to be designed at run-time, which gives much greater
 * flexibility to filtering applications.
 *
 * This file depends on the fidmkf.h file which provides the
 * filter types from Tony Fisher's 'mkfilter' package.  See that
 * file for references and links used there.
 *
 * Here are some of the sources I used whilst writing this code:
 *
 * Robert Bristow-Johnson's EQ cookbook formulae:
 * http://www.harmony-central.com/Computer/Programming/Audio-EQ-Cookbook.txt
 */

package vavi.sound.fidlib;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;


/**
 * Filter specification string
 * <p>
 * The filter specification string can be used to completely specify the filter,
 * or it can be used with the frequency or frequency range missing, in which
 * case default values are picked up from values passed directly to the routine.
 * <p>
 * The spec consists of a series of letters usually followed by the order of the
 * filter and then by any other parameters required, preceded by slashes. For
 * example:
 *
 * <pre>
 *    LpBu4/20.4    Lowpass butterworth, 4th order, -3.01dB at 20.4Hz
 *    BpBu2/3-4     Bandpass butterworth, 2nd order, from 3 to 4Hz
 *    BpBu2/=3-4    Same filter, but adjusted exactly to the range given
 *    BsRe/1000/10  Bandstop resonator, Q=1000, frequency 10Hz
 * </pre>
 *
 * The routines fid_design() or fid_parse() are used to convert this spec-string
 * into filter coefficients and a description (if required).
 * <p>
 *
 * Typical usage:
 *
 * <pre>
 *  FidFilter *filt, *filt2;
 *  char *desc;
 *  FidRun *run;
 *  FidFunc *funcp;
 *  void *fbuf1, *fbuf2;
 *  int delay;
 *  void my_error_func(char *err);
 *
 *  // Design a filter, and optionally get its long description
 *  filt= fid_design(spec, rate, freq0, freq1, adj, &amp;desc);
 *
 *  // List all the possible filter types
 *  fid_list_filters(stdout);
 *  okay= fid_list_filters_buf(buf, buf+sizeof(buf));
 *
 *  // Calculate the response of the filter at a given frequency
 *  // (frequency is given as a proportion of the sampling rate, in
 *  // the range 0 to 0.5).  If phase is returned, then this is
 *  // given in the range 0 to 1 (for 0 to 2*pi).
 *  resp= fid_response(filt, freq);
 *  resp= fid_response_pha(filt, freq, &amp;phase);
 *
 *  // Estimate the signal delay caused by a particular filter, in samples
 *  delay= fid_calc_delay(filt);
 *
 *  // Run a given filter (this will do JIT filter compilation if this is
 *  // implemented for this processor / OS)
 *  run= fid_run_new(filt, &amp;funcp);
 *  fbuf1= fid_run_newbuf(run);
 *  fbuf2= fid_run_newbuf(run);
 *  while (...) {
 *     out_1= funcp(fbuf1, in_1);
 *     out_2= funcp(fbuf2, in_2);
 *     if (restart_required) fid_run_zapbuf(fbuf1);
 *     ...
 *  }
 *  fid_run_freebuf(fbuf2);
 *  fid_run_freebuf(fbuf1);
 *  fid_run_free(run);
 *
 *  // If you need to allocate your own buffers separately for some
 *  // reason, then do it this way:
 *  run= fid_run_new(filt, &amp;funcp);
 *  len= fid_run_bufsize(run);
 *  fbuf1= Alloc(len); fid_run_initbuf(run, fbuf1);
 *  fbuf2= Alloc(len); fid_run_initbuf(run, fbuf2);
 *  while (...) {
 *     out_1= funcp(fbuf1, in_1);
 *     out_2= funcp(fbuf2, in_2);
 *     if (restart_required) fid_run_zapbuf(fbuf1);
 *     ...
 *  }
 *  free(fbuf2);
 *  free(fbuf1);
 *  fid_run_free(run);
 *
 *  // Convert an arbitrary filter into a new filter which is a single
 *  // IIR/FIR pair.  This is done by convolving the coefficients.  This
 *  // flattened filter will give the same result, in theory.  However,
 *  // in practice this will be less accurate, especially in cases where
 *  // the limits of the floating point format are being reached (e.g.
 *  // subtracting numbers with small highly significant differences).
 *  // The routine also ensures that the IIR first coefficient is 1.0.
 *  filt2= fid_flatten(filt);
 *  free(filt);
 *
 *  // Parse an entire filter-spec string possibly containing several FIR,
 *  // IIR and predefined filters and return it as a FidFilter at the given
 *  // location.  Stops at the first ,; or unmatched )]} character, or the end
 *  // of the string.  Returns a strdup'd error string on error, or else 0.
 *  err= fid_parse(double rate, char **pp, FidFilter **ffp);
 *
 *  // Set up your own fatal-error handler (default is to dump a message
 *  // to STDERR and exit on fatal conditions)
 *  fid_set_error_handler(&amp;my_error_func);
 *
 *  // Get the version number of the library as a string (e.g. &quot;1.0.0&quot;)
 *  txt= fid_version();
 *
 *  // Design a filter and reduce it to a list of all the non-const
 *  // coefficients, which is returned in the given double[].  The number
 *  // of coefficients expected must be provided (as a check).
 *  #define N_COEF &lt;whatever&gt;
 *  double coef[N_COEF], gain;
 *  gain= fid_design_coef(coef, N_COEF, spec, rate, freq0, freq1, adj);
 *
 *  // Rewrite a filter spec in a full and/or separated-out form
 *  char *full, *min;
 *  double minf0, minf1;
 *  int minadj;
 *  fid_rewrite_spec(spec, freq0, freq1, adj, &amp;full, &amp;min, &amp;minf0, &amp;minf1, &amp;minadj);
 *  ...
 *  free(full); free(min);
 *
 *  // Create a FidFilter based on coefficients provided in the
 *  // given double array.
 *  static double array[]= { 'I', 3, 1.0, 0.55, 0.77, 'F', 3, 1, -2, 1, 0 };
 *  filt= fid_cv_array(array);
 *
 *  // Join a number of filters into a single filter (and free them too,
 *  // if the first argument is 1)
 *  filt= fid_cat(0, filt1, filt2, filt3, filt4, 0);
 * </pre>
 *
 * Format of returned filter
 * <p>
 * The filter returned is a single chunk of allocated memory in which is stored
 * a number of FidFilter instances. Each instance has variable length according
 * to the coefficients contained in it. It is probably easier to think of this
 * as a stream of items in memory. Each sub-filter starts with its type as a
 * short -- either 'I' for IIR filters, or 'F' for FIR filters. (Other types may
 * be added later, e.g. AM modulation elements, or whatever). This is followed
 * by a short bitmap which indicates which of the coefficients are constants,
 * aiding code-generation. Next comes the count of the following coefficients,
 * as an int. (These header fields normally takes 8 bytes, the same as a double,
 * but this might depend on the platform). Then follow the coefficients, as
 * doubles. The next sub-filter follows on straight after that. The end of the
 * list is marked by 8 zero bytes, meaning typ==0, cbm==0 and len==0.
 * <p>
 * The filter can be read with the aid of the FidFilter structure (giving typ,
 * cbm, len and val[] elements) and the FFNEXT() macro: using ff= FFNEXT(ff)
 * steps to the next FidFilter structure along the chain.
 * <p>
 * Note that within the sub-filters, coefficients are listed in the order that
 * they apply to data, from current-sample backwards in time, i.e. most recent
 * first (so an FIR val[] of 0, 0, 1 represents a two-sample delay FIR filter).
 * IIR filters are *not* necessarily adjusted so that their first coefficient is
 * 1.
 * <p>
 * Most filters have their gain pre-adjusted so that some suitable part of the
 * response is at gain==1.0. However, this depends on the filter type.
 */
public abstract class FidFilter {
    /** */
    private static final String FIDLIB_VERSION = "0.9.9";

    /** Type of filter element 'I' IIR, 'F' FIR, or 0 for end of list */
    protected int type;
    /**
     * Constant bitmap. Bits 0..14, if set, indicate that val[0..14] is a
     * constant across changes in frequency for this filter type Bit 15, if
     * set, indicates that val[15..inf] are constant.
     */
    protected int cbm;
    /** Number of doubles stored in val[], or 0 for end of list */
    protected int len;
    protected double[] val;

    /** */
    public abstract double filter_step(double val);

    /** */
    public static class Factory {
        /**
         * Select which method of filter execution is preferred. RF_CMDLIST is
         * recommended (and is the default).
         *
         * <pre>
         *    RF_COMBINED -- easy to understand code, lower accuracy
         *    RF_CMDLIST  -- faster pre-compiled code
         *    RF_JIT      -- fastest JIT run-time generated code (no longer supported)
         * </pre>
         */
        public static FidFilter newInstance() {
            return new CommandListFidFilter();
        }
    }

    /**
     * Target-specific fixes
     */
    private static double asinh(double val) {
        return Math.log(val + Math.sqrt(val * val + 1.0));
    }

    /**
     * Complex multiply: aa *= bb;
     */
    private static void cmul(double[] aa, int aaP, double[] bb, int bbP) {
        double rr = aa[aaP + 0] * bb[bbP + 0] - aa[aaP + 1] * bb[bbP + 1];
        double ii = aa[aaP + 0] * bb[bbP + 1] + aa[aaP + 1] * bb[bbP + 0];
        aa[aaP + 0] = rr;
        aa[aaP + 1] = ii;
    }

    /**
     * Complex square: aa *= aa;
     */
    private static void csqu(double[] aa, int aaP) {
        double rr = aa[aaP + 0] * aa[aaP + 0] - aa[aaP + 1] * aa[aaP + 1];
        double ii = 2 * aa[aaP + 0] * aa[aaP + 1];
        aa[aaP + 0] = rr;
        aa[aaP + 1] = ii;
    }

    /**
     * Complex multiply by real: aa *= bb;
     */
    private static void cmulr(double[] aa, int aaP, double fact) {
        aa[aaP + 0] *= fact;
        aa[aaP + 1] *= fact;
    }

    /**
     * Complex conjugate: aa= aa*
     */
    private static void cconj(double[] aa) {
        aa[1] = -aa[1];
    }

    /**
     * Complex divide: aa /= bb;
     */
    private static void cdiv(double[] aa, int aaP, double[] bb) {
        double rr = aa[aaP + 0] * bb[0] + aa[aaP + 1] * bb[1];
        double ii = -aa[aaP + 0] * bb[1] + aa[aaP + 1] * bb[0];
        double fact = 1.0 / (bb[0] * bb[0] + bb[1] * bb[1]);
        aa[aaP + 0] = rr * fact;
        aa[aaP + 1] = ii * fact;
    }

    /**
     * Complex reciprocal: aa= 1/aa
     */
    private static void crecip(double[] aa, int aaP) {
        double fact = 1.0 / (aa[aaP + 0] * aa[aaP + 0] + aa[aaP + 1] * aa[aaP + 1]);
        aa[aaP + 0] *= fact;
        aa[aaP + 1] *= -fact;
    }

    /**
     * Complex assign: aa= bb
     */
    private static void cass(double[] aa, int aaP, double[] bb, int bbP) {
        System.arraycopy(bb, bbP, aa, aaP, 2); // Assigning doubles is really slow
    }

    /**
     * Complex assign: aa= (rr + ii*j)
     */
    private static void cassz(double[] aa, int aaP, double rr, double ii) {
        aa[aaP + 0] = rr;
        aa[aaP + 1] = ii;
    }

    /**
     * Complex add: aa += bb
     */
    private static void cadd(double[] aa, int aaP, double[] bb) {
        aa[aaP + 0] += bb[0];
        aa[aaP + 1] += bb[1];
    }

    /**
     * Complex add: aa += (rr + ii*j)
     */
    private static void caddz(double[] aa, int aaP, double rr, double ii) {
        aa[aaP + 0] += rr;
        aa[aaP + 1] += ii;
    }

    /**
     * Complex subtract: aa -= bb
     */
    private static void csub(double[] aa, double[] bb) {
        aa[0] -= bb[0];
        aa[1] -= bb[1];
    }

    /**
     * Complex subtract: aa -= (rr + ii*j)
     */
    private static void csubz(double[] aa, double rr, double ii) {
        aa[0] -= rr;
        aa[1] -= ii;
    }

    /**
     * Complex negate: aa= -aa
     */
    private static void cneg(double[] aa, int aaP) {
        aa[aaP + 0] = -aa[aaP + 0];
        aa[aaP + 1] = -aa[aaP + 1];
    }

    /**
     * Evaluate a complex polynomial given the coefficients. rv[0]+i*rv[1] is
     * the result, in[0]+i*in[1] is the input value. Coefficients are real
     * values.
     */
    private static void evaluate(double[] rv, double[] coef, int n_coef, double[] in) {
        double[] pz = new double[2]; // Powers of Z
        int coefP = 0;
        // Handle first iteration by hand
        rv[0] = coef[coefP++];
        rv[1] = 0;

        if (--n_coef > 0) {
            // Handle second iteration by hand
            pz[0] = in[0];
            pz[1] = in[1];
            rv[0] += coef[coefP] * pz[0];
            rv[1] += coef[coefP] * pz[1];
            coefP++;
            n_coef--;

            // Loop for remainder
            while (n_coef > 0) {
                cmul(pz, 0, in, 0);
                rv[0] += coef[coefP] * pz[0];
                rv[1] += coef[coefP] * pz[1];
                coefP++;
                n_coef--;
            }
        }
    }

    /**
     * Housekeeping
     */
    public static String fid_version() {
        return FIDLIB_VERSION;
    }

    /**
     * Get the response and phase of a filter at the given frequency (expressed
     * as a proportion of the sampling rate, 0->0.5). Phase is returned as a
     * number from 0 to 1, representing a phase between 0 and two-pi.
     */
    protected double fid_response_pha(List<FidFilter> filt, double freq, double[] phase) {
        double[] top = new double[2], bot = new double[2];
        double theta = freq * 2 * Math.PI;
        double[] zz = new double[2];

        top[0] = 1;
        top[1] = 0;
        bot[0] = 1;
        bot[1] = 0;
        zz[0] = Math.cos(theta);
        zz[1] = Math.sin(theta);

        for (FidFilter ff : filt) {
            double[] resp = new double[2];
            int cnt = ff.len;
            evaluate(resp, ff.val, cnt, zz);
            if (ff.type == 'I') {
                cmul(bot, 0, resp, 0);
            } else if (ff.type == 'F') {
                cmul(top, 0, resp, 0);
            } else {
                throw new IllegalArgumentException(String.format("Unknown filter type %d in fid_response_pha()", ff.type));
            }
        }

        cdiv(top, 0, bot);

        if (phase != null) {
            double pha = Math.atan2(top[1], top[0]) / (2 * Math.PI);
            if (pha < 0) {
                pha += 1.0;
            }
            phase[0] = pha;
        }

        return Math.hypot(top[1], top[0]);
    }

    /**
     * Get the response of a filter at the given frequency (expressed as a
     * proportion of the sampling rate, 0.0.5).
     *
     * Code duplicate, as I didn't want the overhead of a function call to
     * fid_response_pha. Almost every call in this routine can be inlined.
     */
    protected double fid_response(List<FidFilter> filt, double freq) {
        double[] top = new double[2], bot = new double[2];
        double theta = freq * 2 * Math.PI;
        double[] zz = new double[2];

        top[0] = 1;
        top[1] = 0;
        bot[0] = 1;
        bot[1] = 0;
        zz[0] = Math.cos(theta);
        zz[1] = Math.sin(theta);

        for (FidFilter ff : filt) {
            double[] resp = new double[2];
            int cnt = ff.len;
            evaluate(resp, ff.val, cnt, zz);
            if (ff.type == 'I') {
                cmul(bot, 0, resp, 0);
            } else if (ff.type == 'F') {
                cmul(top, 0, resp, 0);
            } else {
                throw new IllegalArgumentException(String.format("Unknown filter type %d in fid_response()", ff.type));
            }
        }

        cdiv(top, 0, bot);

        return Math.hypot(top[1], top[0]);
    }

//#ifdef MOO

    /**
     * Estimate the delay that a filter causes to the signal by looking for the
     * point at which 50% of the filter calculations are complete. This involves
     * running test impulses through the filter several times. The estimated
     * delay in samples is returned.
     *
     * Delays longer than 8,000,000 samples are not handled well, as the code
     * drops out at this point rather than get stuck in an endless loop.
     */
    protected static int fid_calc_delay(List<FidFilter> filt) {
        FidFilter run1, run2;
        double tot, tot100, tot50;
        int cnt;

        run1 = new CombinedFidFilter(filt);
        run2 = new CombinedFidFilter(filt);

        // Run through to find at least the 99.9% point of filter; the r2
        // (tot100) filter runs at 4x the speed of the other one to act as
        // a reference point much further ahead in the impulse response.

        tot = Math.abs(run1.filter_step(1.0));
        tot100 = Math.abs(run2.filter_step(1.0));
        tot100 += Math.abs(run2.filter_step(0.0));
        tot100 += Math.abs(run2.filter_step(0.0));
        tot100 += Math.abs(run2.filter_step(0.0));

        for (cnt = 1; cnt < 0x1000000; cnt++) {
            tot += Math.abs(run1.filter_step(0.0));
            tot100 += Math.abs(run2.filter_step(0.0));
            tot100 += Math.abs(run2.filter_step(0.0));
            tot100 += Math.abs(run2.filter_step(0.0));
            tot100 += Math.abs(run2.filter_step(0.0));

            if (tot / tot100 >= 0.999) {
                break;
            }
        }

        // Now find the 50% point
        tot50 = tot100 / 2;
        run1 = new CombinedFidFilter(filt);
        tot = Math.abs(run1.filter_step(1.0));
        for (cnt = 0; tot < tot50; cnt++) {
            tot += Math.abs(run1.filter_step(0.0));
        }

        // Clean up, return
        return cnt;
    }

// #endif

//
// 'mkfilter'-derived code
//

    /**
     * mkfilter-derived code ---------------------
     *
     * Copyright (c) 2002-2004 Jim Peters <http://uazu.net/>. This file is released
     * under the GNU Lesser General Public License (LGPL) version 2.1 as published
     * by the Free Software Foundation. See the file COPYING_LIB for details, or
     * visit <http://www.fsf.org/licenses/licenses.html>.
     * <p>
     * This is all code derived from 'mkfilter' by Tony Fisher of the University of
     * York. I've rewritten it all in C, and given it a thorough overhaul, so there
     * is actually none of his code here any more, but it is all still very strongly
     * based on the algorithms and techniques that he used in 'mkfilter'.
     * <p>
     * For those who didn't hear, Tony Fisher died in February 2000 at the age of
     * 43. See his web-site for information and a tribute:
     *
     * <pre>
     *  http://www-users.cs.york.ac.uk/&tilde;fisher/
     *  http://www-users.cs.york.ac.uk/&tilde;fisher/tribute.html
     * </pre>
     *
     * <p>
     * The original C++ sources and the rest of the mkfilter tool-set are still
     * available from his site:
     *
     * <pre>
     *  http://www-users.cs.york.ac.uk/&tilde;fisher/mkfilter/
     * </pre>
     *
     * I've made a number of improvements and changes whilst rewriting the code in
     * C. For example, I halved the calculations required in designing the filters
     * by storing only one complex pole/zero out of each conjugate pair. This also
     * made it much easier to output the filter as a list of sub-filters without
     * lots of searching around to match up conjugate pairs later on. Outputting as
     * a list of subfilters permits greater accuracy in calculation of the response,
     * and also in the execution of the final filter. Also, some FIR coefficients
     * can be marked as 'constant', allowing optimised routines to be generated for
     * whole classes of filters, with just the variable coefficients filled in at
     * run-time.
     * <p>
     * On the down-side, complex numbers are not portably available in C before C99,
     * so complex calculations here are done on double[] arrays with inline
     * functions, which ends up looking more like assembly language than C. Never
     * mind.
     */

    /**
     * LEGAL STUFF
     *
     * Tony Fisher released his software on his University of York pages for free
     * use and free download. The software itself has no licence terms attached, nor
     * copyright messages, just the author's name, E-mail address and date. Nor are
     * there any licence terms indicated on the website. I understand that under the
     * Berne convention copyright does not have to be claimed explicitly, so these
     * are in fact copyright files by legal default. However, the intention was
     * obviously that these files should be used by others.
     * <p>
     * None of this really helps, though, if we're going to try to be 100% legally
     * correct, so I wrote to Anthony Moulds who is the contact name on Tony
     * Fisher's pages now. I explained what I planned to do with the code, and he
     * answered as follows:
     * <p>
     * (Note that I was planning to use it 'as-is' at that time, rather than rewrite
     * it as I have done now)
     *
     * <pre>
     *  &gt; To: &quot;Jim Peters&quot; &lt;jim@uazu.net&gt;
     *  &gt; From: &quot;Anthony Moulds&quot; &lt;anthony@cs.york.ac.uk&gt;
     *  &gt; Subject: RE: mkfilter source
     *  &gt; Date: Tue, 29 Oct 2002 15:30:19 -0000
     *  &gt;
     *  &gt; Hi Jim,
     *  &gt;
     *  &gt; Thanks for your email.
     *  &gt;
     *  &gt; The University will be happy to let you use Dr Fisher's mkfilter
     *  &gt; code since your intention is not to profit financially from his work.
     *  &gt;
     *  &gt; It would be nice if in some way you could acknowledge his contribution.
     *  &gt;
     *  &gt; Best wishes and good luck with your work,
     *  &gt;
     *  &gt; Anthony Moulds
     *  &gt; Senior Experimental Officer,
     *  &gt; Computer Science Department,  University of York,
     *  &gt; York, England, UK. Tel: 44(0)1904 434758  Fax: 44(0)19042767
     *  &gt; ============================================================
     *  &gt;
     *  &gt;
     *  &gt; &gt; -----Original Message-----
     *  &gt; &gt; From: Jim Peters [mailto:jim@uazu.net]
     *  &gt; &gt; Sent: Monday, October 28, 2002 12:36 PM
     *  &gt; &gt; To: anthony@cs.york.ac.uk
     *  &gt; &gt; Subject: mkfilter source
     *  &gt; &gt;
     *  &gt; &gt;
     *  &gt; &gt; I'm very sorry to hear (rather late, I know) that Tony Fisher died --
     *  &gt; &gt; I've always gone straight to the filter page, rather than through his
     *  &gt; &gt; home page.  I hope his work remains available for the future.
     *  &gt; &gt;
     *  &gt; &gt; Anyway, the reason I'm writing is to clarify the status of the
     *  &gt; &gt; mkfilter source code.  Because copyright is not claimed on the web
     *  &gt; &gt; page nor in the source distribution, I guess that Tony's intention was
     *  &gt; &gt; that this code should be in the public domain.  However, I would like
     *  &gt; &gt; to check this now to avoid complications later.
     *  &gt; &gt;
     *  &gt; &gt; I am using his code, modified, to provide a library of filter-design
     *  &gt; &gt; routines for a GPL'd filter design app, which is not yet released.
     *  &gt; &gt; The library could also be used standalone, permitting apps to design
     *  &gt; &gt; filters at run-time rather than using hard-coded compile-time filters.
     *  &gt; &gt; My interest in filters is as a part of my work on the OpenEEG project
     * </pre>
     *
     * So this looks pretty clear to me. I am not planning to profit from the work,
     * so everything is fine with the University. I guess others might profit from
     * the work, indirectly, as with any free software release, but so long as I
     * don't, we're fine.
     * <p>
     * I hope this is watertight enough for Debian/etc. Otherwise I'll have to go
     * back to Anthony Moulds for clarification.
     * <p>
     * Even though there is no code cut-and-pasted from 'mkfilter' here, it is all
     * very obviously based on that code, so it probably counts as a derived work --
     * although as ever "I Am Not A Lawyer".
     */

    private static final double TWOPI = Math.PI * 2;

    /**
     * Complex square root: aa= aa^0.5
     */
    private static double my_sqrt(double aa) {
        return aa <= 0.0 ? 0.0 : Math.sqrt(aa);
    }

    /** */
    private static void csqrt(double[] aa, int aaP) {
        double mag = Math.hypot(aa[aaP + 0], aa[aaP + 1]);
        double rr = my_sqrt((mag + aa[aaP + 0]) * 0.5);
        double ii = my_sqrt((mag - aa[aaP + 0]) * 0.5);
        if (aa[aaP + 1] < 0.0) {
            ii = -ii;
        }
        aa[aaP + 0] = rr;
        aa[aaP + 1] = ii;
    }

    /**
     * Complex imaginary exponent: aa= e^i.theta
     */
    private static void cexpj(double[] aa, int aaP, double theta) {
        aa[aaP + 0] = Math.cos(theta);
        aa[aaP + 1] = Math.sin(theta);
    }

    /**
     * Complex exponent: aa= e^aa
     */
    private static void cexp(double[] aa, int aaP) {
        double mag = Math.exp(aa[aaP + 0]);
        aa[aaP + 0] = mag * Math.cos(aa[aaP + 1]);
        aa[aaP + 1] = mag * Math.sin(aa[aaP + 1]);
    }

    /**
     * Global temp buffer for generating filters. *NOT THREAD SAFE*
     * <p>
     * Note that the poles and zeros are stored in a strange way. Rather than
     * storing both a pole (or zero) and its complex conjugate, I'm storing just one
     * of the pair. Also, for real poles, I'm not storing the imaginary part (which
     * is zero). This results in a list of numbers exactly half the length you might
     * otherwise expect. However, since some of these numbers are in pairs, and some
     * are single, we need a separate flag array to indicate which is which.
     * poltyp[] serves this purpose. An entry is 1 if the corresponding offset is a
     * real pole, or 2 if it is the first of a pair of values making up a complex
     * pole. The second value of the pair has an entry of 0 attached. (Similarly for
     * zeros in zertyp[])
     */

    private static final int MAXPZ = 64;

    /** Number of poles */
    private int n_pol;
    /** Pole values (see above) */
    private double[] pol = new double[MAXPZ];
    /** Pole value types: 1 real, 2 first of complex pair, 0 second */
    private byte[] poltyp = new byte[MAXPZ];
    /** Same for zeros ... */
    private int n_zer;
    private double[] zer = new double[MAXPZ];
    private byte[] zertyp = new byte[MAXPZ];

    /**
     * Pre-warp a frequency
     */
    private static double prewarp(double val) {
        return Math.tan(val * Math.PI) / Math.PI;
    }

    /**
     * Bessel poles; final one is a real value for odd numbers of poles
     */

    private static final double[] bessel_1= {
        -1.00000000000e+00
    };

    private static final double[] bessel_2= {
        -1.10160133059e+00, 6.36009824757e-01,
    };

    private static final double[] bessel_3= {
        -1.04740916101e+00, 9.99264436281e-01,
        -1.32267579991e+00,
    };

    private static final double[] bessel_4= {
        -9.95208764350e-01, 1.25710573945e+00,
        -1.37006783055e+00, 4.10249717494e-01,
    };

    private static final double[] bessel_5= {
        -9.57676548563e-01, 1.47112432073e+00,
        -1.38087732586e+00, 7.17909587627e-01,
        -1.50231627145e+00,
    };

    private static final double[] bessel_6= {
        -9.30656522947e-01, 1.66186326894e+00,
        -1.38185809760e+00, 9.71471890712e-01,
        -1.57149040362e+00, 3.20896374221e-01,
    };

    private static final double[] bessel_7= {
        -9.09867780623e-01, 1.83645135304e+00,
        -1.37890321680e+00, 1.19156677780e+00,
        -1.61203876622e+00, 5.89244506931e-01,
        -1.68436817927e+00,
    };

    private static final double[] bessel_8 = {
        -8.92869718847e-01, 1.99832584364e+00,
        -1.37384121764e+00, 1.38835657588e+00,
        -1.63693941813e+00, 8.22795625139e-01,
        -1.75740840040e+00, 2.72867575103e-01,
    };

    private static final double[] bessel_9= {
        -8.78399276161e-01, 2.14980052431e+00,
        -1.36758830979e+00, 1.56773371224e+00,
        -1.65239648458e+00, 1.03138956698e+00,
        -1.80717053496e+00, 5.12383730575e-01,
        -1.85660050123e+00,
    };

    private static final double[] bessel_10= {
        -8.65756901707e-01, 2.29260483098e+00,
        -1.36069227838e+00, 1.73350574267e+00,
        -1.66181024140e+00, 1.22110021857e+00,
        -1.84219624443e+00, 7.27257597722e-01,
        -1.92761969145e+00, 2.41623471082e-01,
    };

    private static final double[][] bessel_poles= {
        bessel_1, bessel_2, bessel_3, bessel_4, bessel_5,
        bessel_6, bessel_7, bessel_8, bessel_9, bessel_10
    };

    /**
     * Generate Bessel poles for the given order.
     *
     * @param order max is 10
     */
    private void bessel(int order) {
        int a;

        if (order > 10) {
            throw new IllegalArgumentException("Maximum Bessel order is 10");
        }
        n_pol = order;
        System.arraycopy(bessel_poles[order - 1], 0, pol, 0, n_pol);

        for (a = 0; a < order - 1;) {
            poltyp[a++] = 2;
            poltyp[a++] = 0;
        }
        if (a < order) {
            poltyp[a++] = 1;
        }
    }

    /**
     * Generate Butterworth poles for the given order. These are
     * regularly-spaced points on the unit circle to the left of the real==0
     * line.
     *
     * @param order max is {@link #MAXPZ}
     */
    private void butterworth(int order) {
        int a;
        if (order > MAXPZ) {
            throw new IllegalArgumentException("Maximum butterworth/chebyshev order is " + MAXPZ);
        }
        n_pol = order;
        for (a = 0; a < order - 1; a += 2) {
            poltyp[a] = 2;
            poltyp[a + 1] = 0;
            cexpj(pol, a, Math.PI - (order - a - 1) * 0.5 * Math.PI / order);
        }
        if (a < order) {
            poltyp[a] = 1;
            pol[a] = -1.0;
        }
    }

    /**
     * Generate Chebyshev poles for the given order and ripple.
     */
    private void chebyshev(int order, double ripple) {
        double eps, y;
        double sh, ch;
        int a;

        butterworth(order);
        if (ripple >= 0.0) {
            throw new IllegalArgumentException("Chebyshev ripple in dB should be -ve");
        }

        eps = Math.sqrt(-1.0 + Math.pow(10.0, -0.1 * ripple));
        y = asinh(1.0 / eps) / order;
        if (y <= 0.0) {
            throw new IllegalArgumentException("Chebyshev y-value <= 0.0: " + y);
        }
        sh = Math.sinh(y);
        ch = Math.cosh(y);

        for (a = 0; a < n_pol;) {
            if (poltyp[a] == 1) {
                pol[a++] *= sh;
            } else {
                pol[a++] *= sh;
                pol[a++] *= ch;
            }
        }
    }

    /**
     * Adjust raw poles to LP filter
     */
    private void lowpass(double freq) {
        int a;

        // Adjust poles
        freq *= TWOPI;
        for (a = 0; a < n_pol; a++) {
            pol[a] *= freq;
        }

        // Add zeros
        n_zer = n_pol;
        for (a = 0; a < n_zer; a++) {
            zer[a] = Double.MIN_VALUE;
            zertyp[a] = 1;
        }
    }

    /**
     * Adjust raw poles to HP filter
     */
    private void highpass(double freq) {
        int a;

        // Adjust poles
        freq *= TWOPI;
        for (a = 0; a < n_pol;) {
            if (poltyp[a] == 1) {
                pol[a] = freq / pol[a];
                a++;
            } else {
                crecip(pol, a);
                pol[a++] *= freq;
                pol[a++] *= freq;
            }
        }

        // Add zeros
        n_zer = n_pol;
        for (a = 0; a < n_zer; a++) {
            zer[a] = 0.0;
            zertyp[a] = 1;
        }
    }

    /**
     * Adjust raw poles to BP filter. The number of poles is doubled.
     */
    private void bandpass(double freq1, double freq2) {
        double w0 = TWOPI * Math.sqrt(freq1 * freq2);
        double bw = 0.5 * TWOPI * (freq2 - freq1);
        int a, b;

        if (n_pol * 2 > MAXPZ) {
            throw new IllegalArgumentException("Maximum order for bandpass filters is " + MAXPZ / 2);
        }

        // Run through the list backwards, expanding as we go
        for (a = n_pol, b = n_pol * 2; a > 0;) {
            // hba= pole * bw;
            // temp= csqrt(1.0 - square(w0 / hba));
            // pole1= hba * (1.0 + temp);
            // pole2= hba * (1.0 - temp);

            if (poltyp[a - 1] == 1) {
                double hba;
                a--;
                b -= 2;
                poltyp[b] = 2;
                poltyp[b + 1] = 0;
                hba = pol[a] * bw;
                cassz(pol, b, 1.0 - (w0 / hba) * (w0 / hba), 0.0);
                csqrt(pol, b);
                caddz(pol, b, 1.0, 0.0);
                cmulr(pol, b, hba);
            } else { // Assume poltyp[] data is valid
                double[] hba = new double[2];
                a -= 2;
                b -= 4;
                poltyp[b] = 2;
                poltyp[b + 1] = 0;
                poltyp[b + 2] = 2;
                poltyp[b + 3] = 0;
                cass(hba, 0, pol, a);
                cmulr(hba, 0, bw);
                cass(pol, b, hba, 0);
                crecip(pol, b);
                cmulr(pol, b, w0);
                csqu(pol, b);
                cneg(pol, b);
                caddz(pol, b, 1.0, 0.0);
                csqrt(pol, b);
                cmul(pol, b, hba, 0);
                cass(pol, b + 2, pol, b);
                cneg(pol, b + 2);
                cadd(pol, b, hba);
                cadd(pol, b + 2, hba);
            }
        }
        n_pol *= 2;

        // Add zeros
        n_zer = n_pol;
        for (a = 0; a < n_zer; a++) {
            zertyp[a] = 1;
            zer[a] = (a < n_zer / 2) ? 0.0 : Double.MIN_VALUE;
        }
    }

    /**
     * Adjust raw poles to BS filter. The number of poles is doubled.
     */
    private void bandstop(double freq1, double freq2) {
        double w0 = TWOPI * Math.sqrt(freq1 * freq2);
        double bw = 0.5 * TWOPI * (freq2 - freq1);
        int a, b;

        if (n_pol * 2 > MAXPZ) {
            throw new IllegalArgumentException("Maximum order for bandstop filters is " + MAXPZ / 2);
        }

        // Run through the list backwards, expanding as we go
        for (a = n_pol, b = n_pol * 2; a > 0;) {
//          hba= bw / pole;
//          temp= csqrt(1.0 - square(w0 / hba));
//          pole1= hba * (1.0 + temp);
//          pole2= hba * (1.0 - temp);

            if (poltyp[a - 1] == 1) {
                double hba;
                a--;
                b -= 2;
                poltyp[b] = 2;
                poltyp[b + 1] = 0;
                hba = bw / pol[a];
                cassz(pol, b, 1.0 - (w0 / hba) * (w0 / hba), 0.0);
                csqrt(pol, b);
                caddz(pol, b, 1.0, 0.0);
                cmulr(pol, b, hba);
            } else { // assume poltyp[] data is valid
                double[] hba = new double[2];
                a -= 2;
                b -= 4;
                poltyp[b] = 2;
                poltyp[b + 1] = 0;
                poltyp[b + 2] = 2;
                poltyp[b + 3] = 0;
                cass(hba, 0, pol, a);
                crecip(hba, 0);
                cmulr(hba, 0, bw);
                cass(pol, b, hba, 0);
                crecip(pol, b);
                cmulr(pol, b, w0);
                csqu(pol, b);
                cneg(pol, b);
                caddz(pol, b, 1.0, 0.0);
                csqrt(pol, b);
                cmul(pol, b, hba, 0);
                cass(pol, b + 2, pol, b);
                cneg(pol, b + 2);
                cadd(pol, b, hba);
                cadd(pol, b + 2, hba);
            }
        }
        n_pol *= 2;

        // add zeros
        n_zer = n_pol;
        for (a = 0; a < n_zer; a += 2) {
            zertyp[a] = 2;
            zertyp[a + 1] = 0;
            zer[a] = 0.0;
            zer[a + 1] = w0;
        }
    }

    /**
     * Convert list of poles+zeros from S to Z using bilinear transform
     */
    private void s2z_bilinear() {
        for (int a = 0; a < n_pol;) {
            // calculate (2 + val) / (2 - val)
            if (poltyp[a] == 1) {
                if (pol[a] == Double.MIN_VALUE) {
                    pol[a] = -1.0;
                } else {
                    pol[a] = (2 + pol[a]) / (2 - pol[a]);
                }
                a++;
            } else {
                double[] val = new double[2];
                cass(val, 0, pol, a);
                cneg(val, 0);
                caddz(val, 0, 2, 0);
                caddz(pol, a, 2, 0);
                cdiv(pol, a, val);
                a += 2;
            }
        }
        for (int a = 0; a < n_zer;) {
            // calculate (2 + val) / (2 - val)
            if (zertyp[a] == 1) {
                if (zer[a] == Double.MIN_VALUE) {
                    zer[a] = -1.0;
                } else {
                    zer[a] = (2 + zer[a]) / (2 - zer[a]);
                }
                a++;
            } else {
                double[] val = new double[2];
                cass(val, 0, zer, a);
                cneg(val, 0);
                caddz(val, 0, 2, 0);
                caddz(zer, a, 2, 0);
                cdiv(zer, a, val);
                a += 2;
            }
        }
    }

    /**
     * Convert S to Z using matched-Z transform
     */
    private void s2z_matchedZ() {
        for (int a = 0; a < n_pol;) {
            // calculate cexp(val)
            if (poltyp[a] == 1) {
                if (pol[a] == Double.MIN_VALUE) {
                    pol[a] = 0.0;
                } else {
                    pol[a] = Math.exp(pol[a]);
                }
                a++;
            } else {
                cexp(pol, a);
                a += 2;
            }
        }
        for (int a = 0; a < n_zer;) {
            // calculate cexp(val)
            if (zertyp[a] == 1) {
                if (zer[a] == Double.MIN_VALUE) {
                    zer[a] = 0.0;
                } else {
                    zer[a] = Math.exp(zer[a]);
                }
                a++;
            } else {
                cexp(zer, a);
                a += 2;
            }
        }
    }

    /**
     * Generate a FidFilter for the current set of poles and zeros. The given
     * gain is inserted at the start of the FidFilter as a one-coefficient FIR
     * filter. This is positioned to be easily adjusted later to correct the
     * filter gain.
     *
     * 'cbm' should be a bitmap indicating which FIR coefficients are constants
     * for this filter type. Normal values are ~0 for all constant, or 0 for
     * none constant, or some other bitmask for a mixture. Filters generated
     * with lowpass(), highpass() and bandpass() above should pass ~0, but
     * bandstop() requires 0x5.
     *
     * This routine requires that any lone real poles/zeros are at the end of
     * the list. All other poles/zeros are handled in pairs (whether pairs of
     * real poles/zeros, or conjugate pairs).
     */
    private List<FidFilter> z2fidfilter(double gain, int cbm) {
        int n_head /*, n_val */;
        int a;
        List<FidFilter> rv;
        FidFilter ff;

        n_head = 1 + n_pol + n_zer; // worst case: gain + 2-element IIR/FIR
//      n_val = 1 + 2 * (n_pol + n_zer); // for each pole/zero

        rv = new ArrayList<>(n_head);
        ff = Factory.newInstance();
        ff.type = 'F';
        ff.len = 1;
        ff.val[0] = gain;
        rv.add(ff);

        // output as much as possible as 2x2 IIR/FIR filters
        for (a = 0; a <= n_pol - 2 && a <= n_zer - 2; a += 2) {
            // look for a pair of values for an IIR
            if (poltyp[a] == 1 && poltyp[a + 1] == 1) {
                // two real values
                ff = Factory.newInstance();
                ff.type = 'I';
                ff.len = 3;
                ff.val[0] = 1;
                ff.val[1] = -(pol[a] + pol[a + 1]);
                ff.val[2] = pol[a] * pol[a + 1];
                rv.add(ff);
            } else if (poltyp[a] == 2) {
                // a complex value and its conjugate pair
                ff = Factory.newInstance();
                ff.type = 'I';
                ff.len = 3;
                ff.val[0] = 1;
                ff.val[1] = -2 * pol[a];
                ff.val[2] = pol[a] * pol[a] + pol[a + 1] * pol[a + 1];
                rv.add(ff);
            } else {
                throw new IllegalArgumentException("Internal error -- bad poltyp[] values for z2fidfilter()");
            }

            // look for a pair of values for an FIR
            if (zertyp[a] == 1 && zertyp[a + 1] == 1) {
                // Two real values
                // Skip if constant and 0/0
                if (cbm == 0 || zer[a] != 0.0 || zer[a + 1] != 0.0) {
                    ff = Factory.newInstance();
                    ff.type = 'F';
                    ff.cbm = (short) cbm;
                    ff.len = 3;
                    ff.val[0] = 1;
                    ff.val[1] = -(zer[a] + zer[a + 1]);
                    ff.val[2] = zer[a] * zer[a + 1];
                    rv.add(ff);
                }
            } else if (zertyp[a] == 2) {
                // a complex value and its conjugate pair
                // skip if constant and 0/0
                if (cbm == 0 || zer[a] != 0.0 || zer[a + 1] != 0.0) {
                    ff = Factory.newInstance();
                    ff.type = 'F';
                    ff.cbm = (short) cbm;
                    ff.len = 3;
                    ff.val[0] = 1;
                    ff.val[1] = -2 * zer[a];
                    ff.val[2] = zer[a] * zer[a] + zer[a + 1] * zer[a + 1];
                    rv.add(ff);
                }
            } else {
                throw new IllegalArgumentException("Internal error -- bad zertyp[] values");
            }
        }

        // clear up any remaining bits and pieces. Should only be a 1x1
        // IIR/FIR.
        if (n_pol - a == 0 && n_zer - a == 0) {
            //
        } else if (n_pol - a == 1 && n_zer - a == 1) {
            if (poltyp[a] != 1 || zertyp[a] != 1) {
                throw new IllegalArgumentException("Internal error; bad poltyp or zertyp for final pole/zero");
            }
            ff = Factory.newInstance();
            ff.type = 'I';
            ff.len = 2;
            ff.val[0] = 1;
            ff.val[1] = -pol[a];
            rv.add(ff);

            // Skip FIR if it is constant and zero
            if (cbm == 0 || zer[a] != 0.0) {
                ff = Factory.newInstance();
                ff.type = 'F';
                ff.cbm = cbm;
                ff.len = 2;
                ff.val[0] = 1;
                ff.val[1] = -zer[a];
                rv.add(ff);
            }
        } else {
            throw new IllegalArgumentException("Internal error: unexpected poles/zeros at end of list");
        }

        // End of list
        ff = Factory.newInstance();
        ff.type = 0;
        ff.len = 0;
        rv.add(ff);

        return rv;
    }

    /**
     * Setup poles/zeros for a band-pass resonator. 'qfact' gives the Q-factor;
     * 0 is a special value indicating +infinity, giving an oscillator.
     */
    private void bandpass_res(double freq, double qfact) {
        double mag;
        double th0, th1, th2;
        double theta = freq * TWOPI;
        double[] val = new double[2];
        double[] tmp1 = new double[2], tmp2 = new double[2], tmp3 = new double[2], tmp4 = new double[2];
        int cnt;

        n_pol = 2;
        poltyp[0] = 2;
        poltyp[1] = 0;
        n_zer = 2;
        zertyp[0] = 1;
        zertyp[1] = 1;
        zer[0] = 1;
        zer[1] = -1;

        if (qfact == 0.0) {
            cexpj(pol, 0, theta);
            return;
        }

        // do a full binary search, rather than seeding it as Tony Fisher does
        cexpj(val, 0, theta);
        mag = Math.exp(-theta / (2.0 * qfact));
        th0 = 0;
        th2 = Math.PI;
        for (cnt = 60; cnt > 0; cnt--) {
            th1 = 0.5 * (th0 + th2);
            cexpj(pol, 0, th1);
            cmulr(pol, 0, mag);

            // evaluate response of filter for Z= val
            System.arraycopy(val, 0, tmp1, 0, 2);
            System.arraycopy(val, 0, tmp2, 0, 2);
            System.arraycopy(val, 0, tmp3, 0, 2);
            System.arraycopy(val, 0, tmp4, 0, 2);
            csubz(tmp1, 1, 0);
            csubz(tmp2, -1, 0);
            cmul(tmp1, 0, tmp2, 0);
            csub(tmp3, pol);
            cconj(pol);
            csub(tmp4, pol);
            cconj(pol);
            cmul(tmp3, 0, tmp4, 0);
            cdiv(tmp1, 0, tmp3);

            if (Math.abs(tmp1[1] / tmp1[0]) < 1e-10) {
                break;
            }

            // printf("%-24.16g%-24.16g -> %-24.16g%-24.16g\n", th0, th2,
            // tmp1[0], tmp1[1]);

            if (tmp1[1] > 0.0) {
                th2 = th1;
            } else {
                th0 = th1;
            }
        }

        if (cnt <= 0) {
            throw new IllegalStateException("Resonator binary search failed to converge");
        }
    }

    /**
     * Setup poles/zeros for a bandstop resonator
     */
    private void bandstop_res(double freq, double qfact) {
        bandpass_res(freq, qfact);
        zertyp[0] = 2;
        zertyp[1] = 0;
        cexpj(zer, 0, TWOPI * freq);
    }

    /**
     * Setup poles/zeros for an allpass resonator
     */
    private void allpass_res(double freq, double qfact) {
        bandpass_res(freq, qfact);
        zertyp[0] = 2;
        zertyp[1] = 0;
        System.arraycopy(pol, 0, zer, 0, 2);
        cmulr(zer, 0, 1.0 / (zer[0] * zer[0] + zer[1] * zer[1]));
    }

    /**
     * Setup poles/zeros for a proportional-integral filter
     */
    private void prop_integral(double freq) {
        n_pol = 1;
        poltyp[0] = 1;
        pol[0] = 0.0;
        n_zer = 1;
        zertyp[0] = 1;
        zer[0] = -TWOPI * freq;
    }

    // END

    /**
     * Stack a number of identical filters, generating the required FidFilter
     * return value
     */
    private static List<FidFilter> stack_filter(int order, int n_head, int n_val, Object... ap) {
        List<FidFilter> rv = new ArrayList<>(n_head * order);
        FidFilter p;
//      FidFilter q;
        int a, b, len;

        if (order == 0) {
            return rv;
        }

        // Copy from ap
        int apP = 0;
        for (a = 0; a < n_head; a++) {
            p = Factory.newInstance();
            p.type = (Short) ap[apP++];
            p.cbm = (Short) ap[apP++];
            p.len = (Integer) ap[apP++];
            for (b = 0; b < p.len; b++) {
                p.val[b] = (Double) ap[apP++];
            }
            rv.add(p);
        }
        order--;

        // Check length
        len = rv.size();
        if (len != n_head - 1) {
            throw new IllegalArgumentException(String.format("Internal error; bad call to stack_filter(); length mismatch (%d,%d)", len, n_head - 1));
        }

        // Make as many additional copies as necessary
//        while (order-- > 0) {
//            memcpy(p, q, len);
//            p = len + p;
//        }

        // List is already terminated due to zeroed allocation
        return rv;
    }

    /**
     * Search for a peak between two given frequencies. It is assumed that the
     * gradient goes upwards from 'f0' to the peak, and then down again to 'f3'.
     * If there are any other curves, this routine will get confused and will
     * come up with some frequency, although probably not the right one.
     *
     * Returns the frequency of the peak.
     */
    private double search_peak(List<FidFilter> ff, double f0, double f3) {
        double f1, f2;
        double r1, r2;
        int a;

        // binary search, modified, taking two intermediate points. Do 20
        // subdivisions, which should give 1/2^20 == 1e-6 accuracy compared
        // to original range.
        for (a = 0; a < 20; a++) {
            f1 = 0.51 * f0 + 0.49 * f3;
            f2 = 0.49 * f0 + 0.51 * f3;
            if (f1 == f2)
                break; // We're hitting FP limit
            r1 = fid_response(ff, f1);
            r2 = fid_response(ff, f2);
            if (r1 > r2)  {
                // peak is either to the left, or between f1/f2
                f3 = f2;
            } else {
                // peak is either to the right, or between f1/f2
                f0 = f1;
            }
        }
        return (f0 + f3) * 0.5;
    }

    /**
     * Handle the different 'back-ends' for Bessel, Butterworth and Chebyshev
     * filters. First argument selects between bilinear (0) and matched-Z
     * (non-0). The BL and MZ macros makes this a bit more obvious in the code.
     *
     * Overall filter gain is adjusted to give the peak at 1.0. This is easy for
     * all types except for band-pass, where a search is required to find the
     * precise peak. This is much slower than the other types.
     */

    static final int BL = 0;

    static final int MZ = 1;

    private List<FidFilter> do_lowpass(int mz, double freq) {
        List<FidFilter> rv;
        lowpass(prewarp(freq));
        if (mz != 0) {
            s2z_matchedZ();
        } else {
            s2z_bilinear();
        }
        rv = z2fidfilter(1.0, ~0); // FIR is constant
        rv.get(0).val[0] = 1.0 / fid_response(rv, 0.0);
        return rv;
    }

    private List<FidFilter> do_highpass(int mz, double freq) {
        List<FidFilter> rv;
        highpass(prewarp(freq));
        if (mz != 0) {
            s2z_matchedZ();
        } else {
            s2z_bilinear();
        }
        rv = z2fidfilter(1.0, ~0); // FIR is constant
        rv.get(0).val[0] = 1.0 / fid_response(rv, 0.5);
        return rv;
    }

    private List<FidFilter> do_bandpass(int mz, double f0, double f1) {
        List<FidFilter> rv;
        bandpass(prewarp(f0), prewarp(f1));
        if (mz != 0) {
            s2z_matchedZ();
        } else {
            s2z_bilinear();
        }
        rv = z2fidfilter(1.0, ~0); // FIR is constant
        rv.get(0).val[0] = 1.0 / fid_response(rv, search_peak(rv, f0, f1));
        return rv;
    }

    private List<FidFilter> do_bandstop(int mz, double f0, double f1) {
        List<FidFilter> rv;
        bandstop(prewarp(f0), prewarp(f1));
        if (mz != 0) {
            s2z_matchedZ();
        } else {
            s2z_bilinear();
        }
        // FIR second coefficient is *non-const* for bandstop
        rv = z2fidfilter(1.0, 5);
        // Use 0Hz response as reference
        rv.get(0).val[0] = 1.0 / fid_response(rv, 0.0);
        return rv;
    }

    //
    // Filter design routines and supporting code
    //

    private Filter des_bpre = new Filter("BpRe/#V/#F", "Bandpass resonator, Q=#V (0 means Inf), frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            bandpass_res(f0, arg[0]);
            return z2fidfilter(1.0, ~0); // FIR constant
        }
    };

    private Filter des_bsre = new Filter("BsRe/#V/#F", "Bandstop resonator, Q=#V (0 means Inf), frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            bandstop_res(f0, arg[0]);
            return z2fidfilter(1.0, 0); // FIR not constant, depends on freq
        }
    };

    private Filter des_apre = new Filter("ApRe/#V/#F", "Allpass resonator, Q=#V (0 means Inf), frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            allpass_res(f0, arg[0]);
            return z2fidfilter(1.0, 0); // FIR not constant, depends on freq
        }
    };

    private Filter des_pi = new Filter("Pi/#F", "Proportional-integral filter, frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            prop_integral(prewarp(f0));
            s2z_bilinear();
            return z2fidfilter(1.0, 0); // FIR not constant, depends on freq
        }
    };

    private Filter des_piz = new Filter("PiZ/#F", "Proportional-integral filter, matched z-transform, frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            prop_integral(prewarp(f0));
            s2z_matchedZ();
            return z2fidfilter(1.0, 0); // FIR not constant, depends on freq
        }
    };

    private Filter des_lpbe = new Filter("LpBe#O/#F", "Lowpass Bessel filter, order #O, -3.01dB frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            bessel(order);
            return do_lowpass(BL, f0);
        }
    };

    private Filter des_hpbe = new Filter("HpBe#O/#F", "Highpass Bessel filter, order #O, -3.01dB frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            bessel(order);
            return do_highpass(BL, f0);
        }
    };

    private Filter des_bpbe = new Filter("BpBe#O/#R", "Bandpass Bessel filter, order #O, -3.01dB frequencies #R") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            bessel(order);
            return do_bandpass(BL, f0, f1);
        }
    };

    private Filter des_bsbe = new Filter("BsBe#O/#R", "Bandstop Bessel filter, order #O, -3.01dB frequencies #R") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            bessel(order);
            return do_bandstop(BL, f0, f1);
        }
    };

    private Filter des_lpbez = new Filter("LpBeZ#O/#F", "Lowpass Bessel filter, matched z-transform, order #O, -3.01dB frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            bessel(order);
            return do_lowpass(MZ, f0);
        }
    };

    private Filter des_hpbez = new Filter("HpBeZ#O/#F", "Highpass Bessel filter, matched z-transform, order #O, -3.01dB frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            bessel(order);
            return do_highpass(MZ, f0);
        }
    };

    private Filter des_bpbez = new Filter("BpBeZ#O/#R", "Bandpass Bessel filter, matched z-transform, order #O, -3.01dB frequencies #R") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            bessel(order);
            return do_bandpass(MZ, f0, f1);
        }
    };

    private Filter des_bsbez = new Filter("BsBeZ#O/#R", "Bandstop Bessel filter, matched z-transform, order #O, -3.01dB frequencies #R") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            bessel(order);
            return do_bandstop(MZ, f0, f1);
        }
    };

    private Filter des_lpbube = new Filter("LpBuBe#O/#V/#F", "Lowpass Butterworth-Bessel #V% cross, order #O, -3.01dB frequency #F") {
        List<FidFilter> rout // Butterworth-Bessel cross
        (double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            double[] tmp = new double[MAXPZ];
            int a;
            bessel(order);
            System.arraycopy(pol, 0, tmp, 0, order);
            butterworth(order);
            for (a = 0; a < order; a++)
                pol[a] += (tmp[a] - pol[a]) * 0.01 * arg[0];
            // for (a= 1; a<order; a+=2) pol[a] += arg[1] * 0.01;
            return do_lowpass(BL, f0);
        }
    };

    private Filter des_lpbu = new Filter("LpBu#O/#F", "Lowpass Butterworth filter, order #O, -3.01dB frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            butterworth(order);
            return do_lowpass(BL, f0);
        }
    };

 private Filter des_hpbu = new Filter("HpBu#O/#F", "Highpass Butterworth filter, order #O, -3.01dB frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            butterworth(order);
            return do_highpass(BL, f0);
        }
    };

    private Filter des_bpbu = new Filter("BpBu#O/#R", "Bandpass Butterworth filter, order #O, -3.01dB frequencies #R") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            butterworth(order);
            return do_bandpass(BL, f0, f1);
        }
    };

    private Filter des_bsbu = new Filter("BsBu#O/#R", "Bandstop Butterworth filter, order #O, -3.01dB frequencies #R") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            butterworth(order);
            return do_bandstop(BL, f0, f1);
        }
    };

    private Filter des_lpbuz = new Filter("LpBuZ#O/#F", "Lowpass Butterworth filter, matched z-transform, order #O, -3.01dB frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            butterworth(order);
            return do_lowpass(MZ, f0);
        }
    };

    private Filter des_hpbuz = new Filter("HpBuZ#O/#F", "Highpass Butterworth filter, matched z-transform, order #O, -3.01dB frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            butterworth(order);
            return do_highpass(MZ, f0);
        }
    };

    private Filter des_bpbuz = new Filter("BpBuZ#O/#R", "Bandpass Butterworth filter, matched z-transform, order #O, -3.01dB frequencies #R") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            butterworth(order);
            return do_bandpass(MZ, f0, f1);
        }
    };

    private Filter des_bsbuz = new Filter("BsBuZ#O/#R", "Bandstop Butterworth filter, matched z-transform, order #O, -3.01dB frequencies #R") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            butterworth(order);
            return do_bandstop(MZ, f0, f1);
        }
    };

    private Filter des_lpch = new Filter("LpCh#O/#V/#F", "Lowpass Chebyshev filter, order #O, passband ripple #VdB, -3.01dB frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            chebyshev(order, arg[0]);
            return do_lowpass(BL, f0);
        }
    };

    private Filter des_hpch = new Filter("HpCh#O/#V/#F", "Highpass Chebyshev filter, order #O, passband ripple #VdB, -3.01dB frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            chebyshev(order, arg[0]);
            return do_highpass(BL, f0);
        }
    };

    private Filter des_bpch = new Filter("BpCh#O/#V/#R", "Bandpass Chebyshev filter, order #O, passband ripple #VdB, -3.01dB frequencies #R") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            chebyshev(order, arg[0]);
            return do_bandpass(BL, f0, f1);
        }
    };

    private Filter des_bsch = new Filter("BsCh#O/#V/#R", "Bandstop Chebyshev filter, order #O, passband ripple #VdB, -3.01dB frequencies #R") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            chebyshev(order, arg[0]);
            return do_bandstop(BL, f0, f1);
        }
    };

    private Filter des_lpchz = new Filter("LpChZ#O/#V/#F", "Lowpass Chebyshev filter, matched z-transform, order #O, " + "passband ripple #VdB, -3.01dB frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            chebyshev(order, arg[0]);
            return do_lowpass(MZ, f0);
        }
    };

    private Filter des_hpchz = new Filter("HpChZ#O/#V/#F", "Highpass Chebyshev filter, matched z-transform, order #O, " + "passband ripple #VdB, -3.01dB frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            chebyshev(order, arg[0]);
            return do_highpass(MZ, f0);
        }
    };

    private Filter des_bpchz = new Filter("BpChZ#O/#V/#R", "Bandpass Chebyshev filter, matched z-transform, order #O, " + "passband ripple #VdB, -3.01dB frequencies #R") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            chebyshev(order, arg[0]);
            return do_bandpass(MZ, f0, f1);
        }
    };

    private Filter des_bschz = new Filter("BsChZ#O/#V/#R", "Bandstop Chebyshev filter, matched z-transform, order #O, " + "passband ripple #VdB, -3.01dB frequencies #R") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            chebyshev(order, arg[0]);
            return do_bandstop(MZ, f0, f1);
        }
    };

    private Filter des_lpbq = new Filter("LpBq#o/#V/#F", "Lowpass biquad filter, order #O, Q=#V, -3.01dB frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            double omega = 2 * Math.PI * f0;
            double cosv = Math.cos(omega);
            double alpha = Math.sin(omega) / 2 / arg[0];
            return stack_filter(order, 3, 7, 'I', 0x0, 3, 1 + alpha, -2 * cosv, 1 - alpha, 'F', 0x7, 3, 1.0, 2.0, 1.0, 'F', 0x0, 1, (1 - cosv) * 0.5);
        }
    };

    private Filter des_hpbq = new Filter("HpBq#o/#V/#F", "Highpass biquad filter, order #O, Q=#V, -3.01dB frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            double omega = 2 * Math.PI * f0;
            double cosv = Math.cos(omega);
            double alpha = Math.sin(omega) / 2 / arg[0];
            return stack_filter(order, 3, 7, 'I', 0x0, 3, 1 + alpha, -2 * cosv, 1 - alpha, 'F', 0x7, 3, 1.0, -2.0, 1.0, 'F', 0x0, 1, (1 + cosv) * 0.5);
        }
    };

    private Filter des_bpbq = new Filter("BpBq#o/#V/#F", "Bandpass biquad filter, order #O, Q=#V, centre frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            double omega = 2 * Math.PI * f0;
            double cosv = Math.cos(omega);
            double alpha = Math.sin(omega) / 2 / arg[0];
            return stack_filter(order, 3, 7, 'I', 0x0, 3, 1 + alpha, -2 * cosv, 1 - alpha, 'F', 0x7, 3, 1.0, 0.0, -1.0, 'F', 0x0, 1, alpha);
        }
    };

    private Filter des_bsbq = new Filter("BsBq#o/#V/#F", "Bandstop biquad filter, order #O, Q=#V, centre frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            double omega = 2 * Math.PI * f0;
            double cosv = Math.cos(omega);
            double alpha = Math.sin(omega) / 2 / arg[0];
            return stack_filter(order, 2, 6, 'I', 0x0, 3, 1 + alpha, -2 * cosv, 1 - alpha, 'F', 0x5, 3, 1.0, -2 * cosv, 1.0);
        }
    };

    private Filter des_apbq = new Filter("ApBq#o/#V/#F", "Allpass biquad filter, order #O, Q=#V, centre frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            double omega = 2 * Math.PI * f0;
            double cosv = Math.cos(omega);
            double alpha = Math.sin(omega) / 2 / arg[0];
            return stack_filter(order, 2, 6, 'I', 0x0, 3, 1 + alpha, -2 * cosv, 1 - alpha, 'F', 0x0, 3, 1 - alpha, -2 * cosv, 1 + alpha);
        }
    };

    private Filter des_pkbq = new Filter("PkBq#o/#V/#V/#F", "Peaking biquad filter, order #O, Q=#V, dBgain=#V, frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            double omega = 2 * Math.PI * f0;
            double cosv = Math.cos(omega);
            double alpha = Math.sin(omega) / 2 / arg[0];
            double A = Math.pow(10, arg[1] / 40);
            return stack_filter(order, 2, 6, 'I', 0x0, 3, 1 + alpha / A, -2 * cosv, 1 - alpha / A, 'F', 0x0, 3, 1 + alpha * A, -2 * cosv, 1 - alpha * A);
        }
    };

    private Filter des_lsbq = new Filter("LsBq#o/#V/#V/#F", "Lowpass shelving biquad filter, S=#V, dBgain=#V, frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            double omega = 2 * Math.PI * f0;
            double cosv = Math.cos(omega);
            double sinv = Math.sin(omega);
            double A = Math.pow(10, arg[1] / 40);
            double beta = Math.sqrt((A * A + 1) / arg[0] - (A - 1) * (A - 1));
            return stack_filter(order, 2, 6, 'I', 0x0, 3,
                                (A + 1) + (A - 1) * cosv + beta * sinv,
                                -2 * ((A - 1) + (A + 1) * cosv),
                                (A + 1) + (A - 1) * cosv - beta * sinv,
                                'F', 0x0, 3,
                                A * ((A + 1) - (A - 1) * cosv + beta * sinv),
                                2 * A * ((A - 1) - (A + 1) * cosv),
                                A * ((A + 1) - (A - 1) * cosv - beta * sinv));
        }
    };

    private Filter des_hsbq = new Filter("HsBq#o/#V/#V/#F", "Highpass shelving biquad filter, S=#V, dBgain=#V, frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            double omega = 2 * Math.PI * f0;
            double cosv = Math.cos(omega);
            double sinv = Math.sin(omega);
            double A = Math.pow(10, arg[1] / 40);
            double beta = Math.sqrt((A * A + 1) / arg[0] - (A - 1) * (A - 1));
            return stack_filter(order, 2, 6,
                                'I', 0x0, 3,
                                (A + 1) - (A - 1) * cosv + beta * sinv,
                                2 * ((A - 1) - (A + 1) * cosv),
                                (A + 1) - (A - 1) * cosv - beta * sinv,
                                'F', 0x0, 3,
                                A * ((A + 1) + (A - 1) * cosv + beta * sinv),
                                -2 * A * ((A - 1) + (A + 1) * cosv),
                                A * ((A + 1) + (A - 1) * cosv - beta * sinv));
        }
    };

    private Filter des_lpbl = new Filter("LpBl/#F", "Lowpass Blackman window, -3.01dB frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            double wid = 0.4109205 / f0;
            double tot, adj;
            int max = (int) Math.floor(wid);
            int a;
            FidFilter ff = Factory.newInstance();
            ff.type = 'F';
            ff.cbm = 0;
            ff.len = max * 2 + 1;
            ff.val[max] = tot = 1.0;
            for (a = 1; a <= max; a++) {
                double val = 0.42 + 0.5 * Math.cos(Math.PI * a / wid) + 0.08 * Math.cos(Math.PI * 2.0 * a / wid);
                ff.val[max - a] = val;
                ff.val[max + a] = val;
                tot += val * 2.0;
            }
            adj = 1 / tot;
            for (a = 0; a <= max * 2; a++) {
                ff.val[a] *= adj;
            }
            List<FidFilter> rv = new ArrayList<>();
            rv.add(ff);
            return rv;
        }
    };

    private Filter des_lphm = new Filter("LpHm/#F", "Lowpass Hamming window, -3.01dB frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            double wid = 0.3262096 / f0;
            double tot, adj;
            int max = (int) Math.floor(wid);
            int a;
            FidFilter ff = Factory.newInstance();
            ff.type = 'F';
            ff.cbm = 0;
            ff.len = max * 2 + 1;
            ff.val[max] = tot = 1.0;
            for (a = 1; a <= max; a++) {
                double val = 0.54 + 0.46 * Math.cos(Math.PI * a / wid);
                ff.val[max - a] = val;
                ff.val[max + a] = val;
                tot += val * 2.0;
            }
            adj = 1 / tot;
            for (a = 0; a <= max * 2; a++) {
                ff.val[a] *= adj;
            }
            List<FidFilter> rv = new ArrayList<>();
            rv.add(ff);
            return rv;
        }
    };

    private Filter des_lphn = new Filter("LpHn/#F", "Lowpass Hann window, -3.01dB frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            double wid = 0.360144 / f0;
            double tot, adj;
            int max = (int) Math.floor(wid);
            int a;
            FidFilter ff = Factory.newInstance();
            ff.type = 'F';
            ff.cbm = 0;
            ff.len = max * 2 + 1;
            ff.val[max] = tot = 1.0;
            for (a = 1; a <= max; a++) {
                double val = 0.5 + 0.5 * Math.cos(Math.PI * a / wid);
                ff.val[max - a] = val;
                ff.val[max + a] = val;
                tot += val * 2.0;
            }
            adj = 1 / tot;
            for (a = 0; a <= max * 2; a++) {
                ff.val[a] *= adj;
            }
            List<FidFilter> rv = new ArrayList<>();
            rv.add(ff);
            return rv;
        }
    };

    private Filter des_lpba = new Filter("LpBa/#F", "Lowpass Bartlet (triangular) window, -3.01dB frequency #F") {
        List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg) {
            double wid = 0.3189435 / f0;
            double tot, adj;
            int max = (int) Math.floor(wid);
            int a;
            FidFilter ff = Factory.newInstance();
            ff.type = 'F';
            ff.cbm = 0;
            ff.len = max * 2 + 1;
            ff.val[max] = tot = 1.0;
            for (a = 1; a <= max; a++) {
                double val = 1.0 - a / wid;
                ff.val[max - a] = val;
                ff.val[max + a] = val;
                tot += val * 2.0;
            }
            adj = 1 / tot;
            for (a = 0; a <= max * 2; a++) {
                ff.val[a] *= adj;
            }
            List<FidFilter> rv = new ArrayList<>();
            rv.add(ff);
            return rv;
        }
    };

    /**
     * Information passed to individual filter design routines:
     *
     * Note that #O #o #F and #R are mapped to the f0/f1/order arguments, and are
     * not included in the arg[] array.
     *
     * See the previous description for the required meaning of the return value
     * FidFilter list.
     */
    private abstract static class Filter {
        public Filter(String format, String text) {
            this.format = format;
            this.text = text;
        }
        /**
         * Designer routine address
         *
         * @param rate is the sampling rate, or 1 if not set
         * @param f0 give the frequency
         * @param f1 give the frequency or frequency range as a proportion of
         *            the sampling rate
         * @param order is the order of the filter (the integer passed
         *            immediately after the name)
         * @param n_arg is the number of additional arguments for the filter
         * @param arg gives the additional argument values: arg[n]
         */
        abstract List<FidFilter> rout(double rate, double f0, double f1, int order, int n_arg, double[] arg);
        /** format for spec-string */
        String format;
        /** human-readable description of filter */
        String text;
        /* */
        public String toString() {
            return text + " [" + format + "]";
        }
    }

    /**
     * Filter table
     */
    private Filter[] filter = {
        des_bpre,
        des_bsre,
        des_apre,
        des_pi,
        des_piz,
        des_lpbe,
        des_hpbe,
        des_bpbe,
        des_bsbe,
        des_lpbu,
        des_hpbu,
        des_bpbu,
        des_bsbu,
        des_lpch,
        des_hpch,
        des_bpch,
        des_bsch,
        des_lpbez,
        des_hpbez,
        des_bpbez,
        des_bsbez,
        des_lpbuz,
        des_hpbuz,
        des_bpbuz,
        des_bsbuz,
        des_lpchz,
        des_hpchz,
        des_bpchz,
        des_bschz,
        des_lpbube,
        des_lpbq,
        des_hpbq,
        des_bpbq,
        des_bsbq,
        des_apbq,
        des_pkbq,
        des_lsbq,
        des_hsbq,
        des_lpbl,
        des_lphm,
        des_lphn,
        des_lpba,
    };

    /**
     * Design a filter. Spec and range are passed as arguments. The return value
     * is a pointer to a FidFilter as documented earlier in this file. This
     * needs to be free()d once finished with.
     *
     * If 'f_adj' is set, then the frequencies fed to the design code are
     * adjusted automatically to get true sqrt(0.5) (-3.01dB) values at the
     * provided frequencies. (This is obviously a slower operation)
     *
     * If 'descp' is non-0, then a long description of the filter is generated
     * and returned as a strdup'd string at the given location.
     *
     * Any problem with the spec causes the program to die with an error
     * message.
     *
     * 'spec' gives the specification string. The 'rate' argument gives the
     * sampling rate for the data that will be passed to the filter. This is
     * only used to interpret the frequencies given in the spec or given in
     * 'freq0' and 'freq1'. Use 1.0 if the frequencies are given as a proportion
     * of the sampling rate, in the range 0 to 0.5. 'freq0' and 'freq1' provide
     * the default frequency or frequency range if this is not included in the
     * specification string. These should be -ve if there is no default range
     * (causing an error if they are omitted from the 'spec').
     */
    private static class Spec {
        static final int MAXARG = 10;
        String spec;
        double in_f0, in_f1;
        int in_adj;
        double[] argarr = new double[MAXARG];
        double f0, f1;
        int adj;
        int n_arg;
        int order;
        /** Minimum length of spec-string, assuming f0/f1 passed separately */
        int minlen;
        /** Number of frequencies provided: 0,1,2 */
        int n_freq;
        /** Filter index (filter[fi]) */
        int fi;
    }

    /** */
    protected List<FidFilter> fid_design(String spec, double rate, double freq0, double freq1, int f_adj, String[] descp) {
        List<FidFilter> rv;
        Spec sp = new Spec();
        double f0, f1;

        // Parse the filter-spec
        sp.spec = spec;
        sp.in_f0 = freq0;
        sp.in_f1 = freq1;
        sp.in_adj = f_adj;
        parse_spec(sp);
        f0 = sp.f0;
        f1 = sp.f1;

        // Adjust frequencies to range 0-0.5, and check them
        f0 /= rate;
        if (f0 > 0.5) {
            throw new IllegalArgumentException(String.format("Frequency of %gHz out of range with sampling rate of %gHz", f0 * rate, rate));
        }
        f1 /= rate;
        if (f1 > 0.5) {
            throw new IllegalArgumentException(String.format("Frequency of %gHz out of range with sampling rate of %gHz", f1 * rate, rate));
        }

        // Okay we now have a successful spec-match to filter[sp.fi], and
        // sp.n_arg
        // args are now in sp.argarr[]

        // Generate the filter
        if (sp.adj == 0) {
            rv = filter[sp.fi].rout(rate, f0, f1, sp.order, sp.n_arg, sp.argarr);
        } else if (filter[sp.fi].format.contains("#R")) {
            rv = auto_adjust_dual(sp, rate, f0, f1);
        } else {
            rv = auto_adjust_single(sp, rate, f0);
        }

        // Generate a long description if required
        if (descp != null) {
            String fmt = filter[sp.fi].text;
            int fmtP = 0;
            int max = fmt.length() + 60 + sp.n_arg * 20;
            byte[] desc = new byte[max];
            int p = 0; // desc
            char ch;
            double[] arg = sp.argarr;
            int argP = 0;
            int n_arg = sp.n_arg;

            while ((ch = fmt.charAt(fmtP++)) != 0) {
                if (ch != '#') {
                    desc[p++] = (byte) ch;
                    continue;
                }

                switch (fmt.charAt(fmtP++)) {
                case 'O':
                    String ttt = String.format("%d", sp.order);
                    System.arraycopy(ttt.getBytes(), 0, desc, p, ttt.length());
                    p += ttt.length();
                    break;
                case 'F':
                    ttt = String.format("%g", f0 * rate);
                    System.arraycopy(ttt.getBytes(), 0, desc, p, ttt.length());
                    p += ttt.length();
                    break;
                case 'R':
                    ttt = String.format("%g-%g", f0 * rate, f1 * rate);
                    System.arraycopy(ttt.getBytes(), 0, desc, p, ttt.length());
                    p += ttt.length();
                    break;
                case 'V':
                    if (n_arg <= 0) {
                        throw new IllegalStateException("disagreement between filter short-spec and long-description over number of arguments");
                    }
                    n_arg--;
                    ttt = String.format("%g", arg[argP++]);
                    System.arraycopy(ttt.getBytes(), 0, desc, p, ttt.length());
                    p += ttt.length();
                    break;
                default:
                    throw new IllegalArgumentException("unknown format in long description: #" + fmt.charAt(fmtP - 1));
                }
            }
            desc[p++] = 0;
            if (p >= max) {
                throw new IllegalStateException("exceeded estimated description buffer");
            }
            descp[0] = new String(desc);
        }

        return rv;
    }

    //

    private static final double M301DB = 0.707106781186548;

    private class X {
        double resp;
        Spec sp;
        Filter design;
        List<FidFilter> rv = null;
        double rate;
        double f0;
        X(Spec sp, double rate, double f0) {
            design = filter[sp.fi];
            this.rate = rate;
            this.f0 = f0;
        }
        List<FidFilter> DESIGN(double aa, double rate) {
            return design.rout(rate, aa, aa, sp.order, sp.n_arg, sp.argarr);
        }
        void TEST(double aa) {
            if (rv != null) {
                rv = null;
            }
            rv = DESIGN(aa, rate);
            resp = fid_response(rv, f0);
        }
    }

    /**
     * Auto-adjust input frequency to give correct sqrt(0.5) (~-3.01dB) point to 6
     * figures
     */
    private List<FidFilter> auto_adjust_single(Spec sp, double rate, double f0) {
        double a0, a1, a2;
        double r0, r2;
        boolean incr; // Increasing (1) or decreasing (0)
        int a;
        X x = new X(sp, rate, f0);

        // Try and establish a range within which we can find the point
        a0 = f0;
        x.TEST(a0);
        r0 = x.resp;
        for (a = 2; true; a *= 2) {
            a2 = f0 / a;
            x.TEST(a2);
            r2 = x.resp;
            if ((r0 < M301DB) != (r2 < M301DB)) {
                break;
            }
            a2 = 0.5 - ((0.5 - f0) / a);
            x.TEST(a2);
            r2 = x.resp;
            if ((r0 < M301DB) != (r2 < M301DB)) {
                break;
            }
            if (a == 32) { // No success
                throw new IllegalArgumentException("auto_adjust_single internal error -- can't establish enclosing range");
            }
        }

        incr = r2 > r0;
        if (a0 > a2) {
            a1 = a0;
            a0 = a2;
            a2 = a1;
            incr = !incr;
        }

        // binary search
        while (true) {
            a1 = 0.5 * (a0 + a2);
            if (a1 == a0 || a1 == a2) {
                break; // Limit of double, sanity check
            }
            x.TEST(a1);
            if (x.resp >= 0.9999995 * M301DB && x.resp < 1.0000005 * M301DB) {
                break;
            }
            if (incr == (x.resp > M301DB)) {
                a2 = a1;
            } else {
                a0 = a1;
            }
        }

        return x.rv;
    }

    //

    private class Y {
        List<FidFilter> rv = null;
        Spec sp;
        double rate;
        double f0;
        double f1;
        double r0, r1, err0, err1;
        boolean bpass = true;
        int cnt_design = 0;
        Filter design;
        Y(Spec sp, double rate, double f0, double f1) {
            this.sp = sp;
            this.rate = rate;
            this.f0 = f0;
            this.f1 = f1;
            design = filter[sp.fi];
        }
        void DESIGN(double mm, double ww) {
            if (rv != null) {
                // free(rv);
                rv = null;
            }
            rv = design.rout(rate, mm - ww, mm + ww, sp.order, sp.n_arg, sp.argarr);
            r0 = fid_response(rv, f0);
            r1 = fid_response(rv, f1);
            err0 = Math.abs(M301DB - r0);
            err1 = Math.abs(M301DB - r1);
            cnt_design++;
        }
        boolean INC_WID() {
            return ((r0 + r1 < 1.0) == bpass);
        }
        boolean INC_MID() {
            return ((r0 > r1) == bpass);
        }
        boolean MATCH() {
            return (err0 < 0.000000499 && err1 < 0.000000499);
        }
        double PERR() {
            return (err0 + err1);
        }
    }

    /**
     * Auto-adjust input frequencies to give response of sqrt(0.5) (~-3.01dB)
     * correct to 6sf at the given frequency-points
     */
    private List<FidFilter> auto_adjust_dual(Spec sp, double rate, double f0, double f1) {
        double mid = 0.5 * (f0 + f1);
        double wid = 0.5 * Math.abs(f1 - f0);
        double delta;
        double mid0, mid1;
        double wid0, wid1;
        double perr;
        int cnt;

        Y y = new Y(sp, rate, f0, f1);
        y.DESIGN(mid, wid);
        y.bpass = (fid_response(y.rv, 0) < 0.5);
        delta = wid * 0.5;

        // Try delta changes until we get there
        for (cnt = 0; true; cnt++, delta *= 0.51) {
            y.DESIGN(mid, wid); // I know -- this is redundant
            perr = y.PERR();

            mid0 = mid;
            wid0 = wid;
            mid1 = mid + (y.INC_MID() ? delta : -delta);
            wid1 = wid + (y.INC_WID() ? delta : -delta);

            if (mid0 - wid1 > 0.0 && mid0 + wid1 < 0.5) {
                y.DESIGN(mid0, wid1);
                if (y.MATCH()) {
                    break;
                }
                if (y.PERR() < perr) {
                    perr = y.PERR();
                    mid = mid0;
                    wid = wid1;
                }
            }

            if (mid1 - wid0 > 0.0 && mid1 + wid0 < 0.5) {
                y.DESIGN(mid1, wid0);
                if (y.MATCH()) {
                    break;
                }
                if (y.PERR() < perr) {
                    perr = y.PERR();
                    mid = mid1;
                    wid = wid0;
                }
            }

            if (mid1 - wid1 > 0.0 && mid1 + wid1 < 0.5) {
                y.DESIGN(mid1, wid1);
                if (y.MATCH()) {
                    break;
                }
                if (y.PERR() < perr) {
                    perr = y.PERR();
                    mid = mid1;
                    wid = wid1;
                }
            }

            if (cnt > 1000) {
                throw new IllegalArgumentException("auto_adjust_dual -- design not converging");
            }
        }

        return y.rv;
    }

    /**
     * Expand a specification string to the given buffer; if out of space, drops
     * dead
     */
    private static void expand_spec(byte[] buf, int bufend, byte[] str) {
        int ch;
        int p = 0; // buf
        int strP = 0;
        while (strP < str.length) {
            ch = str[strP++];
            if (p + 10 >= bufend) {
                throw new IllegalArgumentException("Buffer overflow in fidlib expand_spec()");
            }
            if (ch == '#') {
                switch (str[strP++]) {
                case 'o':
                    String ttt = "<optional-order>";
                    System.arraycopy(ttt.getBytes(), 0, buf, p, ttt.length());
                    p += ttt.length();
                    break;
                case 'O':
                    ttt = "<order>";
                    System.arraycopy(ttt.getBytes(), 0, buf, p, ttt.length());
                    p += ttt.length();
                    break;
                case 'F':
                    ttt = "<freq>";
                    System.arraycopy(ttt.getBytes(), 0, buf, p, ttt.length());
                    p += ttt.length();
                    break;
                case 'R':
                    ttt = "<range>";
                    System.arraycopy(ttt.getBytes(), 0, buf, p, ttt.length());
                    p += ttt.length();
                    break;
                case 'V':
                    ttt = "<value>";
                    System.arraycopy(ttt.getBytes(), 0, buf, p, ttt.length());
                    p += ttt.length();
                    break;
                default:
                    ttt = String.format("<%c>", str[strP-1]);
                    System.arraycopy(ttt.getBytes(), 0, buf, p, ttt.length());
                    p += ttt.length();
                    break;
                }
            } else {
                buf[p++] = (byte) ch;
            }
        }
        buf[p] = 0;
    }

    /**
     * Design a filter and reduce it to a list of all the non-const
     * coefficients. Arguments are as for fid_filter(). The coefficients are
     * written into the given double array. If the number of coefficients
     * doesn't match the array length given, then a fatal error is generated.
     *
     * Note that all 1-element FIRs and IIR first-coefficients are merged into a
     * single gain coefficient, which is returned rather than being included in
     * the coefficient list. This is to allow it to be merged with other gains
     * within a stack of filters.
     *
     * The algorithm used here (merging 1-element FIRs and adjusting IIR
     * first-coefficients) must match that used in the code- generating code, or
     * else the coefficients won't match up. The 'n_coef' argument provides a
     * partial safeguard.
     */
    protected double fid_design_coef(double[] coef, int n_coef, String spec, double rate, double freq0, double freq1, int adj) {
        List<FidFilter> filt = fid_design(spec, rate, freq0, freq1, adj, null);
        FidFilter ff;
        int len;
        int cnt = 0;
        double gain = 1.0;
        double[] iir, fir;
        double iir_adj = 0;
        double[] const_one = { 1 };
        int n_iir, n_fir;
        int iir_cbm, fir_cbm;
        int coefP = 0;
        Iterator<FidFilter> i = filt.iterator();
        while (i.hasNext()) {
            ff = i.next();
            if (ff.type == 'F' && ff.len == 1) {
                gain *= ff.val[0];
                ff = i.next();
                continue;
            }

            if (ff.type != 'I' && ff.type != 'F') {
                throw new IllegalArgumentException("fid_design_coef can't handle FidFilter type: " + ff.type);
            }

            // initialise to safe defaults
            iir = fir = const_one;
            n_iir = n_fir = 1;
            iir_cbm = fir_cbm = ~0;

            // see if we have an IIR filter
            if (ff.type == 'I') {
                iir = ff.val;
                n_iir = ff.len;
                iir_cbm = ff.cbm;
                iir_adj = 1.0 / ff.val[0];
                ff = i.next();
                gain *= iir_adj;
            }

            // see if we have an FIR filter
            if (ff.type == 'F') {
                fir = ff.val;
                n_fir = ff.len;
                fir_cbm = ff.cbm;
                ff = i.next();
            }

            // dump out all non-const coefficients in reverse order
            len = Math.max(n_fir, n_iir);
            for (int a = len - 1; a >= 0; a--) {
                // output IIR if present and non-const
                if (a < n_iir && a > 0 && (iir_cbm & (1 << (Math.min(a, 15)))) == 0) {
                    if (cnt++ < n_coef) {
                        coef[coefP++] = iir_adj * iir[a];
                    }
                }

                // output FIR if present and non-const
                if (a < n_fir && (fir_cbm & (1 << (Math.min(a, 15)))) == 0) {
                    if (cnt++ < n_coef) {
                        coef[coefP++] = fir[a];
                    }
                }
            }
        }

        if (cnt != n_coef) {
            throw new IllegalArgumentException(String.format("fid_design_coef called with the wrong number of coefficients. Given %d, expecting %d: (\"%s\",%g,%g,%g,%d)", n_coef, cnt, spec, rate, freq0, freq1, adj));
        }

        return gain;
    }

    /**
     * List all the known filters to the given file handle
     */
    public void fid_list_filters(OutputStream out) throws IOException {
        for (Filter value : filter) {
            byte[] buf = new byte[4096];
            expand_spec(buf, buf.length, value.format.getBytes());
            out.write(String.format("%s\n    ", new String(buf)).getBytes());
            expand_spec(buf, buf.length, value.text.getBytes());
            out.write(String.format("%s\n", new String(buf)).getBytes());
        }
    }

    /**
     * List all the known filters to the given buffer; the buffer is
     * NUL-terminated;
     * @return 1 okay, 0 not enough space
     */
    public int fid_list_filters_buf(String buf, int bufend) {
        byte[] tmp = new byte[bufend];

        StringBuilder bufBuilder = new StringBuilder(buf);
        for (Filter value : filter) {
            expand_spec(tmp, tmp.length, value.format.getBytes());
            bufBuilder.append(String.format("%s\n    ", new String(tmp)));
            if (bufBuilder.length() >= bufend) {
                return 0;
            }
            expand_spec(tmp, tmp.length, value.text.getBytes());
            bufBuilder.append(String.format("%s\n", new String(tmp)));
            if (bufBuilder.length() >= bufend) {
                return 0;
            }
        }
        buf = bufBuilder.toString();
        return 1;
    }

    /**
     * Do a convolution of parameters in place
     */
    private static int convolve(double[] dst, int n_dst, double[] src, int n_src) {
        int len = n_dst + n_src - 1;

        for (int a = len - 1; a >= 0; a--) {
            double val = 0;
            for (int b = 0; b < n_src; b++) {
                if (a - b >= 0 && a - b < n_dst) {
                    val += src[b] * dst[a - b];
                }
            }
            dst[a] = val;
        }

        return len;
    }

    /**
     * Generate a combined filter -- merge all the IIR/FIR sub-filters into a
     * single IIR/FIR pair, and make sure the IIR first coefficient is 1.0.
     */
    protected List<FidFilter> fid_flatten(List<FidFilter> filt) {
        int m_fir = 1; // Maximum values
        int m_iir = 1;
        int n_fir, n_iir; // Stored counts during convolution
        List<FidFilter> rv;
        double[] fir, iir;
        double adj;
        int a;

        // Find the size of the output filter
        for (FidFilter ff : filt) {
            if (ff.type == 'I') {
                m_iir += ff.len - 1;
            } else if (ff.type == 'F') {
                m_fir += ff.len - 1;
            } else {
                throw new IllegalArgumentException("fid_flatten doesn't know about type " + ff.type);
            }
        }

        // Setup the output array
        rv = new ArrayList<>(2);
        FidFilter ff = Factory.newInstance();
        ff.type = 'I';
        ff.len = m_iir;
        iir = ff.val; // TODO ???
        rv.add(ff);
        ff = Factory.newInstance();
        ff.type = 'F';
        ff.len = m_fir;
        fir = ff.val; // TODO ???

        iir[0] = 1.0;
        n_iir = 1;
        fir[0] = 1.0;
        n_fir = 1;

        // do the convolution
        for (FidFilter ff2 : filt) {
            if (ff2.type == 'I') {
                n_iir = convolve(iir, n_iir, ff2.val, ff2.len);
            } else {
                n_fir = convolve(fir, n_fir, ff2.val, ff2.len);
            }
        }

        // sanity check
        if (n_iir != m_iir || n_fir != m_fir) {
            throw new IllegalArgumentException("Internal error in fid_combine() -- array under/overflow");
        }

        // fix iir[0]
        adj = 1.0 / iir[0];
        for (a = 0; a < n_iir; a++) {
            iir[a] *= adj;
        }
        for (a = 0; a < n_fir; a++) {
            fir[a] *= adj;
        }

        return rv;
    }

    /**
     * Parse a filter-spec and freq0/freq1 arguments. Returns a error
     * string on error, or else 0.
     */
    private void parse_spec(Spec sp) {
        double[] arg;
        int a;

        arg = sp.argarr;
        sp.n_arg = 0;
        sp.order = 0;
        sp.f0 = 0;
        sp.f1 = 0;
        sp.adj = 0;
        sp.minlen = -1;
        sp.n_freq = 0;

        int fmtP = 0;
        int pP = 0;
        int argP = 0;
        for (a = 0; true; a++) {
            String fmt = filter[a].format;
            String p = sp.spec;
            char ch;
            /* char * */int q = 0;

            if (fmt == null) {
                throw new IllegalArgumentException(String.format("Spec-string \"%s\" matches no known format", sp.spec));
            }

            while (pP < p.length() && fmtP < fmt.length()) {
                ch = fmt.charAt(fmtP++);
                if (ch != '#') {
                    if (ch == p.charAt(pP++)) {
                        continue;
                    }
                    p = null;
                    break;
                }

                if (Character.isLetter(p.charAt(pP))) {
                    p = null;
                    break;
                }

                // Handling a format character
                ch = fmt.charAt(fmtP++);
                switch (ch) {
                default:
                    throw new IllegalArgumentException(String.format("Internal error: Unknown format #%c in format: %s", fmt.charAt(fmtP - 1), filter[a].format));
                case 'o':
                case 'O':
                    sp.order = Integer.parseInt(p); // TODO , q ???
                    if (pP == q) {
                        if (ch == 'O') {
                            throw new IllegalArgumentException(String.format("Bad match of spec-string \"%s\" to format \"%s\"", sp.spec, filter[a].format));
                        }
                        sp.order = 1;
                    }
                    if (sp.order <= 0) {
                        throw new IllegalArgumentException(String.format("Bad order %d in spec-string \"%s\"", sp.order, sp.spec));
                    }
                    pP = q;
                    break;
                case 'V':
                    sp.n_arg++;
                    arg[argP++] = Double.parseDouble(p); // TODO , q ???
                    if (pP == q) {
                        throw new IllegalArgumentException(String.format("Bad match of spec-string \"%s\" to format \"%s\"", sp.spec, filter[a].format));
                    }
                    pP = q;
                    break;
                case 'F':
                    sp.minlen = pP - 1;
                    sp.n_freq = 1;
                    sp.adj = (p.charAt(pP) == '=') ? -1 : 0;
                    if (sp.adj != 0) {
                        pP++;
                    }
                    sp.f0 = Double.parseDouble(p); // TODO q
                    sp.f1 = 0;
                    if (pP == q) {
                        throw new IllegalArgumentException(String.format("Bad match of spec-string \"%s\" to format \"%s\"", sp.spec, filter[a].format));
                    }
                    pP = q;
                    break;
                case 'R':
                    sp.minlen = pP - 1;
                    sp.n_freq = 2;
                    sp.adj = (p.charAt(pP) == '=') ? -1 : 0;
                    if (sp.adj != 0) {
                        pP++;
                    }
                    sp.f0 = Double.parseDouble(p); // TODO q
                    if (pP == q) {
                        throw new IllegalArgumentException(String.format("Bad match of spec-string \"%s\" to format \"%s\"", sp.spec, filter[a].format));
                    }
                    pP = q;
                    if (p.charAt(pP++) != '-') {
                        throw new IllegalArgumentException(String.format("Bad match of spec-string \"%s\" to format \"%s\"", sp.spec, filter[a].format));
                    }
                    sp.f1 = Double.parseDouble(p); // TODO q
                    if (pP == q) {
                        throw new IllegalArgumentException(String.format("Bad match of spec-string \"%s\" to format \"%s\"", sp.spec, filter[a].format));
                    }
                    if (sp.f0 > sp.f1) {
                        throw new IllegalArgumentException(String.format("Backwards frequency range in spec-string \"%s\"", sp.spec));
                    }
                    pP = q;
                    break;
                }
            }

            if (pP == 0) {
                continue;
            }

            if (fmt.charAt(fmtP) == '/' && fmt.charAt(fmtP + 1) == '#' && fmt.charAt(fmtP + 2) == 'F') {
                sp.minlen = pP;
                sp.n_freq = 1;
                if (sp.in_f0 < 0.0) {
                    throw new IllegalArgumentException("Frequency omitted from filter-spec, and no default provided");
                }
                sp.f0 = sp.in_f0;
                sp.f1 = 0;
                sp.adj = sp.in_adj;
                fmt += 3;
            } else if (fmt.charAt(fmtP) == '/' && fmt.charAt(fmtP + 1) == '#' && fmt.charAt(fmtP + 2) == 'R') {
                sp.minlen = pP;
                sp.n_freq = 2;
                if (sp.in_f0 < 0.0 || sp.in_f1 < 0.0) {
                    throw new IllegalArgumentException("Frequency omitted from filter-spec, and no default provided");
                }
                sp.f0 = sp.in_f0;
                sp.f1 = sp.in_f1;
                sp.adj = sp.in_adj;
                fmt += 3;
            }

            // check for trailing unmatched format characters
            if (fmt.charAt(fmtP) != 0) {
                throw new IllegalArgumentException(String.format("Bad match of spec-string \"%s\" to format \"%s\"", sp.spec, filter[a].format));
            }
//          if (sp.n_arg > MAXARG) {
//              throw new IllegalArgumentException("Internal error -- maximum arguments exceeded");
//          }

            // set the minlen to the whole string if unset
            if (sp.minlen < 0) {
                sp.minlen = pP;
            }

            // save values, return
            sp.fi = a;
        }
    }

    /**
     * Parse a filter-spec and freq0/freq1 arguments and rewrite them to give an
     * all-in-one filter spec and/or a minimum spec plus separate freq0/freq1
     * arguments. The all-in-one spec is returned in *spec1p (strdup'd), and the
     * minimum separated-out spec is returned in *spec2p (strdup'd), *freq0p and
     * *freq1p. If either of spec1p or spec2p is 0, then that particular spec-string
     * is not generated.
     */
    protected void fid_rewrite_spec(String spec, double freq0, double freq1, int adj, String[] spec1p, String[] spec2p, double[] freq0p, double[] freq1p, int[] adjp) {
        Spec sp = new Spec();
        sp.spec = spec;
        sp.in_f0 = freq0;
        sp.in_f1 = freq1;
        sp.in_adj = adj;
        parse_spec(sp);

        if (spec1p != null) {
            String buf;
            int len;
            byte[] rv;
            buf = switch (sp.n_freq) {
                case 1 -> String.format("/%s%.15f", sp.adj != 0 ? "=" : "", sp.f0);
                case 2 -> String.format("/%s%.15g-%.15f", sp.adj != 0 ? "=" : "", sp.f0, sp.f1);
                default -> "";
            };
            len = buf.length();
            rv = new byte[sp.minlen + len + 1];
            System.arraycopy(spec, 0, rv, 0, sp.minlen);
            System.arraycopy(buf, 0, rv, sp.minlen, len);
            spec1p[0] = new String(rv);
        }

        if (spec2p != null) {
            byte[] rv = new byte[sp.minlen + 1];
            System.arraycopy(spec, 0, rv, 0, sp.minlen);
            spec2p[0] = new String(rv);
            freq0p[0] = sp.f0;
            freq1p[0] = sp.f1;
            adjp[0] = sp.adj;
        }
    }

    /**
     * Create a FidFilter from the given double array. The double[] should
     * contain one or more sections, each starting with the filter type (either
     * 'I' or 'F', as a double), then a count of the number of coefficients
     * following, then the coefficients themselves. The end of the list is
     * marked with a type of 0.
     *
     * This is really just a convenience function, allowing a filter to be
     * conveniently dumped to C source code and then reconstructed.
     *
     * Note that for more general filter generation, FidFilter instances can be
     * created simply by allocating the memory and filling them in (see
     * fidlib.h).
     */
    protected static List<FidFilter> fid_cv_array(double[] arr) {
        FidFilter ff;
        List<FidFilter> rv;
        int n_head = 0;
        int n_val = 0;

        // Scan through for sizes
        for (int dp = 0; arr[dp] != 0;) {
            int len, typ;

            typ = (int) (arr[dp++]);
            if (typ != 'F' && typ != 'I') {
                throw new IllegalArgumentException("Bad type in array passed to fid_cv_array: " + arr[dp - 1]);
            }

            len = (int) (arr[dp++]);
            if (len < 1) {
                throw new IllegalArgumentException("Bad length in array passed to fid_cv_array: " + arr[dp - 1]);
            }

            n_head++;
            n_val += len;
            dp += len;
        }

        rv = new ArrayList<>(n_head);

        // scan through to fill in FidFilter
        for (int dp = 0; arr[dp] != 0;) {
            int typ = (int) arr[dp++];
            int len = (int) arr[dp++];

            ff = Factory.newInstance();
            ff.type = typ;
            ff.cbm = ~0;
            ff.len = len;
            System.arraycopy(dp, 0, ff.val, 0, len);
            dp += len;
            rv.add(ff);
        }

        // final element already zero'd thanks to allocation

        return rv;
    }

    /**
     * Create a single filter from the given list of filters in order. If
     * 'freeme' is set, then all the listed filters are free'd once read;
     * otherwise they are left untouched. The newly allocated resultant filter
     * is returned, which should be released with free() when finished with.
     */
    @SafeVarargs
    protected static List<FidFilter> fid_cat(int freeme, List<FidFilter>... ap) {
        List<FidFilter> rv;
        FidFilter ff;
        int len = 0;
        int cnt = 0;
        List<FidFilter> dst;

        // find the memory required to store the combined filter
        int apP = 0;
        for (List<FidFilter> ff0 : ap) {
            for (FidFilter fidFilter : ff0) {
                ff = fidFilter;
                if (ff.type == 0) {
                    break;
                }
                len++;
            }
        }

        rv = new ArrayList<>(len);
        dst = rv;

        apP = 0;
        List<FidFilter> ff0;
        while ((ff0 = ap[apP++]) != null) {
            for (FidFilter fidFilter : ff0) {
                ff = fidFilter;
                dst.add(ff);
                cnt++;
            }
        }

        // final element already zero'd
        return rv;
    }

    //
    // Support for fid_parse
    //

    /** Skip white space (including comments) */
    private static void skipWS(String[] pp) {
        int p = 0;

        while (p < pp[0].length()) {
            if (Character.isSpaceChar(pp[0].charAt(p))) {
                p++;
                continue;
            }
            if (pp[0].charAt(p) == '#') {
                while (p < pp[0].length() && pp[0].charAt(p) != '\n') {
                    p++;
                }
                continue;
            }
            break;
        }
        pp[0] = pp[0].substring(p);
    }

    /**
     * Grab a word from the input into the given buffer. Returns 0: end of file
     * or error, else 1: success. Error is indicated when the word doesn't fit
     * in the buffer.
     */
    private static int grabWord(String[] pp, byte[] buf, int buflen) {
        int p = 0, q;
        int len;

        skipWS(pp);
        if (p == pp[0].length()) {
            return 0;
        }

        q = p;
        if (pp[0].charAt(q) == ',' || pp[0].charAt(q) == ';' || pp[0].charAt(q) == ')' || pp[0].charAt(q) == ']' || pp[0].charAt(q) == '}') {
            q++;
        } else {
            while (q < pp[0].length() && pp[0].charAt(q) != '#' && !Character.isSpaceChar(pp[0].charAt(q)) && (pp[0].charAt(q) != ',' && pp[0].charAt(q) != ';' && pp[0].charAt(q) != ')' && pp[0].charAt(q) != ']' && pp[0].charAt(q) != '}')) {
                q++;
            }
        }
        len = q - p;
        if (len >= buflen) {
            return 0;
        }

        System.arraycopy(pp[0].substring(p, len).getBytes(), 0, buf, 0, len);

        pp[0] = pp[0].substring(q);
        return 1;
    }

    /** */
    private static final int INIT_LEN = 128;

    /**
     * Parse an entire filter specification, perhaps consisting of several FIR, IIR
     * and predefined filters. Stops at the first ,; or unmatched )]}. Returns
     * either 0 on success, or else a strdup'd error string.
     *
     * This duplicates code from Fiview filter.c, I know, but this may have to
     * expand in the future to handle '+' operations, and special filter types like
     * tunable heterodyne filters. At that point, the filter.c code will have to be
     * modified to call a version of this routine.
     *
     * @throws IllegalArgumentException
     */
    public FidFilter[] fid_parse(double rate, String[] pp) {
        byte[] buf = new byte[INIT_LEN];
        int p = 0;
        int rew;

        FidFilter curr;
        List<FidFilter> result = new ArrayList<>();
//      int xtra = 0;
        int typ = -1; // First time through
        double val;
        byte dmy;

        while (true) {
            rew = p;
            if (grabWord(pp, buf, buf.length) == 0) {
                if (pp[0].charAt(p) != 0) {
                    throw new IllegalArgumentException("Filter element unexpectedly long -- syntax error?");
                }
                buf[0] = 0;
            }
            if (buf[0] == 0 || buf[1] == 0)
                switch (buf[0]) {
                default:
                    break;
                case 0:
                case ',':
                case ';':
                case ')':
                case ']':
                case '}':
                    // End of filter, return it
                    curr = FidFilter.Factory.newInstance();
                    curr.type = 0;
                    curr.cbm = 0;
                    curr.len = 0;
                    p = buf[0] != 0 ? (p - 1) : p;
                    result.add(curr);
                    return result.toArray(new FidFilter[0]);
                case '/':
                    if (typ > 0) {
                        throw new IllegalArgumentException("Filter syntax error; unexpected '/'");
                    }
                    typ = 'I';
                    continue;
                case 'x':
                    if (typ > 0) {
                        throw new IllegalArgumentException("Filter syntax error; unexpected 'x'");
                    }
                    typ = 'F';
                    continue;
                }

            if (typ < 0) {
                typ = 'F'; // Assume 'x' if missing
            }
            if (typ == 0) {
                throw new IllegalArgumentException("Expecting a 'x' or '/' before this");
            }

            Scanner scanner = new Scanner(new String(buf));
            if (scanner.hasNext("%lf %c")) {
                val = scanner.nextFloat();
                dmy = scanner.nextByte();
                // Must be a predefined filter
                List<FidFilter> ff;

                Spec sp;
                double f0, f1;
//              int len;

                if (typ != 'F') {
                    throw new IllegalArgumentException("Predefined filters cannot be used with '/'");
                }

                // Parse the filter-spec
                sp = new Spec();
                sp.spec = new String(buf);
                sp.in_f0 = sp.in_f1 = -1;
                parse_spec(sp);
                f0 = sp.f0;
                f1 = sp.f1;

                // Adjust frequencies to range 0-0.5, and check them
                f0 /= rate;
                if (f0 > 0.5) {
                    throw new IllegalArgumentException(String.format("Frequency of %gHz out of range with " + "sampling rate of %gHz", f0 * rate, rate));
                }
                f1 /= rate;
                if (f1 > 0.5) {
                    throw new IllegalArgumentException(String.format("Frequency of %gHz out of range with " + "sampling rate of %gHz", f1 * rate, rate));
                }

                // okay we now have a successful spec-match to filter[sp.fi],
                // and
                // sp.n_arg
                // args are now in sp.argarr[]

                // generate the filter
                if (sp.adj == 0) {
                    ff = filter[sp.fi].rout(rate, f0, f1, sp.order, sp.n_arg, sp.argarr);
                } else if (filter[sp.fi].format.contains("#R")) {
                    ff = auto_adjust_dual(sp, rate, f0, f1);
                } else {
                    ff = auto_adjust_single(sp, rate, f0);
                }

                // append it to our FidFilter to return
                result.addAll(ff);
                typ = 0;
                continue;
            }

            // must be a list of coefficients
            curr = result.get(0);
            curr.type = typ;
            curr.cbm = ~0;
            curr.len = 1;

            // see how many more coefficients we can pick up
            while (true) {
                rew = p;
                if (grabWord(pp, buf, buf.length) == 0) {
                    if (pp[0].charAt(p) != 0) {
                        throw new IllegalArgumentException("Filter element unexpectedly long -- syntax error?");
                    }
                    buf[0] = 0;
                }
                Scanner scanner2 = new Scanner(new String(buf));
                if (scanner.hasNext("%lf %c")) {
                    val = scanner2.nextFloat();
                    dmy = scanner2.nextByte();
                    p = rew;
                    break;
                }
                curr.len++;
            }
            typ = 0;
        }
    }
}

/* */
