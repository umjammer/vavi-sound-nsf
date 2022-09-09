/*
 * Combined-filter based filter-running code.
 *
 * Copyright (c) 2002-2003 Jim Peters <http://uazu.net/>.  This
 * file is released under the GNU Lesser General Public License
 * (LGPL) version 2.1 as published by the Free Software
 * Foundation.  See the file COPYING_LIB for details, or visit
 * <http://www.fsf.org/licenses/licenses.html>.
 *
 * Convolves all the filters into a single IIR/FIR pair, and runs
 * that directly through static code.  Compiled with GCC -O6 on
 * ix86 this is surprisingly fast -- at worst half the speed of
 * assember code, at best matching it.  The downside of
 * convolving all the sub-filters together like this is loss of
 * accuracy and instability in some kinds of filters, especially
 * high-order ones.  The one big advantage of this approach is
 * that the code is easy to understand.
 */

package vavi.sound.fidlib;

import java.util.Iterator;
import java.util.List;


/**
 * CombinedFidFilter.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 060901 nsano initial version <br>
 */
class CombinedFidFilter extends FidFilter {
    /** Magic: 0x64966325 */
    private int magic;
    /** FIR parameters */
    private double[]fir;
    /** Number of FIR parameters */
    private int n_fir;
    /** IIR parameters */
    private double []iir;
    /** Number of IIR parameters */
    private int n_iir;
    /** Number of entries in buffer */
    private int n_buf;
    /** Combined filter */
    private List<FidFilter> filt;
    /** */
    private double[] buf;

    /** */
    public double filter_step(double val) {
        int a;

        // Shift the whole internal array up one
        System.arraycopy(buf, 0, buf, 1, (n_buf - 1) * buf.length);

        // Do IIR
        for (a = 1; a < n_iir; a++) {
            val -= iir[a] * buf[a];
        }
        buf[0] = val;

        // Do FIR
        val = 0;
        for (a = 0; a < n_fir; a++) {
            val += fir[a] * buf[a];
        }

        return val;
    }

    /**
     * Create an instance of a filter, ready to run. This allows
     * many simultaneous instances of the filter to be run.
     */
    public CombinedFidFilter(List<FidFilter> filt) {
        FidFilter ff;

        this.magic = 0x64966325;
        this.filt = fid_flatten(filt);

        Iterator<FidFilter> i = filt.iterator();
        ff = i.next();
        if (ff.type != 'I') {
            throw new IllegalArgumentException("expecting IIR+FIR in flattened filter");
        }
        this.n_iir = ff.len;
        this.iir = ff.val;
        ff = i.next();
        if (ff.type != 'F') {
            throw new IllegalArgumentException("expecting IIR+FIR in flattened filter");
        }
        this.n_fir = ff.len;
        this.fir = ff.val;
        ff = i.next();
        if (ff.len != 0) {
            throw new IllegalArgumentException("expecting IIR+FIR in flattened filter");
        }

        this.n_buf = Math.max(this.n_fir, this.n_iir);

        // buffer
        if (this.magic != 0x64966325) {
            throw new IllegalArgumentException("Bad handle passed");
        }

        buf = new double[n_buf];
    }

    /**
     * Reinitialise an instance ready to start afresh
     */
    void fid_run_zapbuf() {
        for (int i = 0; i < n_buf; i++) {
            buf[i] = 0;
        }
    }
}

/* */
