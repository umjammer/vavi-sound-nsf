/*
 * Command-list based filter-running code.
 *
 * Copyright (c) 2002-2003 Jim Peters <http://uazu.net/>.  This
 * file is released under the GNU Lesser General Public License
 * (LGPL) version 2.1 as published by the Free Software
 * Foundation.  See the file COPYING_LIB for details, or visit
 * <http://www.fsf.org/licenses/licenses.html>.
 *
 * This version of the filter-running code is based on getting
 * the filter to go as fast as possible with a pre-compiled
 * routine, but without flattening the filter structure.  This
 * gives greater accuracy than the combined filter.  The result
 * is mostly faster than the combined filter (tested on ix86 with
 * gcc -O6), except where the combined filter gets a big
 * advantage from flattening the filter list.  This code is also
 * portable (unlike the JIT option).
 */

package vavi.sound.fidlib;


/**
 * CommandListFidFilter.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 060901 nsano initial version <br>
 */
class CommandListFidFilter extends FidFilter {
    /** Magic: 0x64966325 */
    private int magic;
    /** Length of working buffer required in doubles */
    private int buf_size;
    /** Coefficient list */
    private double[] coef;
    /** Command list */
    private byte[] cmd;

    /** Number of bytes to memmove */
    private int mov_cnt;
    /** */
    private double[] buf = new double[buf_size];

    /**
     * Filter processing routine. This is designed to avoid too many branches,
     * and references are very localized in the code, keeping the optimizer from
     * trying to store and remember old values.
     */
    private class X {
        double iir;
        int coefP;
        double fir = 0;
        int bufP = 0;
        double tmp = buf[0];
        X(double[] iir_p, int iir_pP) {
            iir = iir_p[iir_pP];
            // Using a memmove first is faster on gcc -O6 / ix86 than moving
            // the values whilst working through the buffers.
            System.arraycopy(buf, 1, buf, 0, mov_cnt);
        }
        void IIR() {
            iir -= coef[coefP++] * tmp;
            tmp = buf[bufP++];
        }
        void FIR() {
            fir += coef[coefP++] * tmp;
            tmp = buf[bufP++];
        }
        void BOTH() {
            iir -= coef[coefP++] * tmp;
            fir += coef[coefP++] * tmp;
            tmp = buf[bufP++];
        }
        void ENDIIR() {
            iir -= coef[coefP++] * tmp;
            tmp = buf[bufP++];
            buf[-1] = iir;
        }
        void ENDFIR() {
            fir += coef[coefP++] * tmp;
            tmp = buf[bufP++];
            buf[-1] = iir;
            iir = fir + coef[coefP++] * iir;
            fir = 0;
        }
        void ENDBOTH() {
            iir -= coef[coefP++] * tmp;
            fir += coef[coefP++] * tmp;
            tmp = buf[bufP++];
            buf[-1] = iir;
            iir = fir + coef[coefP++] * iir;
            fir = 0;
        }
        void GAIN() {
            iir *= coef[coefP++];
        }
    }

    /**
     * Step commands:
     *
     * <pre>
     *    0  END
     *    1  IIR coefficient (1+0)
     *    2  2x IIR coefficient (2+0)
     *    3  3x IIR coefficient (3+0)
     *    4  4Nx IIR coefficient (4N+0)
     *    5  FIR coefficient (0+1)
     *    6  2x FIR coefficient (0+2)
     *    7  3x FIR coefficient (0+3)
     *    8  4Nx FIR coefficient (0+4N)
     *    9  IIR+FIR coefficients (1+1)
     *   10  2x IIR+FIR coefficients (2+2)
     *   11  3x IIR+FIR coefficients (3+3)
     *   12  4Nx IIR+FIR coefficients (4N+4N)
     *   13  End-stage, pure IIR, assume no FIR done at all (1+0)
     *   14  End-stage with just FIR coeff (0+2)
     *   15  End-stage with IIR+FIR coeff (1+2)
     *   16  IIR + pure-IIR endstage (2+0)
     *   17  FIR + FIR end-stage (0+3)
     *   18  IIR+FIR + IIR+FIR end-stage (2+3)
     *   19  Nx (IIR + pure-IIR endstage) (2+0)
     *   20  Nx (FIR + FIR end-stage) (0+3)
     *   21  Nx (IIR+FIR + IIR+FIR end-stage) (2+3)
     *   22  Gain coefficient (0+1)
     * </pre>
     *
     * Most filters are made up of 2x2 IIR/FIR pairs, which means a list of
     * command 18 bytes. The other big job would be long FIR filters. These have
     * to be handled with a list of 7,6,5 commands, plus a 13 command.
     */
    public void filter_step(double[] iir_p, long len) {
        int iir_pP = 0;
        while (len != 0) {

            X x = new X(iir_p, iir_pP);

            byte[] cmd = this.cmd;
            int cmdP = 0;
            byte ch;
            int cnt;

            while ((ch = cmd[cmdP++]) != 0) {
                switch (ch) {
                case 1:
                    x.IIR();
                    break;
                case 2:
                    x.IIR();
                    x.IIR();
                    break;
                case 3:
                    x.IIR();
                    x.IIR();
                    x.IIR();
                    break;
                case 4:
                    cnt = cmd[cmdP++];
                    do {
                        x.IIR();
                        x.IIR();
                        x.IIR();
                        x.IIR();
                    } while (--cnt > 0);
                    break;
                case 5:
                    x.FIR();
                    break;
                case 6:
                    x.FIR();
                    x.FIR();
                    break;
                case 7:
                    x.FIR();
                    x.FIR();
                    x.FIR();
                    break;
                case 8:
                    cnt = cmd[cmdP++];
                    do {
                        x.FIR();
                        x.FIR();
                        x.FIR();
                        x.FIR();
                    } while (--cnt > 0);
                    break;
                case 9:
                    x.BOTH();
                    break;
                case 10:
                    x.BOTH();
                    x.BOTH();
                    break;
                case 11:
                    x.BOTH();
                    x.BOTH();
                    x.BOTH();
                    break;
                case 12:
                    cnt = cmd[cmdP++];
                    do {
                        x.BOTH();
                        x.BOTH();
                        x.BOTH();
                        x.BOTH();
                    } while (--cnt > 0);
                    break;
                case 13:
                    x.ENDIIR();
                    break;
                case 14:
                    x.ENDFIR();
                    break;
                case 15:
                    x.ENDBOTH();
                    break;
                case 16:
                    x.IIR();
                    x.ENDIIR();
                    break;
                case 17:
                    x.FIR();
                    x.ENDFIR();
                    break;
                case 18:
                    x.BOTH();
                    x.ENDBOTH();
                    break;
                case 19:
                    cnt = cmd[cmdP++];
                    do {
                        x.IIR();
                        x.ENDIIR();
                    } while (--cnt > 0);
                    break;
                case 20:
                    cnt = cmd[cmdP++];
                    do {
                        x.FIR();
                        x.ENDFIR();
                    } while (--cnt > 0);
                    break;
                case 21:
                    cnt = cmd[cmdP++];
                    do {
                        x.BOTH();
                        x.ENDBOTH();
                    } while (--cnt > 0);
                    break;
                case 22:
                    x.GAIN();
                    break;
                }
            }
            len--;
            iir_p[iir_pP] = x.iir;
            iir_pP++;
        }
    }

    /** */
    public double filter_step(double val) {
        return 0;
    }
}

/* */
