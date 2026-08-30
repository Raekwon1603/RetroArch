package com.retroarch.browser.retroactivity;

import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Decodes the real, ROM-accurate gameplay-HUD icons (missile, super
 * missile, power bomb) directly from the loaded Super Metroid ROM file, for
 * the dual-screen fork's second screen (see
 * super_metroid-android's docs/retroarch-fork-notes.md).
 *
 * This is a straight Java port of that project's own real, working
 * decoder - src/second_screen.c's SM2_RenderMissileIcon/
 * SM2_RenderSuperMissileIcon/SM2_RenderPowerBombIcon and the helpers they
 * use (Snes4bppColorIndex's 2bpp sibling Snes2bppColorIndex, Snes15ToArgb,
 * RomFixedPtr's LoROM address formula) - not a reimplementation from
 * scratch. That project reaches these bytes via direct C pointers into an
 * in-memory ROM buffer (g_rom, loaded by its own platform layer); this
 * fork has no such buffer (bsnes-hd beta owns ROM loading internally, as an
 * opaque black box - see docs/retroarch-fork-notes.md's WRAM findings for
 * the same reason nativeReadSystemRam exists), so this reads the identical
 * bytes directly from the ROM file on disk instead, via
 * RetroActivityCommon.nativeGetContentPath() to find it. Same real bytes
 * either way - a ROM file's contents don't change once loaded, so reading
 * from disk instead of a loaded core's private copy produces identical
 * decoded output.
 */
final class SuperMetroidRomIcons {
    private SuperMetroidRomIcons() {}

    // Real SNES addresses, copied verbatim from second_screen.c.
    private static final long ADDR_HUD_TILE_GFX = 0x9ab200L; // BG3 char data, 2bpp
    private static final long ADDR_HUD_PALETTE = 0x9a8000L;  // full 256-color initial palette
    private static final int HUD_TILE_COUNT = 512; // 0x2000 bytes / 16 bytes-per-2bpp-tile

    // kHudTilemaps_Missiles22 in second_screen.c - copied verbatim from
    // sm_80.c's own kHudTilemaps_Missiles literal array. Entries 0-5 back
    // the 3x2 missile icon; 6-9 the 2x2 super missile icon; 10-13 the 2x2
    // power bomb icon.
    private static final int[] HUD_TILEMAPS_MISSILES_22 = {
            0x344b, 0x3449, 0x744b, 0x344c, 0x344a, 0x744c, 0x3434, 0x7434, 0x3435, 0x7435,
            0x3436, 0x7436, 0x3437, 0x7437, 0x3438, 0x7438, 0x3439, 0x7439, 0x343a, 0x743a,
            0x343b, 0x743b,
    };

    /**
     * LoROM SNES-address -> file-offset formula, copied verbatim from
     * sm_rtl.h's RomFixedPtr: {@code (((addr >> 16) << 15) | (addr & 0x7fff)) & 0x3fffff}.
     * Assumes a headerless ROM file (no 512-byte copier header) - matches
     * this project's own confirmed 3145728-byte (exactly 3MB, no header)
     * Super Metroid Wide.smc.
     */
    private static long romFileOffset(long snesAddr) {
        return (((snesAddr >> 16) << 15) | (snesAddr & 0x7fff)) & 0x3fffffL;
    }

    private static int snes2bppColorIndex(byte[] tile, int tileOffset, int px, int py) {
        int bit = 7 - px;
        int p0 = tile[tileOffset + py * 2] & 0xFF;
        int p1 = tile[tileOffset + py * 2 + 1] & 0xFF;
        return ((p0 >> bit) & 1) | (((p1 >> bit) & 1) << 1);
    }

    // SNES BGR555 -> Android's 0xAARRGGBB int format, same as Snes15ToArgb.
    private static int snes15ToArgb(int c) {
        int r5 = c & 0x1F, g5 = (c >> 5) & 0x1F, b5 = (c >> 10) & 0x1F;
        int r = r5 * 255 / 31, g = g5 * 255 / 31, b = b5 * 255 / 31;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * Decodes a 3x2-tile (24x16px) HUD icon (the missile icon layout) from
     * four HUD_TILEMAPS_MISSILES_22 entries starting at entryOffset,
     * row-major (matches AddMissilesToHudTilemap's own
     * hud_tilemap[10,11,12,42,43,44] placement, one tilemap row = 32 tiles
     * down). Transparent (alpha 0) where the decoded color index is 0,
     * same as the real HUD (color index 0 is background/transparent).
     */
    private static int[] decode3x2Icon(byte[] tileGfx, short[] palette, int entryOffset) {
        int[] out = new int[24 * 16];
        for (int ty = 0; ty < 2; ty++) {
            for (int tx = 0; tx < 3; tx++) {
                int entry = HUD_TILEMAPS_MISSILES_22[entryOffset + ty * 3 + tx];
                decodeHudTile(tileGfx, palette, entry, out, 24, tx * 8, ty * 8);
            }
        }
        return out;
    }

    /**
     * Decodes a 2x2-tile (16x16px) HUD icon (super missile / power bomb
     * layout) from four entries starting at entryOffset, row-major
     * [topLeft, topRight, bottomLeft, bottomRight] - matches
     * AddToTilemapInner's own hud_tilemap[v2, v2+1, v2+32, v2+33]
     * placement.
     */
    private static int[] decode2x2Icon(byte[] tileGfx, short[] palette, int entryOffset) {
        int[] out = new int[16 * 16];
        for (int ty = 0; ty < 2; ty++) {
            for (int tx = 0; tx < 2; tx++) {
                int entry = HUD_TILEMAPS_MISSILES_22[entryOffset + ty * 2 + tx];
                decodeHudTile(tileGfx, palette, entry, out, 16, tx * 8, ty * 8);
            }
        }
        return out;
    }

    private static void decodeHudTile(byte[] tileGfx, short[] palette, int entry,
                                       int[] out, int outStride, int destX, int destY) {
        int tileIndex = entry & 0x3FF;
        int paletteRow = (entry >> 10) & 7;
        boolean flipX = (entry & 0x4000) != 0;
        boolean flipY = (entry & 0x8000) != 0;
        if (tileIndex >= HUD_TILE_COUNT) return;

        int tileOffset = tileIndex * 16;
        for (int py = 0; py < 8; py++) {
            int sy = flipY ? 7 - py : py;
            for (int px = 0; px < 8; px++) {
                int sx = flipX ? 7 - px : px;
                int ci = snes2bppColorIndex(tileGfx, tileOffset, sx, sy);
                if (ci == 0) continue;
                int color15 = palette[paletteRow * 4 + ci] & 0xFFFF;
                out[(destY + py) * outStride + destX + px] = snes15ToArgb(color15);
            }
        }
    }

    /**
     * Reads exactly the bytes needed for all three icons (one HUD tile
     * bank read, one palette read) from the ROM file at romPath, decodes
     * all three, and returns { missileIcon(24x16), superMissileIcon(16x16),
     * powerBombIcon(16x16) } as ARGB8888 int[] pixel arrays - or null if
     * the ROM file can't be read (missing, permission issue, wrong size).
     * Safe to call once and cache the result: this is static ROM data, it
     * never changes for the life of a loaded ROM.
     */
    static int[][] decodeAmmoIcons(String romPath) {
        if (romPath == null) return null;
        try (RandomAccessFile raf = new RandomAccessFile(romPath, "r")) {
            long tileGfxOffset = romFileOffset(ADDR_HUD_TILE_GFX);
            long paletteOffset = romFileOffset(ADDR_HUD_PALETTE);

            byte[] tileGfx = new byte[HUD_TILE_COUNT * 16]; // 0x2000 bytes
            raf.seek(tileGfxOffset);
            raf.readFully(tileGfx);

            byte[] paletteBytes = new byte[256 * 2]; // 256 colors, 2 bytes each
            raf.seek(paletteOffset);
            raf.readFully(paletteBytes);
            short[] palette = new short[256];
            for (int i = 0; i < 256; i++) {
                palette[i] = (short) ((paletteBytes[i * 2] & 0xFF) | ((paletteBytes[i * 2 + 1] & 0xFF) << 8));
            }

            int[] missileIcon = decode3x2Icon(tileGfx, palette, 0);
            int[] superMissileIcon = decode2x2Icon(tileGfx, palette, 6);
            int[] powerBombIcon = decode2x2Icon(tileGfx, palette, 10);

            return new int[][] { missileIcon, superMissileIcon, powerBombIcon };
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Same as decodeAmmoIcons(String), but from an already-loaded whole-ROM
     * byte array (SuperMetroidRom.load) instead of opening the file itself -
     * for callers (SuperMetroidSecondScreenView) that also need the ROM
     * bytes for other decoders (SuperMetroidRomMap) and want to load the
     * ~3MB file only once, not once per decoder.
     */
    static int[][] decodeAmmoIcons(byte[] rom) {
        if (rom == null) return null;
        int tileGfxOffset = SuperMetroidRom.fileOffset((int) ADDR_HUD_TILE_GFX);
        int paletteOffset = SuperMetroidRom.fileOffset((int) ADDR_HUD_PALETTE);
        int tileGfxLen = HUD_TILE_COUNT * 16;
        int paletteLen = 256 * 2;
        if (tileGfxOffset < 0 || tileGfxOffset + tileGfxLen > rom.length) return null;
        if (paletteOffset < 0 || paletteOffset + paletteLen > rom.length) return null;

        byte[] tileGfx = new byte[tileGfxLen];
        System.arraycopy(rom, tileGfxOffset, tileGfx, 0, tileGfxLen);

        short[] palette = new short[256];
        for (int i = 0; i < 256; i++) {
            int off = paletteOffset + i * 2;
            palette[i] = (short) ((rom[off] & 0xFF) | ((rom[off + 1] & 0xFF) << 8));
        }

        int[] missileIcon = decode3x2Icon(tileGfx, palette, 0);
        int[] superMissileIcon = decode2x2Icon(tileGfx, palette, 6);
        int[] powerBombIcon = decode2x2Icon(tileGfx, palette, 10);
        return new int[][] { missileIcon, superMissileIcon, powerBombIcon };
    }
}
