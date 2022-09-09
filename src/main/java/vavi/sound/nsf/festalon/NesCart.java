package vavi.sound.nsf.festalon;

import java.util.Arrays;

import vavi.util.Debug;


/**
 * NesCart.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 060501 nsano initial version <br>
 */
class NesCart {
    /** */
    private static class Page {
        /** */
        byte[] page;
        /** */
        int pagePointer;
        /** */
        byte[] prgPointer;
        /** */
        int prgSize;
        /** */
        boolean prgIsRAM;
        /** */
        boolean prgRam;
        /** */
        int prgMask2;
        /** */
        int prgMask4;
        /** */
        int prgMask8;
        /** */
        int prgMask16;
        /** */
        int prgMask32;
        /** */
        Page(byte[] page, int pagePointer) {
            this.page = page;
            this.pagePointer = pagePointer;
//Debug.printf("Page: %04X, %04X\n", page.length, pagePointer);
        }
        public String toString() {
            return Arrays.toString(page) + ", " + pagePointer + ", " + prgSize + ", " + prgIsRAM;
        }
        public byte read(int address) {
            return page[address + pagePointer];
        }
        public void write(int address, byte value) {
            page[address + pagePointer] = value;
        }
    }

    /** */
    private Page[] pages = new Page[32];

    // 16 are (sort of) reserved for UNIF/iNES and 16 to map other stuff.

    /** */
    private void setPagePtr(int s, int address, byte[] p, int pP, boolean ram) {
        int addressBase = address >> 11;
//Debug.printf("setPagePtr: %04X, %04X, %s\n", address, addressBase, p);
//new Exception("*** DUMMY ***").printStackTrace();

        if (p != null) {
            for (int i = 0; i < (s >> 1); i++) {
//Debug.printf("0: %04X, %04X\n", pP, address);
                pages[addressBase + i] = new Page(p, pP - address);
                pages[addressBase + i].prgIsRAM = ram;
Debug.printf("1: page: %d, %04X, %04X\n", addressBase + i, pages[addressBase + i].page.length, pages[addressBase + i].pagePointer);
            }
        } else {
            for (int i = 0; i < (s >> 1); i++) {
                pages[addressBase + i] = new Page(new byte[0x10000], 0);
                pages[addressBase + i].prgIsRAM = false;
Debug.printf("3: page: %d, %04X, %04X\n", addressBase + i, pages[addressBase + i].page.length, pages[addressBase + i].pagePointer);
            }
        }
    }

    /** */
    private byte[] nothing = new byte[8192];

    /** */
    NesCart() {
        for (int i = 0; i < 32; i++) {
            pages[i] = new Page(nothing, -2048 * i);
            pages[i].prgPointer = null;
            pages[i].prgSize = 0;
        }
    }

    /** */
    void setupPRG(int chip, byte[] p, int size, boolean ram) {
        pages[chip].prgPointer = p;
        pages[chip].prgSize = size;

        pages[chip].prgMask2 = (size >> 11) - 1;
        pages[chip].prgMask4 = (size >> 12) - 1;
        pages[chip].prgMask8 = (size >> 13) - 1;
        pages[chip].prgMask16 = (size >> 14) - 1;
        pages[chip].prgMask32 = (size >> 15) - 1;

        pages[chip].prgRam = ram;
    }

    /** */
    Reader cartReader = (address, dataBus) -> {
//Debug.printf("cart.read: %04X, %04X, %04X\n", address >> 11, address, pages[address >> 11].page.length);
        return pages[address >> 11].read(address);
    };

    /** */
    Writer cartWriter = (address, value) -> {
        if (pages[address >> 11].prgIsRAM && pages[address >> 11] != null) {
            pages[address >> 11].write(address, (byte) value);
        }
    };

    /** */
    private Reader cartReaderOB = (address, dataBus) -> {
        if (pages[address >> 11] == null) {
            return (byte) dataBus;
        }
        return pages[address >> 11].read(address);
    };

    /** */
    void setPrg2r(int r, int address, int value) {
        value &= pages[r].prgMask2;

        setPagePtr(2, address, pages[r].prgPointer, pages[r].prgPointer != null ? value << 11 : 0, pages[r].prgRam);
    }

    /** */
    void setPrg2(int address, int value) {
        setPrg2r(0, address, value);
    }

    /** */
    void setPrg4r(int r, int address, int value) {
        value &= pages[r].prgMask4;
        setPagePtr(4, address, pages[r].prgPointer, pages[r].prgPointer != null ? value << 12 : 0, pages[r].prgRam);
    }

    /** */
    void setPrg4(int address, int value) {
        setPrg4r(0, address, value);
    }

    /** */
    void setPrg8r(int r, int address, int value) {
        if (pages[r].prgSize >= 8192) {
            value &= pages[r].prgMask8;
            setPagePtr(8, address, pages[r].prgPointer, pages[r].prgPointer != null ? value << 13 : 0, pages[r].prgRam);
        } else {
            int va = value << 2;
            for (int i = 0; i < 4; i++) {
                setPagePtr(2, address + (i << 11), pages[r].prgPointer, pages[r].prgPointer != null ? ((va + i) & pages[r].prgMask2) << 11 : 0, pages[r].prgRam);
            }
        }
    }

    /** */
    void setPrg8(int address, int value) {
        setPrg8r(0, address, value);
    }

    /** */
    void setPrg16r(int r, int address, int value) {
        if (pages[r].prgSize >= 16384) {
            value &= pages[r].prgMask16;
            setPagePtr(16, address, pages[r].prgPointer, pages[r].prgPointer != null ? value << 14 : 0, pages[r].prgRam);
        } else {
            int va = value << 3;
            for (int i = 0; i < 8; i++) {
                setPagePtr(2, address + (i << 11), pages[r].prgPointer, pages[r].prgPointer != null ? ((va + i) & pages[r].prgMask2) << 11 : 0, pages[r].prgRam);
            }
        }
    }

    /** */
    void setPrg16(int address, int value) {
        setPrg16r(0, address, value);
    }

    /** */
    void setPrg32r(int r, int address, int value) {
        if (pages[r].prgSize >= 32768) {
            value &= pages[r].prgMask32;
            setPagePtr(32, address, pages[r].prgPointer, pages[r].prgPointer != null ? value << 15 : 0, pages[r].prgRam);
        } else {
            int VA = value << 4;
            int x;

            for (x = 0; x < 16; x++) {
                setPagePtr(2, address + (x << 11), pages[r].prgPointer, pages[r].prgPointer != null ? ((VA + x) & pages[r].prgMask2) << 11 : 0, pages[r].prgRam);
            }
        }
    }

    /** */
    void setPrg32(int address, int value) {
        setPrg32r(0, address, value);
    }
}

/* */
