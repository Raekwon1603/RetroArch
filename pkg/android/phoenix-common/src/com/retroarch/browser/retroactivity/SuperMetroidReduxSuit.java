package com.retroarch.browser.retroactivity;

/**
 * Renders the real Redux Suit equipment-screen graphic - Samus's full-color
 * body art, correct pose and suit color for whatever's actually equipped -
 * for the dual-screen fork's second screen (see super_metroid-android's
 * docs/retroarch-fork-notes.md).
 *
 * A straight Java port of that project's own real, working decoder -
 * src/second_screen.c's SM2_RenderReduxSuit - using the literal tile/
 * palette/tilemap data ported verbatim in ReduxSuitData.java (see that
 * file's own comment on where it really came from - a different, separate
 * ROM, not the player's own loaded Super Metroid ROM, so unlike every other
 * decoder in this fork, this one needs no ROM file access at all).
 */
final class SuperMetroidReduxSuit {
    private SuperMetroidReduxSuit() {}

    static final int TILES_W = 8, TILES_H = 17; // kWireframeTilesW/H in second_screen.c
    static final int PX_W = TILES_W * 8, PX_H = TILES_H * 8; // 64x136

    private static int snes4bppColorIndex(byte[] tile, int tileOffset, int px, int py) {
        int bit = 7 - px;
        int p0 = tile[tileOffset + py * 2] & 0xFF;
        int p1 = tile[tileOffset + py * 2 + 1] & 0xFF;
        int p2 = tile[tileOffset + 16 + py * 2] & 0xFF;
        int p3 = tile[tileOffset + 16 + py * 2 + 1] & 0xFF;
        return ((p0 >> bit) & 1) | (((p1 >> bit) & 1) << 1) | (((p2 >> bit) & 1) << 2) | (((p3 >> bit) & 1) << 3);
    }

    private static int snes15ToArgb(int c) {
        int r5 = c & 0x1F, g5 = (c >> 5) & 0x1F, b5 = (c >> 10) & 0x1F;
        int r = r5 * 255 / 31, g = g5 * 255 / 31, b = b5 * 255 / 31;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /**
     * @param equippedItemsValue Live equipped_items WRAM value (0x9A2).
     * @return ARGB8888 pixels, PX_W x PX_H (64x136).
     */
    static int[] render(int equippedItemsValue) {
        int[] out = new int[PX_W * PX_H];

        // Same 4-variant pose selection as vanilla's own wireframe
        // (WriteSamusWireframeTilemap): key = equipped_items & 0x101
        // (bit 0x001=Varia, bit 0x100=Gravity), order none/Gravity/Varia/both -
        // kKeys in second_screen.c's SM2_RenderReduxSuit.
        int[] keys = { 0x0, 0x100, 0x1, 0x101 };
        int key = equippedItemsValue & 0x101;
        int variant = 0;
        while (variant < 4 && keys[variant] != key) variant++;
        if (variant == 4) variant = 0;

        // Suit body color: a SEPARATE, additional live palette swap on top
        // of whichever pose tilemap is selected - row BODY_PALETTE_ROW
        // (the suit body's own palette row in every pose variant) gets
        // overridden with one of 3 fixed suit-color palettes based on
        // equipped_items bit 0x0020 (Gravity, checked first - matches how
        // Gravity visually overrides Varia in-game when both are worn) or
        // 0x0001 (Varia), defaulting to the plain Power Suit palette.
        short[] bodyPalette = ReduxSuitData.PALETTE_POWER;
        if ((equippedItemsValue & 0x0020) != 0) bodyPalette = ReduxSuitData.PALETTE_GRAVITY;
        else if ((equippedItemsValue & 0x0001) != 0) bodyPalette = ReduxSuitData.PALETTE_VARIA;

        short[] tilemap = ReduxSuitData.TILEMAPS[variant];
        int entryIdx = 0;
        for (int row = 0; row < TILES_H; row++) {
            for (int col = 0; col < TILES_W; col++) {
                int entry = tilemap[entryIdx++] & 0xFFFF;
                int tileIndex = entry & 0x3FF;
                int paletteRow = (entry >> 10) & 7;
                boolean flipX = (entry & 0x4000) != 0;
                boolean flipY = (entry & 0x8000) != 0;
                if (tileIndex >= ReduxSuitData.TILE_COUNT || paletteRow >= ReduxSuitData.PALETTE_ROW_COUNT) continue;

                int tileOffset = tileIndex * 32;
                for (int py = 0; py < 8; py++) {
                    int sy = flipY ? 7 - py : py;
                    for (int px = 0; px < 8; px++) {
                        int sx = flipX ? 7 - px : px;
                        int ci = snes4bppColorIndex(ReduxSuitData.TILES, tileOffset, sx, sy);
                        if (ci == 0) continue;
                        // bodyPalette is a single already-indexed 16-color
                        // row (no *16 offset); ReduxSuitData.PALETTE is the
                        // full 4-row table, indexed by paletteRow*16+ci.
                        int color15 = paletteRow == ReduxSuitData.BODY_PALETTE_ROW
                                ? (bodyPalette[ci] & 0xFFFF)
                                : (ReduxSuitData.PALETTE[paletteRow * 16 + ci] & 0xFFFF);
                        out[(row * 8 + py) * PX_W + col * 8 + px] = snes15ToArgb(color15);
                    }
                }
            }
        }
        return out;
    }
}
