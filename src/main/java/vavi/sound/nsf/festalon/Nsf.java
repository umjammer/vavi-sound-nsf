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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Arrays;

import vavi.sound.nsf.festalon.ext.ay;
import vavi.sound.nsf.festalon.ext.fds;
import vavi.sound.nsf.festalon.ext.mmc5;
import vavi.sound.nsf.festalon.ext.n106;
import vavi.sound.nsf.festalon.ext.vrc6;
import vavi.sound.nsf.festalon.ext.vrc7;

import static java.lang.System.getLogger;


/**
 * Nsf.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 060310 nsano initial version <br>
 */
class Nsf extends Plugin {

    private static final Logger logger = getLogger(Nsf.class.getName());

    /** */
    static final int FESTAGFI_TAGS_DATA = 0x2;
    /** */
    private int playAddr;
    /** */
    private int initAddr;
    /** */
    private int loadAddr;
    /** */
    private byte[] bankSwitch = new byte[8];
    /** */
    private int soundChip;
    /** */
    private byte[] nsfRawData;
    /** */
    private int nsfRawDataSize;
    /** */
    private byte[] nsfExtra;
    /** */
    private int nsfExtraSize;
    /** */
    private int nsfNMIFlags;
    /** */
    private byte[] nsfData;
    /** */
    private int nsfMaxBank;
    /** */
    private int nsfSize;
    /** */
    private byte bsOn;
    /** */
    private int doReset;
    /** */
    private byte[] exWRam;
    /** */
    private byte[] ram = new byte[0x800];
    /** */
    private byte songReload;
    /** */
    private int currentSong;
    /** */
    private boolean pal;
    /** */
    private X6502 cpu;
    /** */
    private NesApu apu;
    /** */
    private NesCart cart;
    /** */
    private byte[] nsfRom;
    /** */
    private int doodoo;

    /** */
    static class Header {
        static final int SIZE = 118;

        /** NESM^Z */
        byte[] id = new byte[5];
        int version;
        int totalSongs;
        int startingSong;
        int loadAddressLow;
        int loadAddressHigh;
        int initAddressLow;
        int initAddressHigh;
        int playAddressLow;
        int playAddressHigh;
        byte[] gameName = new byte[32];
        byte[] artist = new byte[32];
        byte[] copyright = new byte[32];
        /** Unused */
        byte[] ntscSpeed = new byte[2];
        byte[] bankSwitch = new byte[8];
        /** Unused */
        byte[] palSpeed = new byte[2];
        int videoSystem;
        int soundChip;
        byte[] expansion = new byte[4];

        static Header readFrom(InputStream is) throws IOException {
            Header h = new Header();
            DataInputStream dis = new DataInputStream(is);
            dis.readFully(h.id, 0, 5);
            h.version = dis.readUnsignedByte();
            h.totalSongs = dis.readUnsignedByte();
            h.startingSong = dis.readUnsignedByte();
            h.loadAddressLow = dis.readUnsignedByte();
            h.loadAddressHigh = dis.readUnsignedByte();
            h.initAddressLow = dis.readUnsignedByte();
            h.initAddressHigh = dis.readUnsignedByte();
            h.playAddressLow = dis.readUnsignedByte();
            h.playAddressHigh = dis.readUnsignedByte();
            dis.readFully(h.gameName, 0, 32);
            dis.readFully(h.artist, 0, 32);
            dis.readFully(h.copyright, 0, 32);
            /* Unused */
            dis.readFully(h.ntscSpeed, 0, 2);
            dis.readFully(h.bankSwitch, 0, 8);
            /* Unused */
            dis.readFully(h.palSpeed, 0, 2);
            h.videoSystem = dis.readUnsignedByte();
            h.soundChip = dis.readUnsignedByte();
            dis.readFully(h.expansion, 0, 4);
            return h;
        }

        @Override
        public String toString() {
            String builder = "Header [id=" +
                    Arrays.toString(id) +
                    ", version=" +
                    version +
                    ", totalSongs=" +
                    totalSongs +
                    ", startingSong=" +
                    startingSong +
                    ", loadAddressLow=" +
                    loadAddressLow +
                    ", loadAddressHigh=" +
                    loadAddressHigh +
                    ", initAddressLow=" +
                    initAddressLow +
                    ", initAddressHigh=" +
                    initAddressHigh +
                    ", playAddressLow=" +
                    playAddressLow +
                    ", playAddressHigh=" +
                    playAddressHigh +
                    ", gameName=" +
                    Arrays.toString(gameName) +
                    ", artist=" +
                    Arrays.toString(artist) +
                    ", copyright=" +
                    Arrays.toString(copyright) +
                    ", ntscSpeed=" +
                    Arrays.toString(ntscSpeed) +
                    ", bankSwitch=" +
                    Arrays.toString(bankSwitch) +
                    ", palSpeed=" +
                    Arrays.toString(palSpeed) +
                    ", videoSystem=" +
                    videoSystem +
                    ", soundChip=" +
                    soundChip +
                    ", expansion=" +
                    Arrays.toString(expansion) +
                    "]";
            return builder;
        }
    }

    /** */
    private Writer amlWriter = new Writer() {
        public void exec(int address, int value) {
            cpu._private[cpu.a & 0x7ff] = (byte) value;
        }
    };

    /** */
    private Reader amlReader = new Reader() {
        public int exec(int address, int dataBus) {
            return cpu._private[cpu.a & 0x7ff];
        }
    };

    /** */
    public void close() {

        if (nsfData != null) {
            nsfData = null;
        }
    }

    /** */
    private void setBank(int address, int bank) {
        bank &= nsfMaxBank;
        if ((soundChip & 4) != 0) {
            System.arraycopy(nsfData, bank << 12, exWRam, address - 0x6000, 4096);
        } else {
            cart.setPrg4(address, bank);
        }
    }

    /** */
    public static Plugin load(byte[] buf, int size) throws IOException {
        Nsf nfe = new Nsf();

        nfe.outChannels = 1;

//        nfe = new Nsf();

        if (size >= 5 && new String(buf, 0, 5).equals("NESM" + (char) 0x1a)) {
            try {
                nfe.loadNSF(buf, size, 0);
            } catch (IOException e) {
                nfe.close();
                throw e;
            }
        } else if (new String(buf, 0, 4).equals("NSFE")) {
            if (nfe.load(buf, size, 0) == 0) {
                nfe.close();
                throw new IllegalArgumentException("NSFE");
            }
        } else {
            nfe.close();
            throw new IllegalArgumentException("unknown");
        }

        nfe.init();

        return nfe;
    }

    /*
     * Note: This function will leave the timing values in "1/1000000th sec
     * ticks" zeroed out, even though the NSF specs call for it; they're
     * redundant, and inaccurate.
     */
    private byte[] create(int[] totalSize) {
        Header header;
        byte[] buffer;

        header = new Header();

        System.arraycopy(("NESM" + 0x1A).getBytes(), 0, header.id, 0, 5);

        header.version = 0x01;
        header.totalSongs = totalSongs;
        header.startingSong = startingSong;

        header.loadAddressLow = (byte) loadAddr & 0xff;
        header.loadAddressHigh = (byte) ((loadAddr & 0xff00) >> 8);

        header.initAddressLow = (byte) initAddr & 0xff;
        header.initAddressHigh = (byte) ((initAddr & 0xff00) >> 8);

        header.playAddressLow = (byte) playAddr & 0xff;
        header.playAddressHigh = (byte) ((playAddr & 0xff00) >> 8);

        System.arraycopy(gameName.getBytes(), 0, header.gameName, 0, 31);
        header.gameName[31] = 0;

        System.arraycopy(artist.getBytes(), 0, header.artist, 0, 31);
        header.artist[31] = 0;

        System.arraycopy(copyright.getBytes(), 0, header.copyright, 0, 31);
        header.copyright[31] = 0;

        System.arraycopy(bankSwitch, 0, header.bankSwitch, 0, 8);

        header.videoSystem = videoSystem;

        header.soundChip = soundChip;

        totalSize[0] = Header.SIZE + nsfRawDataSize;

        buffer = new byte[totalSize[0]];

        System.arraycopy(header, 0, buffer, 0, Header.SIZE);
        System.arraycopy(nsfRawData, 0, buffer, Header.SIZE, nsfRawDataSize);

        return buffer;
    }

    /** */
    private void loadNSF(byte[] buf, int size, int info_only) throws IOException {
        Header nsfHeader = Header.readFrom(new ByteArrayInputStream(buf));
logger.log(Level.DEBUG, nsfHeader);

        nsfHeader.gameName[31] = nsfHeader.artist[31] = nsfHeader.copyright[31] = 0;

        gameName = new String(nsfHeader.gameName);
logger.log(Level.DEBUG, "gameName: " + gameName);
        artist = new String(nsfHeader.artist);
logger.log(Level.DEBUG, "artist: " + artist);
        copyright = new String(nsfHeader.copyright);
logger.log(Level.DEBUG, "copyright: " + copyright);

        loadAddr = nsfHeader.loadAddressLow;
        loadAddr |= nsfHeader.loadAddressHigh << 8;

        if (loadAddr < 0x6000) { // A buggy NSF...
            loadAddr += 0x8000;
        }
logger.log(Level.DEBUG, "loadAddr: %04x".formatted(loadAddr));

        initAddr = nsfHeader.initAddressLow;
        initAddr |= nsfHeader.initAddressHigh << 8;
logger.log(Level.DEBUG, "initAddr: %04x".formatted(initAddr));

        playAddr = nsfHeader.playAddressLow;
        playAddr |= nsfHeader.playAddressHigh << 8;
logger.log(Level.DEBUG, "playAddr: %04x".formatted(playAddr));

        nsfSize = size - 0x80;
logger.log(Level.DEBUG, "nsfSize: %04x".formatted(nsfSize));

        nsfMaxBank = (nsfSize + (loadAddr & 0xfff) + 4095) / 4096;
        nsfMaxBank = uppow2(nsfMaxBank);
logger.log(Level.DEBUG, "nsfMaxBank: %04x".formatted(nsfMaxBank));

        if (info_only == 0) {
            nsfData = new byte[nsfMaxBank * 4096];
            nsfRawData = nsfData;
            int nsfRawDataP = loadAddr & 0xfff;
            nsfRawDataSize = nsfSize;

            Arrays.fill(nsfData, 0, nsfMaxBank * 4096, (byte) 0x00);
            System.arraycopy(buf, 0, nsfData, nsfRawDataP, nsfSize);

            nsfMaxBank--;
logger.log(Level.DEBUG, "here 1");
        } else if (info_only == FESTAGFI_TAGS_DATA) {
            nsfData = new byte[nsfSize];
            nsfRawData = nsfData;
            nsfRawDataSize = nsfSize;
            System.arraycopy(buf, 0, nsfData, 0, nsfSize);
logger.log(Level.DEBUG, "here 2");
        }

        videoSystem = nsfHeader.videoSystem;

        soundChip = nsfHeader.soundChip;
        totalSongs = nsfHeader.totalSongs;
        startingSong = (byte) (nsfHeader.startingSong - 1);

        System.arraycopy(nsfHeader.bankSwitch, 0, bankSwitch, 0, nsfHeader.bankSwitch.length);
    }

    /** */
    public static Plugin getFileInfo(byte[] buf, int size, int type) throws IOException {
        Nsf nfe;

        if (type == 0) {
            return null; /* Invalid info type. */
        }

        nfe = new Nsf();

        if (size >= 5 && new String(buf, 0, 5).equals("NESM" + (char) 0x1a)) {
            try {
                nfe.loadNSF(buf, size, type);
            } catch (IOException e) {
                nfe.close();
                throw e;
            }
        } else if (new String(buf, 0, 4).equals("NSFE")) {
            if (nfe.load(buf, size, type) == 0) {
                nfe.close();
                return null;
            }
        } else {
            nfe.close();
            return null;
        }
        return nfe;
    }

    /**
     * EXPSOUND structure is set by NSF*_Init(), NESAPU structure is already set
     * when these functions are called.
     */
    private void init() {

        if ((videoSystem & 0x3) == 0) {
            pal = false;
        } else if ((videoSystem & 0x3) == 1) {
            pal = true;
        }

        if ((soundChip & 4) != 0) {
            exWRam = new byte[32768 + 8192];
        } else {
            exWRam = new byte[8192];
        }

        cpu = new X6502(ram, pal, exWRam);

        apu = new NesApu(cpu);

        cpu.power();
        apu.power();

        cpu.setReader(0x0000, 0x1fff, amlReader, ram);
        cpu.setWriter(0x0000, 0x1fff, amlWriter, ram);

        doReset = 1;

        cart = new NesCart();

        if ((soundChip & 4) != 0) {
            cart.setupPRG(0, exWRam, 32768 + 8192, true);
            cart.setPrg32(0x6000, 0);
            cart.setPrg8(0xe000, 4);
            Arrays.fill(exWRam, 0, 32768 + 8192, (byte) 0x00);
            cpu.setWriter(0x6000, 0xdfff, cart.cartWriter, cart);
            cpu.setReader(0x6000, 0xffff, cart.cartReader, cart);
logger.log(Level.DEBUG, "here 1");
        } else {
            Arrays.fill(exWRam, 0, 8192, (byte) 0x00);
            cpu.setReader(0x6000, 0x7fff, cart.cartReader, cart);
            cpu.setWriter(0x6000, 0x7fff, cart.cartWriter, cart);

            cart.setupPRG(0, nsfData, ((nsfMaxBank + 1) * 4096), false);
            cart.setupPRG(1, exWRam, 8192, true);

            cart.setPrg8r(1, 0x6000, 0);
            cpu.setReader(0x8000, 0xffff, cart.cartReader, cart);
logger.log(Level.DEBUG, "here 2 *");
        }

        bsOn = 0;
        for (int i = 0; i < 8; i++) {
            bsOn |= bankSwitch[i];
        }

        if (bsOn == 0) {
            for (int i = (loadAddr & 0xf000); i < 0x10000; i += 0x1000) {
                setBank(i, (i - (loadAddr & 0xf000)) >> 12);
            }
        }

        cpu.setWriter(0x2000, 0x3fff, null, null);
        cpu.setReader(0x2000, 0x3fff, null, null);

        cpu.setWriter(0x5ff6, 0x5fff, nsfWriter, this);

        cpu.setWriter(0x3ff0, 0x3fff, nsfWriter, this);

        // We don't support expansion sound chips in PAL mode. It would be EL
        // BUTTO PAINO to do so, and probably slow, since it would require
        // having two resamplers going at once(on some chips; Festalon takes
        // advantage of the fact that chips like the VRC7 are run at a clock
        // speed "compatible" with the NES' CPU clock speed, as far as
        // resampling is concerned).

        totalChannels = 5;

        if (!pal) {
            ExpSound[] expSounds = {
                new vrc6(apu), new vrc7(apu), new fds(apu), new mmc5(apu), new n106(apu), new ay(apu)
            };

            for (int i = 0; i < expSounds.length; i++) {
                if ((soundChip & (1 << i)) != 0) {
                    apu.addExp(expSounds[i]);
                    totalChannels += expSounds[i].channels;
                }
            }
        }

        currentSong = startingSong;
        songReload = (byte) 0xff;
    }

    /** */
    private Writer nsfWriter = new Writer() {
        public void exec(int address, int value) {
            if (address >= 0x5ff6 && address <= 0x5fff) { // Bank-switching
                if (address <= 0x5ff7 && (soundChip & 4) == 0) {
                    return; // Only valid in FDS mode
                }
                if (bsOn == 0) {
                    return;
                }
                address &= 0xf;
                setBank(address * 4096, value);
            }
        }
    };

    /** */
    private void clri() {

        for (int i = 0; i < 0x800; i++) {
            ram[i] = 0x00;
        }

        cpu.writeDm(0x4015, 0x00);

        for (int i = 0; i < 0x14; i++) {
            cpu.writeDm(0x4000 + i, 0);
        }
        cpu.writeDm(0x4015, 0xf);

        if ((soundChip & 4) != 0) {
            cpu.writeDm(0x4017, 0xc0); // FDS BIOS writes $C0
            cpu.writeDm(0x4089, 0x80);
            cpu.writeDm(0x408a, 0xe8);
        } else {
            for (int i = 0; i < 8192; i++) {
                exWRam[i] = 0x00;
            }
            cpu.writeDm(0x4017, 0xc0);
            cpu.writeDm(0x4017, 0xc0);
            cpu.writeDm(0x4017, 0x40);
        }
        if (bsOn != 0) {
            for (int i = 0; i < 8; i++) {
                if ((soundChip & 4) != 0 && i >= 6) {
                    setBank(0x6000 + (i - 6) * 4096, bankSwitch[i]);
                }
                setBank(0x8000 + i * 4096, bankSwitch[i]);
            }
        }
    }

    /**
     * Selects current song.
     * @param which song number
     */
    public void controlSong(int which) {
        currentSong = which;
        songReload = (byte) 0xff;
    }

    /**
     * @param count [0]
     * @return wave
     */
    public float[] emulate(int[] count) {
        // Reset the stack if we're going to call the play routine or the init
        // routine.
        if (cpu.pc == 0x3800 || songReload != 0) {
            if (songReload != 0) {
logger.log(Level.DEBUG, "clri");
                clri();
            }

            cpu.s = 0xfd;
            ram[0x01ff] = 0x37;
            ram[0x01fe] = (byte) 0xff;
            cpu.x = cpu.y = cpu.a = 0;
            if (songReload != 0) {
                cpu.p = 4;
                cpu.x = pal ? 1 : 0;
                cpu.a = currentSong;
                cpu.pc = initAddr;
                songReload = 0;
            } else {
                cpu.pc = playAddr;
            }
        }

        if (pal) {
            cpu.run(apu, 312 * (256 + 85) - doodoo);
        } else {
            cpu.run(apu, 262 * (256 + 85) - doodoo);
        }
        doodoo ^= 1;

        cpu.hackSpeed(apu);

        count[0] = apu.emulateFlush();
        return apu.waveFinal;
    }

    /** */
    public void disable(int t) {
        apu.disable(t);
    }

    /** */
    private int load(byte[] buf, int size, int info_only) {
        try {
            byte[] nbuf = null;
            int bufP = 0; // TODO

            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(buf));
            dis.readInt();
            size -= 4;

            while (size != 0) {
                int chunk_size;
                byte[] tb = new byte[4];

                if (size < 4) {
                    return 0;
                }

                chunk_size = dis.readInt();
                size -= 4;
                if (size < 4) {
                    return 0;
                }

                dis.readFully(tb, 0, 4);
                size -= 4;

//logger.log(Level.TRACE, "Chunk: %.4s %d".formatted(tb, chunk_size));
                String t = new String(tb);
                if (t.equals("INFO")) {
                    if (chunk_size < 8) {
                        return 0;
                    }

                    loadAddr = dis.readUnsignedShort();
                    size -= 2;

                    initAddr = dis.readUnsignedShort();
                    size -= 2;

                    playAddr = dis.readUnsignedShort();
                    size -= 2;

                    videoSystem = dis.readUnsignedByte();
                    size--;
                    soundChip = dis.readUnsignedByte();
                    size--;

                    chunk_size -= 8;

                    if (chunk_size != 0) {
                        totalSongs = dis.readUnsignedByte();
                        size--;
                        chunk_size--;
                    } else {
                        totalSongs = 1;
                    }

                    if (chunk_size != 0) {
                        startingSong = dis.readUnsignedByte();
                        size--;
                        chunk_size--;
                    } else {
                        startingSong = 0;
                    }

                    songNames = new String[totalSongs];

                    songLengths = new int[totalSongs];
                    songFades = new int[totalSongs];
                    for (int i = 0; i < totalSongs; i++) {
                        songLengths[i] = -1;
                        songFades[i] = -1;
                    }
                } else if (t.equals("DATA")) {
                    nsfSize = chunk_size;
                    nbuf = buf;
                } else if (t.equals("BANK")) {
                    dis.readFully(bankSwitch, size, Math.min(chunk_size, 8));
                } else if (t.equals("NEND")) {
                    if (chunk_size == 0 && nbuf != null) {
                        nsfMaxBank = ((nsfSize + (loadAddr & 0xfff) + 4095) / 4096);
                        nsfMaxBank = uppow2(nsfMaxBank);

                        if (info_only == 0) {
                            nsfData = new byte[nsfMaxBank * 4096];
                            // return 0;
                            System.arraycopy(nbuf, 0, nsfData, loadAddr & 0xfff, nsfSize);

                            nsfRawData = nsfData;
                            int nsfRawDataP = loadAddr & 0xfff;
                            nsfRawDataSize = nsfSize;
                        }
                        nsfMaxBank--;
                        return 1;
                    } else {
                        return 0;
                    }
                } else if (t.equals("tlbl")) {
                    int songcount = 0;
                    if (totalSongs == 0) {
                        return 0; // Out of order chunk.
                    }
                    while (chunk_size > 0) {
                        int slen = buf.length;

                        songNames[songcount++] = new String(buf, bufP, slen);

                        bufP += slen + 1;
                        chunk_size -= slen + 1;
                    }
                } else if (t.equals("time")) {
                    int count = chunk_size / 4;
                    int ws = 0;
                    chunk_size -= count * 4;

                    while (count-- > 0) {
                        songLengths[ws] = dis.readInt();
//logger.log(Level.DEBUG, "%d".firmatted(fe.SongLengths[ws] / 1000));
                        ws++;
                    }
                } else if (t.equals("fade")) {
                    int count = chunk_size / 4;
                    int ws = 0;
                    chunk_size -= count * 4;

                    while (count-- > 0) {
                        songFades[ws] = dis.readInt();
//logger.log(Level.DEBUG, "%d".formatted(fe.SongFades[ws]));
                        ws++;
                    }
                } else if (t.equals("auth")) {
                    int which = 0;
                    while (chunk_size > 0) {
                        int slen = buf.length;

                        if (which == 0) {
                            gameName = new String(buf);
                        } else if (which == 1) {
                            artist = new String(buf);
                        } else if (which == 2) {
                            copyright = new String(buf);
                        } else if (which == 3) {
                            ripper = new String(buf);
                        }

                        which++;
                        bufP += slen + 1;
                        chunk_size -= slen + 1;
                    }
                } else if (tb[0] >= 'A' && tb[0] <= 'Z') { // Unrecognized
                    // mandatory chunk
//logger.log(Level.DEBUG, "unknown");
                    return 0;
                } else {
                    // mmm...store the unknown chunk in memory so it can be used
                    // by
                    // createNSFE()
                    // if necessary.
//logger.log(Level.DEBUG, "Boop: %.4s".firmatted(tb));
                    // NSFExtra = NSFExtra(NSFExtraSize + 8 + chunk_size);
                    System.arraycopy(buf, bufP - 8, nsfExtra, nsfExtraSize, 8 + chunk_size);
                    nsfExtraSize += 8 + chunk_size;
                }
                bufP += chunk_size;
                size -= chunk_size;
            }
            return 1;
        } catch (IOException e) {
logger.log(Level.TRACE, e.getMessage(), e);
            assert false;
            return 0;
        }
    }

    /** */
    private static int uppow2(int n) {
        for (int x = 31; x >= 0; x--) {
            if ((n & (1 << x)) != 0) {
                if ((1 << x) != n) {
                    return (1 << (x + 1));
                }
                break;
            }
        }
        return n;
    }

    /**
     * @param totalsize out
     */
    private byte[] createNSFE(int[] totalsize) {
        try {
            int cursize;
            ByteArrayOutputStream buffer;

            cursize = 0;
            buffer = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(buffer);

            dos.writeBytes("NSFE");
            dos.writeInt(0); // TODO all size

            dos.writeBytes("INFO");
            dos.writeInt(2 + 2 + 2 + 1 + 1 + 1 + 1);
            dos.writeShort(loadAddr);
            dos.writeShort(initAddr);
            dos.writeShort(playAddr);
            dos.writeByte(videoSystem);
            dos.writeByte(soundChip);
            dos.writeByte(totalSongs);
            dos.writeByte(startingSong);

            for (int i = 0; i < 8; i++) {
                if (bankSwitch[i] != 0) {
                    dos.writeBytes("BANK");
                    dos.writeInt(8);
                    dos.write(bankSwitch, 0, 8);
                    break;
                }
            }
            dos.writeBytes("DATA");
            dos.writeInt(nsfRawDataSize);
            dos.write(nsfRawData, 0, nsfRawDataSize);

            if (songLengths != null) {
                int max;

                for (max = totalSongs - 1; max >= 0; max--) {
                    if (songLengths[max] != -1) {
                        break;
                    }
                }

                if (max >= 0) {
                    dos.writeBytes("time");
                    dos.writeInt(4 * max);
                    for (int i = 0; i <= max; i++) {
                        dos.writeInt(songLengths[i]);
                    }
                }
            }

            if (songFades != null) {
                int max;

                for (max = totalSongs - 1; max >= 0; max--) {
                    if (songFades[max] != -1) {
                        break;
                    }
                }

                if (max >= 0) {
                    dos.writeBytes("fade");
                    dos.writeInt(4 * max);
                    for (int i = 0; i <= max; i++) {
                        dos.writeInt(songFades[i]);
                    }
                }
            }

            if (songNames != null) {
                dos.writeBytes("tlbl");
                dos.writeInt(0); // TODO
                for (int i = 0; i < totalSongs; i++) {
                    dos.write(songNames[i].getBytes());
                }
            }

            if (artist != null || gameName != null || copyright != null || ripper != null) {
                dos.writeBytes("auth");
                dos.writeInt(0); // TODO
                dos.write(gameName.getBytes());
                dos.write(artist.getBytes());
                dos.write(copyright.getBytes());
                dos.write(ripper.getBytes());
            }

            if (nsfExtra != null) {
                dos.write(nsfExtra, 0, nsfExtraSize);
            }

            dos.writeBytes("NEND");
            dos.writeInt(0); // TODO

            totalsize[0] = cursize;
            return buffer.toByteArray();

        } catch (IOException e) {
            assert false;
            return null;
        }
    }

    /**
     * @before should call {@link #setSound(int, int)}
     */
    public void setVolume(int volume) {
        apu.filter.soundVolume = volume;
    }

    /**
     * @before should call {@link #setSound(int, int)}
     */
    public int setLowpass(boolean on, int corner, int order) {
        return apu.filter.setLowpass(on, corner, order);
    }

    /** */
    public void setSound(int rate, int quality) {
//        if (apu.filter != null) {
//            apu.filter = null;
//        }
        apu.filter = new Filter(rate, cpu.pal ? X6502.PAL_CPU : X6502.NTSC_CPU, cpu.pal, quality);

        apu.waveFinalLen = rate / (cpu.pal ? 50 : 60) * 2; // * 2 for extra
        // room
//        if (apu.waveFinal != null) {
//            apu.waveFinal = null;
//        }
        apu.waveFinal = new float[apu.waveFinalLen];
    }
}
