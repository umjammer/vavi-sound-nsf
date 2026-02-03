package vavi.sound.nsf.festalon;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import static java.lang.System.getLogger;


/**
 * X6502.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 060501 nsano initial version <br>
 */
public class X6502 {

    private static final Logger logger = getLogger(X6502.class.getName());

    /** */
    private static class WriteMap {

        Writer writer;
        Object _private;
        WriteMap next;
    }

    /** Temporary cycle counter */
    private int tCount;

    /**
     * I'll change this to uint32 later... I'll need to AND PC after increments
     * to 0xffff when I do, though. Perhaps an IPC() macro?
     */
    int pc; // short
    int a, x, y, s, p, mooPI; // byte

    private byte jammed;
    private int count;

    /**
     * Simulated IRQ pin held low(or is it high?). And other junk hooked on for
     * speed reasons.
     */
    private int irqLow;

    /* Data bus "cache" for reads from certain areas */
    private int db;
    private final byte[] ram;

    final boolean pal;

    /* Sent to the hook functions. */
    final byte[] _private;

    private final Reader[] readers = new Reader[0x1_0000];
    private final WriteMap[] writers = new WriteMap[0x1_0000];

    // writeFunc BWrite = new byte[0x10000];
    private final Object[] readPrivate = new Object[0x1_0000];

    // void *BWritePrivate[0x10000];

    public int timestamp;

    long timestampBase;

    static final int N_FLAG = 0x80;
    static final int V_FLAG = 0x40;
    static final int U_FLAG = 0x20;
    static final int B_FLAG = 0x10;
    static final int D_FLAG = 0x08;
    static final int I_FLAG = 0x04;
    static final int Z_FLAG = 0x02;
    static final int C_FLAG = 0x01;

    static final double NTSC_CPU = 1789772.7272727272727272;
    static final double PAL_CPU = 1662607.125;

    static final int FCEU_IQEXT = 0x001;
    static final int FCEU_IQEXT2 = 0x002;
    // ...
    static final int FCEU_IQRESET = 0x020;
    static final int FCEU_IQDPCM = 0x100;
    static final int FCEU_IQFCOUNT = 0x200;

    /** */
    private final Writer nullWriter = (address, value) -> {
    };

    /** */
    private final Reader nullReader = (address, dataBus) -> dataBus;

    /** */
    public void setReader(int start, int end, Reader reader, Object _private) {

        if (reader == null) {
            reader = nullReader;
        }
        for (int i = start; i <= end; i++) {
            readers[i] = reader;
            readPrivate[i] = _private;
        }
    }

    /** */
    public void setWriter(int start, int end, Writer writer, Object _private) {

        if (writer == null) {
            writer = nullWriter;
        }

        for (int i = start; i <= end; i++) {
            if (writers[i] != null && writers[i].writer != null && writers[i].writer != nullWriter) {
                WriteMap oldHead = writers[i];
                WriteMap newHead = new WriteMap();

                newHead.writer = writer;
                newHead._private = _private;
                newHead.next = oldHead;

                writers[i] = newHead;
            } else {
                writers[i] = new WriteMap();
                writers[i].writer = writer;
                writers[i]._private = _private;
                writers[i].next = null;
            }
        }
    }

    /** */
    private void addCYC(int x) {
        int __x = x;
        tCount += __x;
        timestamp += __x;
        count -= __x * 48;
    }

    /** */
    private int readMemory(int address) {
//logger.log(Level.TRACE, "address: %04x, %s".formatted(address, readers[address]));
        if (address == 0x770) logger.log(Level.TRACE, "Reader[770]: %s".formatted(readers[address]));
        return db = readers[address & 0xffff].exec(address & 0xffff, db); // AReadPrivate[A]
    }

    /** */
    private void writeMemory(int address, int value) {
        if (address >= 0x4000 && address <= 0x4017)
            logger.log(Level.TRACE, "APU WRITE: %04x = %02x".formatted(address, value));
        else if (address < 0x800)
            logger.log(Level.TRACE, "WRITE: %04x = %02x".formatted(address, value));
        WriteMap wm = writers[address & 0xffff];

        do {
            wm.writer.exec(address & 0xffff, value); // wm._private
            wm = wm.next;
        } while (wm != null);
        // X.BWrite[A].func(X.BWrite[A].private,A,V);
    }

    /** */
    private int readRAM(int address) {
        return db = ram[address & 0x7ff] & 0xff;
    }

    /** */
    private void writeRAM(int address, int value) {
        ram[address & 0x7ff] = (byte) value;
    }

    /** */
    int readDm(int address) {
        addCYC(1);
        return db = readers[address].exec(address, db); // AReadPrivate[A]
    }

    /** */
    void writeDm(int address, int value) {
        X6502.WriteMap wm;
        db = value;

        wm = writers[address];

        do {
            wm.writer.exec(address, value); // wm._private
            wm = wm.next;
        } while (wm != null);
    }

    /** */
    private void push(int v) {
//logger.log(Level.TRACE, "s: %1$04X, %1$s".formatted(s));
        writeRAM(0x100 + s, v);
        s--;
        s &= 0xff; // TODO vavi
    }

    /** */
    private int pop() {
        try {
            return readRAM(0x100 + (++s));
        } finally {
            s &= 0xff; // TODO vavi
        }
    }

    /** */
    private static final int[] znTable = {
            Z_FLAG, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG,
            N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG,
            N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG,
            N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG,
            N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG,
            N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG,
            N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG,
            N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG,
            N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG,
            N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG,
            N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG,
            N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG,
            N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG,
            N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG,
            N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG,
            N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG, N_FLAG
    };

    // Some of these operations will only make sense if you know what the flag
    // constants are.

    /** */
    private void x_zn(int zort) {
        p &= ~(Z_FLAG | N_FLAG);
        p |= znTable[zort];
    }

    /** */
    private void x_znt(int zort) {
        p |= znTable[zort];
    }

    /** */
    private void jr(boolean cond) {
        if (cond) {
            int tmp;
            int disp;
            disp = readMemory(pc);
            pc++;
            addCYC(1);
            tmp = pc;
            pc += (byte) disp;
            pc &= 0xffff;
            if (((tmp ^ pc) & 0x100) != 0) {
                addCYC(1);
            }
        } else {
            pc++;
            pc &= 0xffff;
        }
    }

    private final OP lda = new OP() {
        @Override
        int exec(int... i) {
            a = i[0];
            x_zn(a);
            return a;
        }
    };

    private final OP ldx = new OP() {
        @Override
        int exec(int... i) {
            x = i[0];
            x_zn(x);
            return x;
        }
    };

    private final OP ldy = new OP() {
        @Override
        int exec(int... i) {
            y = i[0];
            x_zn(y);
            return y;
        }
    };

    /* All of the freaky arithmetic operations. */
    private final OP and = new OP() {
        @Override
        int exec(int... i) {
            a &= i[0];
            x_zn(a);
            return a;
        }
    };

    private final OP bit = new OP() {
        @Override
        int exec(int... i) {
            p &= ~(Z_FLAG | V_FLAG | N_FLAG);
            p |= znTable[i[0] & a] & Z_FLAG;
            p |= i[0] & (V_FLAG | N_FLAG);
            return i[0];
        }
    };

    private final OP eor = new OP() {
        @Override
        int exec(int... i) {
            a ^= i[0];
            x_zn(a);
            return a;
        }
    };

    private final OP ora = new OP() {
        @Override
        int exec(int... i) {
            a |= i[0];
            x_zn(a);
            return a;
        }
    };

    private final OP adc = new OP() {
        @Override
        int exec(int... i) {
            int l = a + i[0] + (p & 1);
            p &= ~(Z_FLAG | C_FLAG | N_FLAG | V_FLAG);
            p |= ((((a ^ i[0]) & 0x80) ^ 0x80) & ((a ^ l) & 0x80)) >> 1;
            p |= (l >> 8) & C_FLAG;
            a = l & 0xff;
            x_znt(a);
            return a;
        }
    };

    private final OP sbc = new OP() {
        @Override
        int exec(int... i) {
            int l = a - i[0] - ((p & 1) ^ 1);
            p &= ~(Z_FLAG | C_FLAG | N_FLAG | V_FLAG);
            p |= ((a ^ l) & (a ^ i[0]) & 0x80) >> 1;
            p |= ((l >> 8) & C_FLAG) ^ C_FLAG;
            a = l & 0xff;
            x_znt(a);
            return a;
        }
    };

    private final OP cmpl = new OP() {
        @Override
        int exec(int... i) {
            int t = i[0] - i[1];
            x_zn(t & 0xff);
            p &= ~C_FLAG;
            p |= ((t >> 8) & C_FLAG) ^ C_FLAG;
            return i[0];
        }
    };

    /* Special undocumented operation. Very similar to CMP. */
    private final OP axs = new OP() {
        @Override
        int exec(int... i) {
            int t = (a & x) - i[0];
            x_zn(t & 0xff);
            p &= ~C_FLAG;
            p |= ((t >> 8) & C_FLAG) ^ C_FLAG;
            x = t & 0xff;
            return x;
        }
    };

    private final OP cmp = new OP() {
        @Override
        int exec(int... i) {
            cmpl.exec(a, i[0]);
            return i[0];
        }
    };

    private final OP cpx = new OP() {
        @Override
        int exec(int... i) {
            cmpl.exec(x, i[0]);
            return i[0];
        }
    };

    private final OP cpy = new OP() {
        @Override
        int exec(int... i) {
            cmpl.exec(y, i[0]);
            return i[0];
        }
    };

    /* The following operations modify the byte being worked on. */
    private final OP dec = new OP() {
        @Override
        int exec(int... i) {
            int val = (i[0] - 1) & 0xff;
            x_zn(val);
            return val;
        }
    };

    private final OP inc = new OP() {
        @Override
        int exec(int... i) {
            int val = (i[0] + 1) & 0xff;
            x_zn(val);
            return val;
        }
    };

    private final OP asl = new OP() {
        @Override
        int exec(int... i) {
            int val = i[0] & 0xff;
            p &= ~C_FLAG;
            p |= (val >> 7) & C_FLAG;
            val = (val << 1) & 0xff;
            x_zn(val);
            return val;
        }
    };

    private final OP lsr = new OP() {
        @Override
        int exec(int... i) {
            int val = i[0] & 0xff;
            p &= ~(C_FLAG | N_FLAG | Z_FLAG);
            p |= val & 1;
            val >>= 1;
            x_znt(val);
            return val;
        }
    };

    /* For undocumented instructions, maybe for other things later... */
    private final OP lsra = new OP() {
        @Override
        int exec(int... i) {
            p &= ~(C_FLAG | N_FLAG | Z_FLAG);
            p |= a & 1;
            a >>= 1;
            x_znt(a);
            return a;
        }
    };

    private final OP rol = new OP() {
        @Override
        int exec(int... i) {
            int val = i[0] & 0xff;
            int l = (val >> 7) & C_FLAG;
            val = ((val << 1) | (p & C_FLAG)) & 0xff;
            p &= ~(Z_FLAG | N_FLAG | C_FLAG);
            p |= l;
            x_znt(val);
            return val;
        }
    };

    private final OP ror = new OP() {
        @Override
        int exec(int... i) {
            int val = i[0] & 0xff;
            int l = val & 1;
            val = ((val >> 1) | ((p & C_FLAG) << 7)) & 0xff;
            p &= ~(Z_FLAG | N_FLAG | C_FLAG);
            p |= l;
            x_znt(val);
            return val;
        }
    };

    /*
     * Icky icky thing for some undocumented instructions. Can easily be broken
     * if names of local variables are changed.
     */

    /** Absolute */
    private int getAB() {
        int target = readMemory(pc);
        pc++;
        pc &= 0xffff;
        target |= readMemory(pc) << 8;
        pc++;
        pc &= 0xffff;
        return target;
    }

    /** Absolute Indexed(for reads) */
    private int getABIRD(int i) {
        int tmp = getAB();
        int target = tmp;
        target += i;
        if (((target ^ tmp) & 0x100) != 0) {
            target &= 0xffff;
            readMemory(target ^ 0x100);
            addCYC(1);
        }
        return target;
    }

    /** Absolute Indexed(for writes and rmws) */
    private int getABIWR(int i) {
        int rt = getAB();
        int target = (rt + i) & 0xffff;
        readMemory((target & 0x00ff) | (rt & 0xff00));
        return target;
    }

    /** Zero Page */
    private int getZP() {
        int target = readMemory(pc);
        pc++;
        pc &= 0xffff;
        return target;
    }

    /** Zero Page Indexed */
    private int getZPI(int i) {
        int target = (readMemory(pc) + i) & 0xff;
        pc++;
        pc &= 0xffff;
        return target;
    }

    /** Indexed Indirect */
    private int getIX() {
        int tmp;
        tmp = readMemory(pc);
        pc++;
        pc &= 0xffff;
        tmp = (tmp + x) & 0xff;
        int target = readRAM(tmp);
        target |= readRAM((tmp + 1) & 0xff) << 8;
        return target;
    }

    /** Indirect Indexed (for reads) */
    private int getIYRD() {
        int rt;
        int tmp;
        tmp = readMemory(pc);
        pc++;
        pc &= 0xffff;
        rt = readRAM(tmp);
        rt |= readRAM((tmp + 1) & 0xff) << 8;
        int target = rt + y;
        if (((target ^ rt) & 0x100) != 0) {
            target &= 0xffff;
            readMemory(target ^ 0x100);
            addCYC(1);
        }
        return target;
    }

    /** Indirect Indexed (for writes and rmws) */
    private int getIYWR() {
        int rt;
        int tmp;
        tmp = readMemory(pc);
        pc++;
        pc &= 0xffff;
        rt = readRAM(tmp);
        rt |= readRAM((tmp + 1) & 0xff) << 8;
        int target = (rt + y) & 0xffff;
        readMemory((target & 0x00ff) | (rt & 0xff00));
        return target;
    }

    private abstract static class OP {

        abstract int exec(int... i);
    }

    /*
     * Now come the macros to wrap up all of the above stuff addressing mode
     * functions and operation macros. Note that operation macros will always
     * operate(redundant redundant) on the variable "x".
     */

    private void rmwA(OP op) {
        a = op.exec(a);
    }

    /* Meh... */
    private void rmwAB(OP op) {
        int address = getAB();
        int value = readMemory(address);
        writeMemory(address, value);
        value = op.exec(value);
        writeMemory(address, value);
    }

    /** */
    private void rmwABI(int reg, OP op) {
        int address = getABIWR(reg);
        int value = readMemory(address);
        writeMemory(address, value);
        value = op.exec(value);
        writeMemory(address, value);
    }

    /** */
    private void rmwABX(OP op) {
        rmwABI(x, op);
    }

    /** */
    private void rmwABY(OP op) {
        rmwABI(y, op);
    }

    /** */
    private void rmwIX(OP op) {
        int address = getIX();
        int value = readMemory(address);
        writeMemory(address, value);
        value = op.exec(value);
        writeMemory(address, value);
    }

    /** */
    private void rmwIY(OP op) {
        int address = getIYWR();
        int value = readMemory(address);
        writeMemory(address, value);
        value = op.exec(value);
        writeMemory(address, value);
    }

    /** */
    private void rmwZP(OP op) {
        int address = getZP();
        int value = readRAM(address);
        value = op.exec(value);
        writeRAM(address, value);
    }

    /** */
    private void rmwZPX(OP op) {
        int address = getZPI(x);
        int value = readRAM(address);
        value = op.exec(value);
        writeRAM(address, value);
    }

    /** */
    private void rmwZPY(OP op) {
        int address = getZPI(y);
        int value = readRAM(address);
        value = op.exec(value);
        writeRAM(address, value);
    }

    /** */
    private void ldIM(OP op) {
        int value = readMemory(pc);
        pc++;
        pc &= 0xffff;
        op.exec(value);
    }

    /** */
    private void ldZP(OP op) {
        int address = getZP();
        int value = readRAM(address);
        op.exec(value);
    }

    /** */
    private void ldZPX(OP op) {
        int address = getZPI(x);
        int value = readRAM(address);
        op.exec(value);
    }

    /** */
    private void ldZPY(OP op) {
        int address = getZPI(y);
        int value = readRAM(address);
        op.exec(value);
    }

    /** */
    private void ldAB(OP op) {
        int address = getAB();
        int value = readMemory(address);
        op.exec(value);
    }

    /** */
    private void ldABI(int reg, OP op) {
        int address = getABIRD(reg);
        int value = readMemory(address);
        op.exec(value);
    }

    /** */
    private void ldABX(OP op) {
        ldABI(x, op);
    }

    /** */
    private void ldABY(OP op) {
        ldABI(y, op);
    }

    /** */
    private void ldIX(OP op) {
        int address;
        int value;
        address = getIX();
        value = readMemory(address);
        op.exec(value);
    }

    /** */
    private void ldIY(OP op) {
        int address;
        int value;
        address = getIYRD();
        value = readMemory(address);
        op.exec(value);
    }

    /** */
    private void stZP(int r) {
        int address;
        address = getZP();
        writeRAM(address, r);
    }

    /** */
    private void stZPX(int r) {
        int address = getZPI(x);
        writeRAM(address, r);
    }

    /** */
    private void stZPY(int r) {
        int address = getZPI(y);
        writeRAM(address, r);
    }

    /** */
    private void stAB(int r) {
        int address = getAB();
        writeMemory(address, r);
    }

    /** */
    private void stABI(int reg, int r) {
        int address = getABIWR(reg);
        writeMemory(address, r);
    }

    /** */
    private void stABX(int r) {
        stABI(x, r);
    }

    /** */
    private void stABY(int r) {
        stABI(y, r);
    }

    /** */
    private void stIX(int r) {
        int address = getIX();
        writeMemory(address, r);
    }

    /** */
    private void stIY(int r) {
        int address = getIYWR();
        writeMemory(address, r);
    }

    /** */
    private static final byte[] cycTable = {
            /* 0x00 */ 7, 6, 2, 8, 3, 3, 5, 5, 3, 2, 2, 2, 4, 4, 6, 6,
            /* 0x10 */ 2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,
            /* 0x20 */ 6, 6, 2, 8, 3, 3, 5, 5, 4, 2, 2, 2, 4, 4, 6, 6,
            /* 0x30 */ 2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,
            /* 0x40 */ 6, 6, 2, 8, 3, 3, 5, 5, 3, 2, 2, 2, 3, 4, 6, 6,
            /* 0x50 */ 2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,
            /* 0x60 */ 6, 6, 2, 8, 3, 3, 5, 5, 4, 2, 2, 2, 5, 4, 6, 6,
            /* 0x70 */ 2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,
            /* 0x80 */ 2, 6, 2, 6, 3, 3, 3, 3, 2, 2, 2, 2, 4, 4, 4, 4,
            /* 0x90 */ 2, 6, 2, 6, 4, 4, 4, 4, 2, 5, 2, 5, 5, 5, 5, 5,
            /* 0xA0 */ 2, 6, 2, 6, 3, 3, 3, 3, 2, 2, 2, 2, 4, 4, 4, 4,
            /* 0xB0 */ 2, 5, 2, 5, 4, 4, 4, 4, 2, 4, 2, 4, 4, 4, 4, 4,
            /* 0xC0 */ 2, 6, 2, 8, 3, 3, 5, 5, 2, 2, 2, 2, 4, 4, 6, 6,
            /* 0xD0 */ 2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,
            /* 0xE0 */ 2, 6, 3, 8, 3, 3, 5, 5, 2, 2, 2, 2, 4, 4, 6, 6,
            /* 0xF0 */ 2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,
    };

    /** */
    public void beginIRQ(int w) {
        irqLow |= w;
    }

    /** */
    public void endIRQ(int w) {
        irqLow &= ~w;
    }

    /** */
    public void reset() {
        irqLow = FCEU_IQRESET;
    }

    /** */
    public X6502(byte[] ram, boolean pal, byte[] _private) {
        this.ram = ram;
        this.pal = pal;
        this._private = _private;
    }

    /** */
    public void power() {
        count = tCount = irqLow = pc = a = x = y = s = p = mooPI = db = jammed = 0;
        timestamp = 0;
        setReader(0x0000, 0xffff, null, null);
        setWriter(0x0000, 0xffff, null, null);
        reset();
    }

    public void run(NesApu apu, int cycles) {
        logger.log(Level.TRACE, "run entered: " + cycles);
        if (pal) {
            cycles *= 15; // 15 * 4 = 60
        } else {
            cycles *= 16; // 16 * 4 = 64
        }

        count += cycles;

        while (count > 0) {
            int temp;
            int b1;
            int t;

            logger.log(Level.TRACE, "PC: %04x A:%02x X:%02x Y:%02x S:%02x P:%02x".formatted(pc, a, x, y, s, p));

            // Fetch Opcode
            // t = (int) (readers[pc].exec(pc, 0) & 0xff);
            // pc++;

            if ((irqLow != 0)) {
                if ((irqLow & FCEU_IQRESET) != 0) {
                    jammed = 0;
                    mooPI = p = I_FLAG;
                    irqLow &= ~FCEU_IQRESET;
                } else {
                    if ((mooPI & I_FLAG) == 0 && jammed == 0) {
                        addCYC(7);
                        push(pc >> 8);
                        push(pc);
                        push((p & ~B_FLAG) | (U_FLAG));
                        p |= I_FLAG;
                        pc = readMemory(0xfffe);
                        pc |= readMemory(0xffff) << 8;
                    }
                }
                if (count <= 0) {
                    mooPI = p;
                    return;
                } // Should increase accuracy without a major speed hit.
            }

            mooPI = p;
            b1 = readMemory(pc);
// if (CC++ < 300) { logger.log(Level.DEBUG, "%04x: %02x".formatted(pc, b1)); }
// if (debug) System.err.printf("Fetched Opcode: %02x%n", b1);
// else { System.exit(0); }
            addCYC(cycTable[b1]);

            temp = tCount;
            tCount = 0;

            apu.hookSoundCPU(temp);

//logger.log(Level.DEBUG, "%04x:$%02x".formatted(_PC, b1));
            pc++;
            switch (b1) {
                case 0x00: // BRK
                    pc++;
                    push(pc >> 8);
                    push(pc);
                    push(p | U_FLAG | B_FLAG);
                    p |= I_FLAG;
                    mooPI |= I_FLAG;
                    pc = readMemory(0xfffe);
                    pc |= readMemory(0xffff) << 8;
                    break;

                case 0x40: // RTI
                    p = pop();
//              pi = p; // This is probably incorrect, so it's commented out.
                    pc = pop();
                    pc |= pop() << 8;
                    break;

                case 0x60: // RTS
                    pc = pop();
                    pc |= pop() << 8;
                    pc++;
                    break;

                case 0x48: // PHA
                    push(a);
                    break;
                case 0x08: // PHP
                    push(p | U_FLAG | B_FLAG);
                    break;
                case 0x68: // PLA
                    a = pop();
                    x_zn(a);
                    break;
                case 0x28: // PLP
                    p = pop();
                    break;
                case 0x4c: {
                    int ptmp = pc;
                    int npc;

                    npc = readMemory(ptmp);
                    ptmp++;
                    npc |= readMemory(ptmp) << 8;
                    pc = npc;
                }
                break; // JMP ABSOLUTE
                case 0x6c: {
                    int tmp = getAB();
                    pc = readMemory(tmp);
                    pc |= readMemory(((tmp + 1) & 0x00ff) | (tmp & 0xff00)) << 8;
//logger.log(Level.DEBUG, "%04x, %04x, %02x".formatted(tmp, pc, readMemory(x, 0xe2)));
                }
                break;
                case 0x20: { // JSR
                    int npc;
                    npc = readMemory(pc);
                    pc++;
                    push(pc >> 8);
                    push(pc);
                    pc = readMemory(pc) << 8;
                    pc |= npc;
                }
                break;

                case 0xaa: // TAX
                    x = a;
                    x_zn(a);
                    break;

                case 0x8a: // TXA
                    a = x;
                    x_zn(a);
                    break;

                case 0xa8: // TAY
                    y = a;
                    x_zn(a);
                    break;
                case 0x98: // TYA
                    a = y;
                    x_zn(a);
                    break;

                case 0xba: // TSX
                    x = s;
                    x_zn(x);
                    break;
                case 0x9a: // TXS
                    s = x;
                    break;

                case 0xca: // DEX
                    x--;
                    x &= 0xff;
                    x_zn(x);
                    break;
                case 0x88: // DEY
                    y--;
                    y &= 0xff;
                    x_zn(y);
                    break;

                case 0xe8: // INX
                    x++;
                    x &= 0xff;
                    x_zn(x);
                    break;
                case 0xC8: // INY
                    y++;
                    y &= 0xff;
                    x_zn(y);
                    break;

                case 0x18: // CLC
                    p &= ~C_FLAG;
                    break;
                case 0xd8: // CLD
                    p &= ~D_FLAG;
                    break;
                case 0x58: // CLI
                    p &= ~I_FLAG;
                    break;
                case 0xb8: // CLV
                    p &= ~V_FLAG;
                    break;

                case 0x38: // SEC
                    p |= C_FLAG;
                    break;
                case 0xf8: // SED
                    p |= D_FLAG;
                    break;
                case 0x78: // SEI
                    p |= I_FLAG;
                    break;

                case 0xea: // NOP
                    break;

                case 0x0a:
                    rmwA(asl);
                    break;
                case 0x06:
                    rmwZP(asl);
                    break;
                case 0x16:
                    rmwZPX(asl);
                    break;
                case 0x0e:
                    rmwAB(asl);
                    break;
                case 0x1e:
                    rmwABX(asl);
                    break;

                case 0xc6:
                    rmwZP(dec);
                    break;
                case 0xd6:
                    rmwZPX(dec);
                    break;
                case 0xce:
                    rmwAB(dec);
                    break;
                case 0xde:
                    rmwABX(dec);
                    break;

                case 0xe6:
                    rmwZP(inc);
                    break;
                case 0xf6:
                    rmwZPX(inc);
                    break;
                case 0xee:
                    rmwAB(inc);
                    break;
                case 0xfe:
                    rmwABX(inc);
                    break;

                case 0x4a:
                    rmwA(lsr);
                    break;
                case 0x46:
                    rmwZP(lsr);
                    break;
                case 0x56:
                    rmwZPX(lsr);
                    break;
                case 0x4e:
                    rmwAB(lsr);
                    break;
                case 0x5e:
                    rmwABX(lsr);
                    break;

                case 0x2a:
                    rmwA(rol);
                    break;
                case 0x26:
                    rmwZP(rol);
                    break;
                case 0x36:
                    rmwZPX(rol);
                    break;
                case 0x2e:
                    rmwAB(rol);
                    break;
                case 0x3e:
                    rmwABX(rol);
                    break;

                case 0x6a:
                    rmwA(ror);
                    break;
                case 0x66:
                    rmwZP(ror);
                    break;
                case 0x76:
                    rmwZPX(ror);
                    break;
                case 0x6e:
                    rmwAB(ror);
                    break;
                case 0x7E:
                    rmwABX(ror);
                    break;

                case 0x69:
                    ldIM(adc);
                    break;
                case 0x65:
                    ldZP(adc);
                    break;
                case 0x75:
                    ldZPX(adc);
                    break;
                case 0x6d:
                    ldAB(adc);
                    break;
                case 0x7d:
                    ldABX(adc);
                    break;
                case 0x79:
                    ldABY(adc);
                    break;
                case 0x61:
                    ldIX(adc);
                    break;
                case 0x71:
                    ldIY(adc);
                    break;

                case 0x29:
                    ldIM(and);
                    break;
                case 0x25:
                    ldZP(and);
                    break;
                case 0x35:
                    ldZPX(and);
                    break;
                case 0x2d:
                    ldAB(and);
                    break;
                case 0x3d:
                    ldABX(and);
                    break;
                case 0x39:
                    ldABY(and);
                    break;
                case 0x21:
                    ldIX(and);
                    break;
                case 0x31:
                    ldIY(and);
                    break;

                case 0x24:
                    ldZP(bit);
                    break;
                case 0x2c:
                    ldAB(bit);
                    break;

                case 0xc9:
                    ldIM(cmp);
                    break;
                case 0xc5:
                    ldZP(cmp);
                    break;
                case 0xd5:
                    ldZPX(cmp);
                    break;
                case 0xcd:
                    ldAB(cmp);
                    break;
                case 0xdd:
                    ldABX(cmp);
                    break;
                case 0xd9:
                    ldABY(cmp);
                    break;
                case 0xc1:
                    ldIX(cmp);
                    break;
                case 0xd1:
                    ldIY(cmp);
                    break;

                case 0xe0:
                    ldIM(cpx);
                    break;
                case 0xe4:
                    ldZP(cpx);
                    break;
                case 0xec:
                    ldAB(cpx);
                    break;

                case 0xc0:
                    ldIM(cpy);
                    break;
                case 0xc4:
                    ldZP(cpy);
                    break;
                case 0xcc:
                    ldAB(cpy);
                    break;

                case 0x49:
                    ldIM(eor);
                    break;
                case 0x45:
                    ldZP(eor);
                    break;
                case 0x55:
                    ldZPX(eor);
                    break;
                case 0x4d:
                    ldAB(eor);
                    break;
                case 0x5d:
                    ldABX(eor);
                    break;
                case 0x59:
                    ldABY(eor);
                    break;
                case 0x41:
                    ldIX(eor);
                    break;
                case 0x51:
                    ldIY(eor);
                    break;

                case 0xa9:
                    ldIM(lda);
                    break;
                case 0xa5:
                    ldZP(lda);
                    break;
                case 0xb5:
                    ldZPX(lda);
                    break;
                case 0xad:
                    ldAB(lda);
                    break;
                case 0xbd:
                    ldABX(lda);
                    break;
                case 0xb9:
                    ldABY(lda);
                    break;
                case 0xa1:
                    ldIX(lda);
                    break;
                case 0xb1:
                    ldIY(lda);
                    break;

                case 0xa2:
                    ldIM(ldx);
                    break;
                case 0xa6:
                    ldZP(ldx);
                    break;
                case 0xb6:
                    ldZPY(ldx);
                    break;
                case 0xae:
                    ldAB(ldx);
                    break;
                case 0xbe:
                    ldABY(ldx);
                    break;

                case 0xa0:
                    ldIM(ldy);
                    break;
                case 0xa4:
                    ldZP(ldy);
                    break;
                case 0xb4:
                    ldZPX(ldy);
                    break;
                case 0xac:
                    ldAB(ldy);
                    break;
                case 0xbc:
                    ldABX(ldy);
                    break;

                case 0x09:
                    ldIM(ora);
                    break;
                case 0x05:
                    ldZP(ora);
                    break;
                case 0x15:
                    ldZPX(ora);
                    break;
                case 0x0D:
                    ldAB(ora);
                    break;
                case 0x1D:
                    ldABX(ora);
                    break;
                case 0x19:
                    ldABY(ora);
                    break;
                case 0x01:
                    ldIX(ora);
                    break;
                case 0x11:
                    ldIY(ora);
                    break;

                case 0xeb: // undocumented
                    break;
                case 0xe9:
                    ldIM(sbc);
                    break;
                case 0xe5:
                    ldZP(sbc);
                    break;
                case 0xf5:
                    ldZPX(sbc);
                    break;
                case 0xed:
                    ldAB(sbc);
                    break;
                case 0xfd:
                    ldABX(sbc);
                    break;
                case 0xf9:
                    ldABY(sbc);
                    break;
                case 0xe1:
                    ldIX(sbc);
                    break;
                case 0xf1:
                    ldIY(sbc);
                    break;

                case 0x85:
                    stZP(a);
                    break;
                case 0x95:
                    stZPX(a);
                    break;
                case 0x8D:
                    stAB(a);
                    break;
                case 0x9D:
                    stABX(a);
                    break;
                case 0x99:
                    stABY(a);
                    break;
                case 0x81:
                    stIX(a);
                    break;
                case 0x91:
                    stIY(a);
                    break;

                case 0x86:
                    stZP(x);
                    break;
                case 0x96:
                    stZPY(x);
                    break;
                case 0x8E:
                    stAB(x);
                    break;

                case 0x84:
                    stZP(y);
                    break;
                case 0x94:
                    stZPX(y);
                    break;
                case 0x8C:
                    stAB(y);
                    break;

                case 0x90: // BCC
                    jr((p & C_FLAG) == 0);
                    break;

                case 0xb0: // BCS
                    jr((p & C_FLAG) != 0);
                    break;

                case 0xf0: // BEQ
                    jr((p & Z_FLAG) != 0);
                    break;

                case 0xd0: // BNE
                    jr((p & Z_FLAG) == 0);
                    break;

                case 0x30: // BMI
                    jr((p & N_FLAG) != 0);
                    break;

                case 0x10: // BPL
                    jr((p & N_FLAG) == 0);
                    break;

                case 0x50: // BVC
                    jr((p & V_FLAG) == 0);
                    break;

                case 0x70: // BVS
                    jr((p & V_FLAG) != 0);
                    break;

//          default:
//logger.log(Level.DEBUG, "Bad %02x at $%04x".formatted(b1, pc));
//              break;

//#ifdef moo

                // Here comes the undocumented instructions block. Note that this
                // implementation may be "wrong". If so, please tell me.

                case 0x2b: // AAC
                case 0x0b:
                    ldIM(new OP() {
                        @Override
                        int exec(int... i) {
                            and.exec(i);
                            p &= ~C_FLAG;
                            p |= (a >> 7) & C_FLAG;
                            return a;
                        }
                    });
                    break;

                case 0x87: // AAX
                    stZP(a & x);
                    break;
                case 0x97:
                    stZPY(a & x);
                    break;
                case 0x8f:
                    stAB(a & x);
                    break;
                case 0x83:
                    stIX(a & x);
                    break;

                case 0x6b: { // ARR - ARGH, MATEY!
                    ldIM(new OP() {
                        @Override
                        int exec(int... i) {
                            int arrtmp;
                            and.exec(i);
                            p &= ~V_FLAG;
                            p |= (a ^ (a >> 1)) & 0x40;
                            arrtmp = (a >> 7) & C_FLAG;
                            a = (a >> 1) & 0xff;
                            a |= (p & C_FLAG) << 7;
                            p &= ~C_FLAG;
                            p |= arrtmp;
                            x_zn(a);
                            return a;
                        }
                    });
                }
                break;

                case 0x4b: // ASR
                    ldIM(new OP() {
                        @Override
                        int exec(int... i) {
                            lsra.exec(i);
                            and.exec(i);
                            return a;
                        }
                    });
                    break;

                case 0xab: // ATX(OAL) Is this(OR with $EE) correct?
                    ldIM(new OP() {
                        @Override
                        int exec(int... i) {
                            a |= 0xEE;
                            and.exec(i);
                            x = a;
                            return a;
                        }
                    });
                    break;

                case 0xcb: // AXS
                    ldIM(axs);
                    break;

                case 0xc7: // DCP
                    ldZP(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = dec.exec(i[0]);
                            cmp.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0xd7:
                    ldZPX(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = dec.exec(i[0]);
                            cmp.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0xcf:
                    ldAB(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = dec.exec(i[0]);
                            cmp.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0xdf:
                    ldABX(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = dec.exec(i[0]);
                            cmp.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0xdb:
                    ldABY(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = dec.exec(i[0]);
                            cmp.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0xc3:
                    ldIX(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = dec.exec(i[0]);
                            cmp.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0xd3:
                    ldIY(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = dec.exec(i[0]);
                            cmp.exec(val);
                            return val;
                        }
                    });
                    break;

                case 0xe7: // ISC
                    ldZP(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = inc.exec(i[0]);
                            sbc.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0xf7:
                    ldZPX(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = inc.exec(i[0]);
                            sbc.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0xef:
                    ldAB(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = inc.exec(i[0]);
                            sbc.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0xff:
                    ldABX(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = inc.exec(i[0]);
                            sbc.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0xfb:
                    ldABY(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = inc.exec(i[0]);
                            sbc.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0xe3:
                    ldIX(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = inc.exec(i[0]);
                            sbc.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0xf3:
                    ldIY(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = inc.exec(i[0]);
                            sbc.exec(val);
                            return val;
                        }
                    });
                    break;

                case 0x04: // DOP
                    pc++;
                    break;
                case 0x14:
                    pc++;
                    break;
                case 0x34:
                    pc++;
                    break;
                case 0x44:
                    pc++;
                    break;
                case 0x54:
                    pc++;
                    break;
                case 0x64:
                    pc++;
                    break;
                case 0x74:
                    pc++;
                    break;

                case 0x80:
                    pc++;
                    break;
                case 0x82:
                    pc++;
                    break;
                case 0x89:
                    pc++;
                    break;
                case 0xc2:
                    pc++;
                    break;
                case 0xd4:
                    pc++;
                    break;
                case 0xe2:
                    pc++;
                    break;
                case 0xf4:
                    pc++;
                    break;

                case 0x02: // KIL
                case 0x12:
                case 0x22:
                case 0x32:
                case 0x42:
                case 0x52:
                case 0x62:
                case 0x72:
                case 0x92:
                case 0xb2:
                case 0xd2:
                case 0xf2:
                    addCYC(0xff);
                    jammed = 1;
                    pc--;
                    break;

                case 0xbb: // LAR
                    rmwABY(new OP() {
                        @Override
                        int exec(int... i) {
                            s &= i[0];
                            a = x = s;
                            x_zn(x);
                            return i[0];
                        }
                    });
                    break;

                case 0xa7: // LAX
                    ldZP(new OP() {
                        @Override
                        int exec(int... i) {
                            lda.exec(i);
                            ldx.exec(i);
                            return i[0];
                        }
                    });
                    break;
                case 0xb7:
                    ldZPY(new OP() {
                        @Override
                        int exec(int... i) {
                            lda.exec(i);
                            ldx.exec(i);
                            return i[0];
                        }
                    });
                    break;
                case 0xaf:
                    ldAB(new OP() {
                        @Override
                        int exec(int... i) {
                            lda.exec(i);
                            ldx.exec(i);
                            return i[0];
                        }
                    });
                    break;
                case 0xbf:
                    ldABY(new OP() {
                        @Override
                        int exec(int... i) {
                            lda.exec(i);
                            ldx.exec(i);
                            return i[0];
                        }
                    });
                    break;
                case 0xa3:
                    ldIX(new OP() {
                        @Override
                        int exec(int... i) {
                            lda.exec(i);
                            ldx.exec(i);
                            return i[0];
                        }
                    });
                    break;
                case 0xb3:
                    ldIY(new OP() {
                        @Override
                        int exec(int... i) {
                            lda.exec(i);
                            ldx.exec(i);
                            return i[0];
                        }
                    });
                    break;

                case 0x1a: // NOP
                case 0x3a:
                case 0x5a:
                case 0x7a:
                case 0xda:
                case 0xfa:
                    break;

                case 0x27: // RLA
                    rmwZP(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = rol.exec(i[0]);
                            and.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0x37:
                    rmwZPX(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = rol.exec(i[0]);
                            and.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0x2f:
                    rmwAB(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = rol.exec(i[0]);
                            and.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0x3f:
                    rmwABX(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = rol.exec(i[0]);
                            and.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0x3b:
                    rmwABY(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = rol.exec(i[0]);
                            and.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0x23:
                    rmwIX(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = rol.exec(i[0]);
                            and.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0x33:
                    rmwIY(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = rol.exec(i[0]);
                            and.exec(val);
                            return val;
                        }
                    });
                    break;

                case 0x67: // RRA
                    rmwZP(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = ror.exec(i[0]);
                            adc.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0x77:
                    rmwZPX(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = ror.exec(i[0]);
                            adc.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0x6f:
                    rmwAB(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = ror.exec(i[0]);
                            adc.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0x7f:
                    rmwABX(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = ror.exec(i[0]);
                            adc.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0x7b:
                    rmwABY(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = ror.exec(i[0]);
                            adc.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0x63:
                    rmwIX(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = ror.exec(i[0]);
                            adc.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0x73:
                    rmwIY(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = ror.exec(i[0]);
                            adc.exec(val);
                            return val;
                        }
                    });
                    break;

                case 0x07: // SLO
                    rmwZP(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = asl.exec(i[0]);
                            ora.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0x17:
                    rmwZPX(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = asl.exec(i[0]);
                            ora.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0x0f:
                    rmwAB(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = asl.exec(i[0]);
                            ora.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0x1f:
                    rmwABX(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = asl.exec(i[0]);
                            ora.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0x1b:
                    rmwABY(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = asl.exec(i[0]);
                            ora.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0x03:
                    rmwIX(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = asl.exec(i[0]);
                            ora.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0x13:
                    rmwIY(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = asl.exec(i[0]);
                            ora.exec(val);
                            return val;
                        }
                    });
                    break;

                case 0x47: // SRE
                    rmwZP(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = lsr.exec(i[0]);
                            eor.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0x57:
                    rmwZPX(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = lsr.exec(i[0]);
                            eor.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0x4f:
                    rmwAB(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = lsr.exec(i[0]);
                            eor.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0x5f:
                    rmwABX(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = lsr.exec(i[0]);
                            eor.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0x5b:
                    rmwABY(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = lsr.exec(i[0]);
                            eor.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0x43:
                    rmwIX(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = lsr.exec(i[0]);
                            eor.exec(val);
                            return val;
                        }
                    });
                    break;
                case 0x53:
                    rmwIY(new OP() {
                        @Override
                        int exec(int... i) {
                            int val = lsr.exec(i[0]);
                            eor.exec(val);
                            return val;
                        }
                    });
                    break;

                case 0x93: // AXA - SHA
                    stIY(a & x & (((a - y) >> 8) + 1));
                    break;
                case 0x9f:
                    stABY(a & x & (((a - y) >> 8) + 1));
                    break;

                case 0x9c: // SYA
                    stABX(y & (((a - x) >> 8) + 1));
                    break;

                case 0x9e: // SXA
                    stABY(x & (((a - y) >> 8) + 1));
                    break;

                case 0x9b: // XAS
                    s = a & x;
                    stABY(s & (((a - y) >> 8) + 1));
                    break;

                case 0x0c: // TOP
                    ldAB(null);
                    break;
                case 0x1c:
                    break;
                case 0x3c:
                    break;
                case 0x5c:
                    break;
                case 0x7c:
                    break;
                case 0xdc:
                    break;
                case 0xfc:
                    ldABX(null);
                    break;

                case 0x8b: // XAA - BIG QUESTION MARK HERE
                    a |= 0xee;
                    a &= x;
                    ldIM(and);
                    break;
                default:
                    logger.log(Level.TRACE, "UNKNOWN OP: %02x at %04x".formatted(b1, pc - 1));
                    break;
            }
            if (pc == 0x3800) {
                break;
            }
        }
    }

    /** */
    public void hackSpeed(NesApu apu) {
        int howMuch;

        mooPI = p;

        if (irqLow != 0 && (mooPI & I_FLAG) == 0) {
            run(apu, 0);
        }

        if ((apu.irqFrameMode == 0 || (apu.dmcFormat & 0xc0) == 0x80) && (mooPI & I_FLAG) == 0) {
            logger.log(Level.DEBUG, "abnormal skip");
            while (count > 0) {
                count -= 7 * 48;
                timestamp += 7;
                apu.hookSoundCPU(7);
                if (irqLow != 0) {
                    run(apu, 0);
                }
            }
        } else {
            howMuch = count / 48;
            if (howMuch > 0) {
                count -= howMuch * 48;
                timestamp += howMuch;
                apu.hookSoundCPU(howMuch);
            }
        }
    }
}
