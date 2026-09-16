package com.retroarch.browser.retroactivity;

/**
 * Decodes the real, ROM-accurate minimap tile graphics for Metroid: Zero
 * Mission, for the dual-screen fork's second screen - same idea as
 * SuperMetroidRomIcons (decode real ROM art instead of drawing placeholder
 * shapes), but for GBA 4bpp tiles instead of SNES 2bpp, and for the live
 * minimap grid's room/wall/corner tile art instead of HUD ammo icons.
 *
 * Addresses below are real GBA ROM offsets (linear address space, unlike
 * SNES LoROM - no bank-shift formula needed), taken from the metroidret/mzm
 * disassembly project's own database.json asset table (US ROM region),
 * cross-checked against a real "Metroid - Zero Mission (USA).gba" file:
 * both entries' byte ranges read back plausible 4bpp tile data and a
 * plausible BGR555 palette at these exact offsets. TILE_COUNT specifically
 * was verified by scanning tile-by-tile past the originally-assumed 160
 * boundary: shape-tile data continues consistently up through tile 319,
 * then cleanly transitions to a different (font/label glyph) byte pattern
 * starting exactly at tile 320 (ROM offset 0x4108C4) - confirming the real
 * table is 320 tiles, matching MINIMAP_TILE_BACKGROUND (0x140) in
 * ZeroMissionSecondScreenView.
 *
 * Per that disassembly's src/minimap.c (MinimapDraw and the
 * MinimapCopyTile*Gfx family): each live minimap tile word's low 10 bits
 * are a tile index into this graphics table (32 bytes/tile, standard GBA
 * 4bpp 8x8 format), bits 10-11 select one of 4 flip orientations, and bits
 * 12-15 (when non-zero, i.e. explored) select which of 5 16-color palette
 * banks in sMinimapTilesPal to use for that specific tile - the palette is
 * authored per-tile in the compressed per-area source data, not computed
 * from room/area id, which is how a single area (e.g. Chozodia) ends up
 * with two visually distinct sub-region colors on its own map.
 */
final class ZeroMissionMinimapTiles {
    private ZeroMissionMinimapTiles() {}

    // REAL BUG FIXED HERE (see class doc above): TILE_COUNT was 160,
    // undercounting the real 320-tile table by half -- any live minimap
    // cell with a real, explored shape tileId in [0xA0, 0x13F] silently
    // decoded as an all-transparent tile (decodeTile's own out-of-range
    // guard below), rendering as invisible gaps in the map.
    private static final int ADDR_TILES_GFX = 0x40E0C4; // 10240 bytes = 320 tiles x 32 bytes
    private static final int ADDR_TILES_PAL = 0x411360; // 160 bytes = 5 banks x 16 colors x 2 bytes
    private static final int TILE_COUNT = 320;
    private static final int BYTES_PER_TILE = 32; // 8 rows x 4 bytes/row (2 pixels/byte, 4bpp)
    private static final int PALETTE_BANKS = 5;
    private static final int COLORS_PER_BANK = 16;

    /**
     * Decodes one 8x8 tile as ARGB8888 pixels (row-major, index 0 = top-left).
     * Color index 0 is always transparent (alpha 0), matching the GBA
     * convention SuperMetroidRomIcons.decodeHudTile also follows - index 0
     * in a 4bpp/2bpp indexed tile is the backdrop/transparent slot, never a
     * real drawn color.
     *
     * @param tileGfx  Raw sMinimapTilesGfx bytes (TILE_COUNT * BYTES_PER_TILE).
     * @param palette  Decoded ARGB8888 palette, PALETTE_BANKS * COLORS_PER_BANK entries (see decodePalette).
     * @param tileId   0-based tile index (already masked to the low 10 bits by the caller;
     *                 values >= TILE_COUNT - e.g. the 0x141-0x15E area-name label range,
     *                 or 0x140 background - have no graphic here and return an all-transparent tile).
     * @param paletteBank 0-4, which of the 5 palette banks to use.
     * @param flip     0=normal, 1=X-flip, 2=Y-flip, 3=XY-flip (matches the live tile word's own bits 10-11).
     */
    static int[] decodeTile(byte[] tileGfx, int[] palette, int tileId, int paletteBank, int flip) {
        int[] out = new int[8 * 8];
        if (tileId < 0 || tileId >= TILE_COUNT) return out; // background/label-range ids: blank
        if (paletteBank < 0 || paletteBank >= PALETTE_BANKS) paletteBank = 0;

        int tileOffset = tileId * BYTES_PER_TILE;
        boolean flipX = (flip & 1) != 0;
        boolean flipY = (flip & 2) != 0;
        int paletteBase = paletteBank * COLORS_PER_BANK;

        for (int row = 0; row < 8; row++) {
            int srcRow = flipY ? 7 - row : row;
            int rowOffset = tileOffset + srcRow * 4;
            for (int col = 0; col < 8; col++) {
                int srcCol = flipX ? 7 - col : col;
                int b = tileGfx[rowOffset + (srcCol >> 1)] & 0xFF;
                int colorIndex = (srcCol & 1) == 0 ? (b & 0xF) : (b >> 4);
                if (colorIndex == 0) continue; // transparent
                out[row * 8 + col] = palette[paletteBase + colorIndex];
            }
        }
        return out;
    }

    // GBA BGR555 -> Android's 0xAARRGGBB int format (bit layout is
    // identical to SNES's own BGR555 - same conversion as
    // SuperMetroidRomIcons.snes15ToArgb, duplicated locally rather than
    // shared since these two classes decode otherwise-unrelated ROM
    // formats and a shared helper would be a false-coupling for a
    // three-line function).
    private static int bgr555ToArgb(int c) {
        int r5 = c & 0x1F, g5 = (c >> 5) & 0x1F, b5 = (c >> 10) & 0x1F;
        int r = r5 * 255 / 31, g = g5 * 255 / 31, b = b5 * 255 / 31;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int[] decodePalette(byte[] paletteBytes) {
        int count = PALETTE_BANKS * COLORS_PER_BANK;
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            int off = i * 2;
            int c = (paletteBytes[off] & 0xFF) | ((paletteBytes[off + 1] & 0xFF) << 8);
            out[i] = bgr555ToArgb(c);
        }
        return out;
    }

    /** { tileGfx bytes, decoded ARGB palette int[] }, or null if rom is too short/null - see decodeAll. */
    static final class Decoded {
        final byte[] tileGfx;
        final int[] palette;
        Decoded(byte[] tileGfx, int[] palette) {
            this.tileGfx = tileGfx;
            this.palette = palette;
        }
    }

    /**
     * Reads sMinimapTilesGfx + sMinimapTilesPal from an already-loaded
     * whole-ROM byte array (SuperMetroidRom.load - despite the name, that
     * loader is entirely game-agnostic, just "read the whole ROM file into
     * a byte[]"; only that class's own romFileOffset LoROM formula is
     * SNES-specific, which GBA's linear address space has no equivalent
     * need for - this class indexes rom[] directly by ROM offset instead)
     * and decodes the palette.
     * Safe to call once and cache: this is static ROM data, identical for
     * the life of a loaded ROM. Returns null if the ROM is missing or too
     * short to contain these fixed offsets (i.e. not really this game/ROM).
     */
    static Decoded decodeAll(byte[] rom) {
        if (rom == null) return null;
        int gfxLen = TILE_COUNT * BYTES_PER_TILE;
        int palLen = PALETTE_BANKS * COLORS_PER_BANK * 2;
        if (ADDR_TILES_GFX + gfxLen > rom.length) return null;
        if (ADDR_TILES_PAL + palLen > rom.length) return null;

        byte[] tileGfx = new byte[gfxLen];
        System.arraycopy(rom, ADDR_TILES_GFX, tileGfx, 0, gfxLen);

        byte[] paletteBytes = new byte[palLen];
        System.arraycopy(rom, ADDR_TILES_PAL, paletteBytes, 0, palLen);

        return new Decoded(tileGfx, decodePalette(paletteBytes));
    }
}
