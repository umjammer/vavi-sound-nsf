/*
 * JIT-compiled filter-running code.
 *
 * Copyright (c) 2002-2003 Jim Peters <http://uazu.net/>.  This
 * file is released under the GNU Lesser General Public License
 * (LGPL) version 2.1 as published by the Free Software
 * Foundation.  See the file COPYING_LIB for details, or visit
 * <http://www.fsf.org/licenses/licenses.html>.
 *
 * The aim of this version of the filter-running code is to go as
 * fast as possible (without flattening the sub-filters together)
 * by generating the necessary code at run-time.
 *
 * This runs the filter exactly as specified, without convolving
 * the sub-filters together or changing their order.  The only
 * rearrangement performed is making the IIR first coefficient
 * 1.0, and gathering any lone 1-coefficient FIR filters together
 * into a single initial gain adjustment.  For this reason, the
 * routine runs fastest if IIR and FIR sub-filters are grouped
 * together in IIR/FIR pairs, as these can then share common
 * working buffers.
 *
 * The generated code is cached, and is reused for more than one
 * filter if possible.  This means that a bank of 1000s of
 * filters of similar types will probably all end up sharing the
 * same generated routine, which improves processor cache and
 * memory usage.
 *
 * Probably the generated code could be improved, but it is not
 * too bad.  Copying the buffer values using 'rep movsl' turned
 * out to be much faster than loading and storing the floating
 * point values individually whilst working through the buffer.
 *
 * The generated code was tested for speed on a Celeron-900 and
 * on a Pentium-133.  It always beats the RF_CMDLIST option.  It
 * can be slightly slower than the RF_COMBINED option, but only
 * where that option gets a big advantage from flattening the
 * sub-filters.  For pre-flattened filters, it is faster.
 *
 * The generated code can be dumped out at any point in .s format
 * using fid_run_dump().  This can be assembled using 'gas' and
 * then disassembled with 'objdump -d' to see all the generated
 * code.
 *
 * Things that could be improved:
 *
 * - Don't keep the fir running total on the stack at all times.
 * Instead create it at the first FIR operation.  This means
 * generating about 10 new special-case macros.  This would save
 * an add for every filter stage, and some of the messing around
 * at start and end currently done to set up / clean up this
 * value on the FP stack.
 */

package vavi.sound.fidlib;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Iterator;
import java.util.List;


/**
 * JitFidFilter.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 060901 nsano initial version <br>
 */
class JitFidFilter extends FidFilter {

    private static class Routine {
        /** Next in list, or 0 */
        Routine nxt;
        /** Reference count */
        int ref;
        /** Hash of routine */
        int hash;
        /** Routine itself */
        byte[] code;
        /** Length of code in bytes */
        int len;
    }

    /** Magic: 0x64966325 */
    private int magic;
    /** Length of working buffer required in doubles */
    private int n_buf;
    /** Coefficient list */
    private double[] coef;

    /** Routine used */
    private Routine rout;

    /** Number of 4-byte chunks to copy from &buf[1] to &buf[0] */
    private int mov_cnt;
    /** Buffer itself */
    private double[] buf;

    private int HASH(byte[] p, int len) {
        return do_hash(p, len, 0);
    }

    // Code generation
    //
    // %edx is the working buffer pointer
    // %eax is the coefficient pointer
    // %ecx is the loop counter
    // floating point stack contains working values at the top, then
    // previous buffer value, then running iir total, then running
    // fir total
    //
    // Codes in the add() string:
    //
    // %C 4-byte long value count for loop
    // %L Label -- remember this address for looping back to
    // %R 1-byte relative jump back to %L address
    // %D 1-byte relative address of buffer value. If zero, this adjusts the
    // previous byte by ^=0x40 to make it a pure (%edx) form instead of 0(%edx)
    // %D+ 1-byte relative address of buffer value as above, plus increment %edx
    // if we are getting close to the end of the range
    // %A 1-byte relative address of coefficient value. If zero does same as for
    // %D.
    // %A+ 1-byte relative address of coefficient value, plus %eax inc
    // if necessary
    // %= Insert code to update %edx and %eax to point to the given offsets
    //
    // Startup code
    //
    // pushl %ebp
    // movl %esp,%ebp
    // movl 8(%ebp),%edx
    // movl (%edx),%eax
    // movl 4(%edx),%ecx
    // fldz
    // fldl 12(%ebp)
    // fldl 8(%edx)
    // fmull (%eax)
    // leal 8(%edx),%edi
    // leal 16(%edx),%esi
    // cld
    // rep movsl
    private void STARTUP() {
        add("55 89E5 8B5508 8B02 8B4A04 D9EE DD450C DD4208 DC08 8D7A08 8D7210 FC F3A5");
    }

    // Return
    //
    // fstp %st(0) // pop
    // fstp %st(1)
    // leave
    // ret
    private void RETURN() {
        add("DDD8 DDD9 C9 C3");
    }

    // Looping
    //
    // movl $100,%ecx
    // .LXX
    // ...
    // loop .LXX
    //
    // //WAS decl %ecx
    // //WAS testl %ecx,%ecx
    // //WAS jg .LXX
    private void FOR(int xx, int nnd, int nna) {
        add("B9%C %= %L", xx, (nnd) * 8, (nna) * 8);
    }

    // WAS #define NEXT(nnd, nna) add("%= 49 85C9 7F%R", (nnd)*8, (nna)*8)
    private void NEXT(int nnd, int nna) {
        add("%= E2%R", (nnd) * 8, (nna) * 8);
    }

    // Fetching/storing buffer values
    //
    // tmp= buf[n];
    // fldl nn(%edx)
    //
    // buf[nn]= iir;
    // fld %st(1)
    // fstpl nn(%edx)
    private void GETB(int nn) {
        add("DD42%D+", (nn) * 8);
    }

    private void PUTB(int nn) {
        add("D9C1 DD5A%D+", (nn) * 8);
    }

    // FIR element with following IIR element
    //
    // fir -= 2 * tmp;
    // fsub %st(0),%st(2)
    // fsub %st(0),%st(2)
    // fir -= tmp;
    // fsub %st(0),%st(2)
    // fir += tmp;
    // fadd %st(0),%st(2)
    // fir += 2 * tmp;
    // fadd %st(0),%st(2)
    // fadd %st(0),%st(2)
    // fir += coef[nn] * tmp;
    // fld %st(0)
    // fmull nn(%eax)
    // faddp %st(0),%st(3)

    private void FIRc_M2() {
        add("DCEA DCEA");
    }

    private void FIRc_M1() {
        add("DCEA");
    }

    private void FIRc_P1() {
        add("DCC2");
    }

    private void FIRc_P2() {
        add("DCC2 DCC2");
    }

    private void FIRc(int nn) {
        add("D9C0 DC48%A+ DEC3", (nn) * 8);
    }

    // FIR element with no following IIR element
    //
    // fir -= 2 * tmp;
    // fsub %st(0),%st(2)
    // fsubp %st(0),%st(2)
    // fir -= tmp;
    // fsubp %st(0),%st(2)
    // fir += 0 * tmp;
    // fstp %st(0),%st(0) // Really I just want to pop the top value
    // fir += tmp;
    // faddp %st(0),%st(2)
    // fir += 2 * tmp;
    // fadd %st(0),%st(2)
    // faddp %st(0),%st(2)
    // fir += coef[0] * tmp;
    // fmull nn(%eax)
    // faddp %st(0),%st(2)

    private void FIR_M2() {
        add("DCEA DEEA");
    }

    private void FIR_M1() {
        add("DEEA");
    }

    private void FIR_0() {
        add("DDD8");
    }

    private void FIR_P1() {
        add("DEC2");
    }

    private void FIR_P2() {
        add("DCC2 DEC2");
    }

    private void FIR(int nn) {
        add("DC48%A+ DEC2", (nn) * 8);
    }

    // IIR element
    //
    // iir -= coef[nn] * tmp;
    // fmull nn(%eax)
    // fsubp %st(0),%st(1)
    private void IIR(int nn) {
        add("DC48%A+ DEE9", (nn) * 8);
    }

    // Final FIR element of pure-FIR or mixed FIR-IIR stage
    //
    // iir= fir + coef[nn] * iir; fir= 0;
    // fxch
    // fmull nn(%eax)
    // faddp %st(2)
    // fldz
    // fstp %st(3)
    // iir= fir + 1.0 * iir; fir= 0;
    // fxch
    // faddp %st(2)
    // fldz
    // fstp %st(3)
    // iir= fir - 1.0 * iir; fir= 0;
    // fxch
    // fsubp %st(2)
    // fldz
    // fstp %st(3)
    private void FIREND(int nn) {
        add("D9C9 DC48%A+ DEC2 D9EE DDDB", (nn) * 8);
    }

    private void FIREND_P1() {
        add("D9C9 DEC2 D9EE DDDB");
    }

    private void FIREND_M1() {
        add("D9C9 DEEA D9EE DDDB");
    }

    //
    // Globals for handling routines
    //

    // Buffer address
    private byte[] r_buf;
    // Curent end of buffer
    private int r_end;
    // Current write-position
    private int r_cp;
    // Current loop-back label, or 0
    private byte[] r_lab;
    // Loop count
    private int r_loop;
    // %edx offset relative to initial position
    private int r_edx;
    // %eax offset relative to initial position
    private int r_eax;
    // List of routines or 0
    private List<Routine> r_list;

    //
    // Add code to the current routine. This uses global variables,
    // and so is not thread-safe.
    //
    private void add(String fmt, int... ap) {
        int ch, val;

        if (r_end - r_cp < 32) {
            throw new IllegalStateException("routine buffer exceeded");
        }
        int fmtP = 0;
        int apP = 0;
        int r_labP = 0;
        while ((ch = fmt.charAt(fmtP++)) != 0) {
            if (Character.isSpaceChar(ch))
                continue;
            if (Character.isDigit(ch) || (ch >= 'A' && ch <= 'F')) {
                val = ch >= 'A' ? ch - 'A' + 10 : ch - '0';
                ch = fmt.charAt(fmtP++);
                if (!Character.isDigit(ch) && !(ch >= 'A' && ch <= 'F')) {
                    throw new IllegalArgumentException("Bad format for add() routine");
                }
                val = (val * 16) + (ch >= 'A' ? ch - 'A' + 10 : ch - '0');
                r_buf[r_cp++] = (byte) val;
                continue;
            }
            if (ch != '%') {
                throw new IllegalArgumentException("add() routine bad format string");
            }
            switch (ch = fmt.charAt(fmtP++)) {
            case 'C':
                val = ap[apP++];
                r_loop = val;
                r_buf[r_cp++] = (byte) val;
                r_buf[r_cp++] = (byte) (val >> 8);
                r_buf[r_cp++] = (byte) (val >> 16);
                r_buf[r_cp++] = (byte) (val >> 24);
                break;
            case 'L':
                if (r_lab != null) {
                    throw new IllegalArgumentException("two stacked %L formats");
                }
                r_labP = r_cp;
                break;
            case 'R':
                if (r_labP == 0)
                    throw new IllegalArgumentException("%R without matching %L");
                val = r_labP - (r_cp + 1);
                if (val < -128)
                    throw new IllegalArgumentException("%R too far from %L");
                r_buf[r_cp++] = (byte) val;
                r_lab = null;
                break;
            case 'D':
                val = ap[apP++] - r_edx;
                if (val < -128 || val >= 128)
                    throw new IllegalArgumentException("%%edx offset out of range");
                if (val == 0)
                    r_buf[r_cp - 1] ^= 0x40;
                else
                    r_buf[r_cp++] = (byte) val;
                if (fmt.charAt(fmtP) == '+') {
                    fmtP++;
                    if (val >= 120) {
                        r_buf[r_cp++] = (byte) 0x83; // addl $120,%edx
                        r_buf[r_cp++] = (byte) 0xC2;
                        r_buf[r_cp++] = 0x78;
                        r_edx += 120;
                    }
                }
                break;
            case 'A':
                val = ap[apP++] - r_eax;
                if (val < -128 || val >= 128) {
                    throw new IllegalArgumentException("%%eax offset out of range");
                }
                if (val == 0) {
                    r_buf[r_cp - 1] ^= 0x40;
                } else {
                    r_buf[r_cp++] = (byte) val;
                }
                if (fmt.charAt(fmtP) == '+') {
                    fmtP++;
                    if (val >= 120) {
                        r_buf[r_cp++] = (byte) 0x83; // addl $120,%eax
                        r_buf[r_cp++] = (byte) 0xC0;
                        r_buf[r_cp++] = 0x78;
                        r_eax += 120;
                    }
                }
                break;
            case '=':
                val = ap[apP++] - r_edx;
                if (val != 0) {
                    if (val < -128 || val >= 128) {
                        throw new IllegalArgumentException("%%= adjust for %%edx is out of range");
                    }
                    r_buf[r_cp++] = (byte) 0x83; // addl $120,%edx
                    r_buf[r_cp++] = (byte) 0xC2;
                    r_buf[r_cp++] = (byte) val;
                    r_edx += val * (r_lab != null ? r_loop : 1);
                }
                val = ap[apP++] - r_eax;
                if (val != 0) {
                    if (val < -128 || val >= 128) {
                        throw new IllegalArgumentException("%%= adjust for %%edx is out of range");
                    }
                    r_buf[r_cp++] = (byte) 0x83; // addl $120,%edx
                    r_buf[r_cp++] = (byte) 0xC0;
                    r_buf[r_cp++] = (byte) val;
                    r_eax += val * (r_lab != null ? r_loop : 1);
                }
                break;
            default:
                throw new IllegalArgumentException("bad format for add()");
            }
        }
    }

    /** Loop the generated code above LOOP repeats (8) */
    private static final int LOOP = 8;

    /**
     * Create an instance of a filter, ready to run. This returns a void*
     * handle, and a JIT-compiled function to call to execute the filter. (The
     * functions are cached, so if several versions of the same filter are
     * generated with different parameters, it is likely that the same routine
     * will end up servicing all of them).
     *
     * Working buffers for the filter instances must be allocated separately
     * using fid_run_newbuf(). This allows many simultaneous instances of the
     * same filter to be run.
     *
     * The sub-filters are executed in the precise order that they are given.
     * This may lead to some inefficiency, because normally when an IIR filter
     * is followed by an FIR filter, the buffers can be shared. So, if the
     * sub-filters are not in IIR/FIR pairs, then extra memory accesses are
     * required.
     *
     * In any case, factors are extracted from IIR filters (so that the first
     * coefficient is 1), and single-element FIR filters are merged into the
     * global gain factor, and are ignored.
     *
     * The returned handle must be released using fid_run_free().
     */
    JitFidFilter(List<FidFilter> filt) {
        double[] dp = null;
        int dpP;
        double gain = 1.0;
        int a, val;
        double[] coef_tmp;
        byte[] rout_tmp;
        int coef_cnt, coef_max;
        int rout_cnt, rout_max;
        int filt_cnt = 0;
        // Run rr;
        int o_buf = 1; // Current offset into working buffer
        int o_coef = 1; // Current offset into coefficient array
        int hash;

        for (FidFilter ff : filt) {
            filt_cnt += ff.len;
        }

        // Allocate rough worst-case sizes for temporary arrays
        coef_tmp = new double[coef_max = filt_cnt + 1];
        rout_tmp = new byte[rout_max = filt_cnt * 16 + 20 + 32];
        dpP = o_coef; // Leave space to put gain back in later, coef_tmp

        // Setup JIT globals
        r_buf = rout_tmp;
        r_end = rout_max; // rout_tmp
        r_cp = 0; // r_buf
        r_lab = null;
        r_loop = 0;
        r_edx = 0;
        r_eax = 0;

        STARTUP(); // Setup iir/fir running totals on stack, apply gain

        // Generate command and coefficient lists
        Iterator<FidFilter> i = filt.iterator();
        while (i.hasNext()) {
            FidFilter ff = i.next();
            int n_iir = 0, n_fir = 0, cnt;
            double[] iir = null, fir = null;
            double adj = 0;
            if (ff.type == 'F' && ff.len == 1) {
                gain *= ff.val[0];
                ff = i.next();
                continue;
            }
            if (ff.type == 'F') {
                iir = null;
                n_iir = 0;
                fir = ff.val;
                n_fir = ff.len;
                ff = i.next();
            } else if (ff.type == 'I') {
                iir = ff.val;
                n_iir = ff.len;
                fir = null;
                n_fir = 0;
                ff = i.next();
                while (ff.type == 'F' && ff.len == 1) {
                    gain *= ff.val[0];
                    ff = i.next();
                }
                if (ff.type == 'F') {
                    fir = ff.val;
                    n_fir = ff.len;
                    ff = i.next();
                }
            } else {
                throw new IllegalArgumentException("can only handle IIR + FIR types");
            }

            // Okay, we now have an IIR/FIR pair to process, possibly with
            // n_iir or n_fir == 0 if one half is missing
            cnt = Math.max(n_iir, n_fir);
            if (n_iir != 0) {
                adj = 1.0 / iir[0];
                gain *= adj;
            }

            // Sort out any trailing IIR coefficients where there are more
            // IIR than FIR
            if (cnt > n_fir) {
                a = cnt - (n_fir != 0 ? n_fir : 1);
                if (a >= LOOP) {
                    FOR(a, o_buf, o_coef);
                    IIR(o_coef);
                    o_coef++;
                    GETB(o_buf);
                    o_buf++;
                    NEXT(o_buf, o_coef);
                    o_buf += (a - 1);
                    o_coef += (a - 1);
                    while (a-- > 0)
                        dp[dpP++] = iir[--cnt] * adj;
                } else
                    while (a-- > 0) {
                        dp[dpP++] = iir[--cnt] * adj;
                        IIR(o_coef);
                        o_coef++;
                        GETB(o_buf);
                        o_buf++;
                    }
            }

            // Sort out any trailing FIR coefficients where there are more
            // FIR than IIR
            if (cnt > n_iir) {
                a = cnt - (n_iir != 0 ? n_iir : 1);
                if (a >= LOOP) {
                    FOR(a, o_buf, o_coef);
                    FIR(o_coef);
                    o_coef++;
                    GETB(o_buf);
                    o_buf++;
                    NEXT(o_buf, o_coef);
                    o_buf += (a - 1);
                    o_coef += (a - 1);
                    while (a-- > 0)
                        dp[dpP++] = fir[--cnt];
                } else
                    while (a-- > 0) {
                        val = (int) fir[--cnt];
                        if (val == -2.0)
                            FIR_M2();
                        else if (val == -1.0)
                            FIR_M1();
                        else if (val == 0.0)
                            FIR_0();
                        else if (val == 1.0)
                            FIR_P1();
                        else if (val == 2.0)
                            FIR_P2();
                        else {
                            dp[dpP++] = val;
                            FIR(o_coef);
                            o_coef++;
                        }
                        GETB(o_buf);
                        o_buf++;
                    }
            }

            // Sort out any common IIR/FIR coefficients remaining
            if (cnt > 1) {
                a = cnt - 1;
                if (a >= LOOP) {
                    FOR(a, o_buf, o_coef);
                    FIRc(o_coef);
                    o_coef++;
                    IIR(o_coef);
                    o_coef++;
                    GETB(o_buf);
                    o_buf++;
                    NEXT(o_buf, o_coef);
                    o_buf += (a - 1);
                    o_coef += 2 * (a - 1);
                    while (a-- > 0) {
                        dp[dpP++] = fir[--cnt] * adj;
                        dp[dpP++] = iir[cnt] * adj;
                    }
                } else
                    while (a-- > 0) {
                        val = (int) fir[--cnt];
                        if (val == -2.0)
                            FIRc_M2();
                        else if (val == -1.0)
                            FIRc_M1();
                        else if (val == 0.0)
                            ;
                        else if (val == 1.0)
                            FIRc_P1();
                        else if (val == 2.0)
                            FIRc_P2();
                        else {
                            dp[dpP++] = val;
                            FIRc(o_coef);
                            o_coef++;
                        }

                        dp[dpP++] = iir[cnt] * adj;
                        IIR(o_coef);
                        o_coef++;
                        GETB(o_buf);
                        o_buf++;
                    }
            }

            // Handle the final element, according to whether there was any
            // FIR activity in this filter stage
            PUTB(o_buf - 1);
            if (n_fir != 0) {
                if (fir[0] == 1.0) {
                    FIREND_P1();
                } else if (fir[0] == -1.0) {
                    FIREND_M1();
                } else {
                    dp[dpP++] = fir[0];
                    FIREND(o_coef);
                    o_coef++;
                }
            }
        }

        coef_tmp[0] = gain;
        RETURN();

        // Sanity checks
        coef_cnt = dpP;
        rout_cnt = r_cp;
        if (coef_cnt > coef_max || rout_cnt > rout_max) {
            throw new IllegalArgumentException("arrays exceeded");
        }

        // Now generate a hash of the code we've created, and see if we've
        // already got a cached version of that routine
        hash = HASH(rout_tmp, rout_cnt);
        for (Routine rout : r_list) {
            if (rout.hash == hash && rout.len == rout_cnt && memcmp(rout.code, rout_tmp, rout_cnt)) {
                break;
            }
        }
        if (rout == null) {
            rout = new Routine();
            r_list.add(rout);
            rout.ref = 0;
            rout.hash = hash;
            rout.code = new byte[rout_cnt];
            rout.len = rout_cnt;
            System.arraycopy(rout_tmp, 0, rout.code, 0, rout_cnt);
            // Maybe flush caches at this point on processors other than x86
        }
        // free(rout_tmp);

        // Allocate the final Run structure to return
        this.magic = 0x64966325;
        this.n_buf = o_buf;
        this.coef = new double[coef_cnt];
        System.arraycopy(coef_tmp, 0, this.coef, 0, coef_cnt);
        rout.ref++;

        // free(coef_tmp);

        // buffer
        if (magic != 0x64966325) {
            throw new IllegalStateException("Bad handle passed");
        }

        mov_cnt = (this.n_buf - 1) / 4;
    }

    private boolean memcmp(byte[] a, byte[] b, int len) {
        for (int i = 0; i < len; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Delete the filter
     */
    public void fid_run_free() {
        Routine rout = this.rout;
        rout.ref--;
        if (rout.ref != 0) {
            // Delete the routine out of the cache
            for (Routine p : r_list) {
                if (p == rout) {
                    r_list.remove(p);
                    break;
                }
            }
            // free(rout);
        }
        // free(run);
    }

    /**
     * Dump all the routines in memory
     */
    void fid_run_dump(OutputStream out) {
        int a, cnt = 0;
        PrintStream ps = new PrintStream(out);
        ps.print("    .file    \"fid_run_dump.s\"\n" + "    .version    \"01.01\"\n" + ".text\n" + "    .align 4\n");
        for (Routine rr : r_list) {
            ps.printf(".globl    process_%d\n" + "    .type    process_%d,@function\n" + "process_%d:\n", cnt, cnt, cnt);
            for (a = 0; a < rr.len; a++) {
                ps.printf("    .byte 0x%02X\n", 255 & rr.code[a]);
            }
            ps.printf(".Lfe1%d:\n" + "    .size    process_%d,.Lfe1%d-process_%d\n", cnt, cnt, cnt, cnt);
            cnt++;
        }
    }

    /*
     * Hashing function. Overkill for this job, but might as well use a good one
     * as it's available. See below for credits.
     */

    private int hashsize(int n) {
        return (1 << (n));
    }

    private int hashmask(int n) {
        return (hashsize(n) - 1);
    }

    /**
     * mix 3 32-bit values reversibly. For every delta with one or two bits set,
     * and the deltas of all three high bits or all three low bits, whether the
     * original value of a,b,c is almost all zero or is uniformly distributed,
     * If mix() is run forward or backward, at least 32 bits in a,b,c have at
     * least 1/4 probability of changing. If mix() is run forward, every bit of
     * c will change between 1/3 and 2/3 of the time. (Well, 22/100 and 78/100
     * for some 2-bit deltas.) mix() was built out of 36 single-cycle latency
     * instructions in a structure that could supported 2x parallelism, like so:
     *
     * <pre>
     *       a -= b;
     *       a -= c; x = (c&gt;&gt;13);
     *       b -= c; a &circ;= x;
     *       b -= a; x = (a&lt;&lt;8);
     *       c -= a; b &circ;= x;
     *       c -= b; x = (b&gt;&gt;13);
     *       ...
     * </pre>
     *
     * Unfortunately, superscalar Pentiums and Sparcs can't take advantage of
     * that parallelism. They've also turned some of those single-cycle latency
     * instructions into multi-cycle latency instructions. Still, this is the
     * fastest good hash I could find. There were about 2^^68 to choose from. I
     * only looked at a billion or so.
     */
    private void mix(int a, int b, int c) {
        a -= b;
        a -= c;
        a ^= (c >> 13);
        b -= c;
        b -= a;
        b ^= (a << 8);
        c -= a;
        c -= b;
        c ^= (b >> 13);
        a -= b;
        a -= c;
        a ^= (c >> 12);
        b -= c;
        b -= a;
        b ^= (a << 16);
        c -= a;
        c -= b;
        c ^= (b >> 5);
        a -= b;
        a -= c;
        a ^= (c >> 3);
        b -= c;
        b -= a;
        b ^= (a << 10);
        c -= a;
        c -= b;
        c ^= (b >> 15);
    }

    /**
     * hash a variable-length key into a 32-bit value
     * <p>
     * The best hash table sizes are powers of 2. There is no need to do mod a
     * prime (mod is sooo slow!). If you need less than 32 bits, use a bitmask.
     * For example, if you need only 10 bits, do
     * </p>
     *
     * <pre>
     * h = (h &amp; hashmask(10));
     * </pre>
     *
     * In which case, the hash table should have hashsize(10) elements.
     * <p>
     * If you are hashing n strings (byte **)k, do it like this:
     *
     * <pre>
     * for (i = 0, h = 0; i &lt; n; ++i)
     *     h = hash(k[i], len[i], h);
     * </pre>
     *
     * By Bob Jenkins, 1996. bob_jenkins@burtleburtle.net. You may use this code
     * any way you wish, private, educational, or commercial. It's free.
     * <p>
     * See http://burtleburtle.net/bob/hash/evahash.html Use for hash table
     * lookup, or anything where one collision in 2^^32 is acceptable. Do NOT
     * use for cryptographic purposes.
     *
     * @param k the key (the unaligned variable-length array of bytes)
     * @param length the length of the key, counting by bytes
     * @param initval the previous hash, or an arbitrary value. can be any
     *        4-byte value
     * @return a 32-bit value. Every bit of the key affects every bit of the
     *         return value. Every 1-bit and 2-bit delta achieves avalanche.
     *         About 6*len+35 instructions.
     */
    private int do_hash(byte[] k, int length, int initval) {
        int a, b, c, len;

        // Set up the internal state
        len = length;
        a = b = 0x9e3779b9; // the golden ratio; an arbitrary value
        c = initval; // the previous hash value

        // handle most of the key
        int kP = 0;
        while (len >= 12) {
            a += (k[kP + 0] + (k[kP + 1] << 8) + (k[kP + 2] << 16) + (k[kP + 3] << 24));
            b += (k[kP + 4] + (k[kP + 5] << 8) + (k[kP + 6] << 16) + (k[kP + 7] << 24));
            c += (k[kP + 8] + (k[kP + 9] << 8) + (k[kP + 10] << 16) + (k[kP + 11] << 24));
            mix(a, b, c);
            kP += 12;
            len -= 12;
        }

        // handle the last 11 bytes
        c += length;
        switch (len) // all the case statements fall through
        {
        case 11:
            c += (k[kP + 10] << 24);
        case 10:
            c += (k[kP + 9] << 16);
        case 9:
            c += (k[kP + 8] << 8);
        // the first byte of c is reserved for the length
        case 8:
            b += (k[kP + 7] << 24);
        case 7:
            b += (k[kP + 6] << 16);
        case 6:
            b += (k[kP + 5] << 8);
        case 5:
            b += k[kP + 4];
        case 4:
            a += (k[kP + 3] << 24);
        case 3:
            a += (k[kP + 2] << 16);
        case 2:
            a += (k[kP + 1] << 8);
        case 1:
            a += k[kP + 0];
        // case 0: nothing left to add
        }
        mix(a, b, c);
        // report the result
        return c;
    }

    public double filter_step(double val) {
        // rout.code;
        return 0;
    }
}

/* */
