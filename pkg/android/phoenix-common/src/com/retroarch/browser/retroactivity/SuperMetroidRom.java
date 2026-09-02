package com.retroarch.browser.retroactivity;

import android.content.Context;
import android.os.ParcelFileDescriptor;

import com.libretro.common.vfs.VfsImplementationSaf;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

/**
 * Shared, whole-ROM-in-memory access for the dual-screen fork's second
 * screen (see super_metroid-android's docs/retroarch-fork-notes.md) - one
 * real place for the "read the loaded ROM file directly" plumbing that
 * SuperMetroidRomIcons.java originally had inline, now also used by
 * SuperMetroidRomMap.java for the (much larger) map tile/tilemap data.
 * Loading the whole ~3MB ROM into a byte[] once and sharing it is simpler
 * and cheaper than each decoder re-opening/re-seeking the file for its own
 * handful of regions.
 *
 * Assumes a headerless ROM file (no 512-byte copier header) - matches this
 * project's own confirmed 3145728-byte (exactly 3MB) Super Metroid Wide.smc.
 * LoROM address-to-file-offset formula copied verbatim from this project's
 * own src/sm_rtl.h (RomFixedPtr/RomPtr - same formula, called with a
 * compile-time vs. runtime address respectively there; this is the runtime
 * form, matching RomPtr).
 */
final class SuperMetroidRom {
    private SuperMetroidRom() {}

    static byte[] load(String romPath, Context context) {
        if (romPath == null) return null;
        if (romPath.startsWith("saf://"))
            return loadSaf(romPath, context);
        try (RandomAccessFile raf = new RandomAccessFile(romPath, "r")) {
            long len = raf.length();
            if (len <= 0 || len > 64 * 1024 * 1024) return null; // sanity bound, real SNES ROMs are a few MB
            byte[] rom = new byte[(int) len];
            raf.readFully(rom);
            return rom;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * ROMs loaded through Android's system file picker ("Open..." in Load
     * Content) come back as a "saf://<percent-encoded tree>/<path>" string,
     * RetroArch's own serialized form for SAF content (see
     * retro_vfs_path_split_saf in vfs_implementation_saf.c), not a real
     * filesystem path a RandomAccessFile can open. VfsImplementationSaf is
     * the same Java helper the native SAF VFS backend already calls into
     * (openSafFile, via JNI) to resolve a tree+path into a real fd, reused
     * here instead of re-deriving SAF document resolution by hand.
     */
    private static byte[] loadSaf(String romPath, Context context) {
        if (context == null) return null;
        String rest = romPath.substring("saf://".length());
        int slash = rest.indexOf('/');
        String encodedTree = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "/" : rest.substring(slash);
        String tree = safDecodeTree(encodedTree);
        if (tree == null) return null;

        int fd = VfsImplementationSaf.openSafFile(context.getContentResolver(), tree, path, true, false, false);
        if (fd < 0) return null;
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.adoptFd(fd);
             InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(4 * 1024 * 1024);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + n > 64 * 1024 * 1024) return null; // sanity bound, real SNES ROMs are a few MB
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /** Undo the %XX escaping retro_vfs_path_split_saf applies to the tree segment only. */
    private static String safDecodeTree(String encodedTree) {
        try {
            StringBuilder sb = new StringBuilder(encodedTree.length());
            for (int i = 0; i < encodedTree.length(); i++) {
                char c = encodedTree.charAt(i);
                if (c == '%' && i + 2 < encodedTree.length()) {
                    sb.append((char) Integer.parseInt(encodedTree.substring(i + 1, i + 3), 16));
                    i += 2;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** LoROM SNES-address -> file-offset formula (RomPtr/RomFixedPtr in sm_rtl.h/.c). */
    static int fileOffset(int snesAddr) {
        return (((snesAddr >>> 16) << 15) | (snesAddr & 0x7fff)) & 0x3fffff;
    }

    static int readU8(byte[] rom, int snesAddr) {
        return rom[fileOffset(snesAddr)] & 0xFF;
    }

    static int readU16(byte[] rom, int snesAddr) {
        int off = fileOffset(snesAddr);
        return (rom[off] & 0xFF) | ((rom[off + 1] & 0xFF) << 8);
    }

    /**
     * Reads a 3-byte little-endian far pointer (bank:addr, matching this
     * project's own LongPtr struct / Load24() - a 2-byte addr followed by a
     * 1-byte bank, read together as one 24-bit value) at snesAddr, and
     * returns the SNES address it points to (bank << 16 | addr).
     */
    static int readFarPointer(byte[] rom, int snesAddr) {
        int off = fileOffset(snesAddr);
        int addr = (rom[off] & 0xFF) | ((rom[off + 1] & 0xFF) << 8);
        int bank = rom[off + 2] & 0xFF;
        return (bank << 16) | addr;
    }
}
