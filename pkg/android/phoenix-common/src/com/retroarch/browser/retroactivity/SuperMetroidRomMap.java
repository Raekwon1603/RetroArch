package com.retroarch.browser.retroactivity;

/**
 * Decodes the real, ROM-accurate pause-menu map art for a given area,
 * directly from the loaded Super Metroid ROM file, for the dual-screen
 * fork's second screen (see super_metroid-android's
 * docs/retroarch-fork-notes.md).
 *
 * A straight Java port of that project's own real, working decoder -
 * src/second_screen.c's SM2_RenderAreaMap and the helpers it uses
 * (Snes4bppColorIndex, Snes15ToArgb, RomFixedPtr/RomPtr/Load24) - not a
 * reimplementation from scratch. That project reads live explored-tile
 * bits directly out of its own in-process WRAM (map_tiles_explored,
 * explored_map_tiles_saved); this fork has no such access (bsnes-hd runs
 * the ROM as an opaque black box - see docs/retroarch-fork-notes.md), so
 * the explored bits are passed in here (read via
 * RetroActivityCommon.nativeReadSystemRam) instead of read directly.
 */
final class SuperMetroidRomMap {
    private SuperMetroidRomMap() {}

    // Real ROM addresses, copied verbatim from second_screen.c.
    private static final int ADDR_PAUSE_MENU_MAP_TILEMAPS = 0x82964a; // LongPtr[8], one far pointer per area
    private static final int ADDR_PAUSE_MENU_MAP_DATA = 0x829717;     // uint16[8], one far pointer per area
    private static final int ADDR_MAP_TILE_GFX = 0xb68000;            // 768 tiles x 32 bytes, SNES 4bpp
    private static final int ADDR_PAUSE_SCREEN_PALETTES = 0xb6f000;   // 256 x BGR555
    private static final int MAP_TILE_COUNT = 768;

    static final int GRID_W = 64;
    static final int GRID_H = 32;

    private static final int UNEXPLORED_COLOR = 0xFF14141E;

    // Area 6 is Ceres (has its own real tilemap entry); only area 7 (unused
    // "debug" entry, never reached in normal play) falls back to Crateria's
    // data - matches RemapArea's own `(area >= 7) ? 0 : area`.
    private static int remapArea(int area) {
        return area >= 7 ? 0 : area;
    }

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

    // Same bit-unpacking as DecodeExploredGridFrom (second_screen.c) - the
    // 256-byte bitmap is laid out as two 32x32 "screen" blocks side by
    // side (standard SNES 64-wide BG layout), 4 bytes per row per half,
    // MSB-first per byte.
    private static void decodeExploredGrid(byte[] bits, boolean[] out) {
        for (int y = 0; y < GRID_H; y++) {
            for (int x = 0; x < GRID_W; x++) {
                int half = x >> 5;
                int col = (x >> 3) & 3;
                int byteIndex = col + half * 128 + 4 * y;
                int bit = 0x80 >> (x & 7);
                out[y * GRID_W + x] = (bits[byteIndex] & bit) != 0;
            }
        }
    }

    /**
     * Decodes the given area's real map art into an ARGB8888 pixel buffer,
     * GRID_W*8 x GRID_H*8 (512x256) - one buffer for the whole area, same
     * as SM2_RenderAreaMap's own g_map_px. Unexplored tiles (not walked,
     * and not covered by an owned Map Station's reveal data) draw as a
     * flat dark color, matching the real pause-menu map's own background.
     *
     * @param rom ROM bytes, from SuperMetroidRom.load().
     * @param area 0-7 (see second_screen.c's own area index convention).
     * @param exploredBits This area's live 256-byte explored-tile bitmap
     *                      (map_tiles_explored for the current area,
     *                      explored_map_tiles_saved's slice for any other).
     * @param haveMapStation Whether this area's Map Station item is owned
     *                        (map_station_byte_array[area] != 0) - the
     *                        reveal bitmap itself is real ROM data
     *                        (kPauseMenuMapData), fetched from rom directly,
     *                        same as GetMapStationGridForArea does.
     * @return ARGB8888 pixels, 512x256, or null if rom is null/too short.
     */
    static int[] renderAreaMap(byte[] rom, int area, byte[] exploredBits, boolean haveMapStation) {
        if (rom == null || area < 0 || area > 7) return null;

        int remappedArea = remapArea(area);
        int tilemapFarPtrAddr = ADDR_PAUSE_MENU_MAP_TILEMAPS + remappedArea * 3;
        int tilemapAddr = SuperMetroidRom.readFarPointer(rom, tilemapFarPtrAddr);
        int tilemapOffset = SuperMetroidRom.fileOffset(tilemapAddr);

        boolean[] explored = new boolean[GRID_W * GRID_H];
        decodeExploredGrid(exploredBits, explored);

        // Map Station reveal data - kPauseMenuMapData is a plain uint16[8]
        // table of bank-0x82-relative offsets (NOT 3-byte far pointers like
        // kPauseMenuMapTilemaps above), each pointing to that area's own
        // 256-byte reveal bitmap (same bit layout as the explored bitmap) -
        // matches RomPtr_82(kPauseMenuMapData[area]) exactly. Only fetched/
        // decoded when the station is actually owned, matching the real
        // vanilla gate (LoadPauseMenuMapTilemap, sm_82.c).
        boolean[] mapStation = null;
        if (haveMapStation) {
            int entryAddr = ADDR_PAUSE_MENU_MAP_DATA + remappedArea * 2;
            int bankRelativeOffset = SuperMetroidRom.readU16(rom, entryAddr);
            int mapStationAddr = 0x820000 | bankRelativeOffset; // RomPtr_82
            int mapStationOff = SuperMetroidRom.fileOffset(mapStationAddr);
            if (mapStationOff >= 0 && mapStationOff + 256 <= rom.length) {
                byte[] mapStationBits = new byte[256];
                System.arraycopy(rom, mapStationOff, mapStationBits, 0, 256);
                mapStation = new boolean[GRID_W * GRID_H];
                decodeExploredGrid(mapStationBits, mapStation);
            }
        }

        int[] out = new int[GRID_W * 8 * GRID_H * 8];

        for (int ty = 0; ty < GRID_H; ty++) {
            for (int tx = 0; tx < GRID_W; tx++) {
                int idx = ty * GRID_W + tx;
                boolean isExplored = explored[idx];
                boolean isMapStationOnly = !isExplored && mapStation != null && mapStation[idx];

                if (!isExplored && !isMapStationOnly) {
                    fillTilePixels(out, tx, ty, UNEXPLORED_COLOR);
                    continue;
                }

                // Tilemap entries are stored as two 32x32 "screen" blocks
                // side by side (standard SNES 64-wide BG layout), same
                // halving as the explored-bit packing but on whole 16-bit
                // entries.
                int half = tx >> 5;
                int i = half * 1024 + ty * 32 + (tx & 31);
                int entryOffset = tilemapOffset + i * 2;
                if (entryOffset < 0 || entryOffset + 1 >= rom.length) {
                    fillTilePixels(out, tx, ty, UNEXPLORED_COLOR);
                    continue;
                }
                int entry = (rom[entryOffset] & 0xFF) | ((rom[entryOffset + 1] & 0xFF) << 8);
                int tileIndex = entry & 0x3FF;
                int paletteRow = (entry >> 10) & 7;
                boolean flipX = (entry & 0x4000) != 0;
                boolean flipY = (entry & 0x8000) != 0;

                if (tileIndex >= MAP_TILE_COUNT) {
                    fillTilePixels(out, tx, ty, UNEXPLORED_COLOR);
                    continue;
                }

                int tileFileOffset = SuperMetroidRom.fileOffset(ADDR_MAP_TILE_GFX) + tileIndex * 32;
                int paletteFileOffset = SuperMetroidRom.fileOffset(ADDR_PAUSE_SCREEN_PALETTES) + paletteRow * 16 * 2;
                int base = ty * 8 * (GRID_W * 8) + tx * 8;

                for (int py = 0; py < 8; py++) {
                    int sy = flipY ? 7 - py : py;
                    for (int px = 0; px < 8; px++) {
                        int sx = flipX ? 7 - px : px;
                        int ci = snes4bppColorIndex(rom, tileFileOffset, sx, sy);
                        int colorOffset = paletteFileOffset + ci * 2;
                        int color15 = (rom[colorOffset] & 0xFF) | ((rom[colorOffset + 1] & 0xFF) << 8);
                        int argb = snes15ToArgb(color15);
                        if (isMapStationOnly) {
                            // Dim map-station-only (not-yet-visited) tiles,
                            // matching real vanilla's own visual
                            // distinction - halve each RGB channel, same
                            // simplification second_screen.c's own
                            // SM2_RenderAreaMap uses.
                            int a = argb & 0xFF000000;
                            int r = ((argb >> 16) & 0xFF) / 2;
                            int g = ((argb >> 8) & 0xFF) / 2;
                            int b = (argb & 0xFF) / 2;
                            argb = a | (r << 16) | (g << 8) | b;
                        }
                        out[base + py * (GRID_W * 8) + px] = argb;
                    }
                }
            }
        }
        return out;
    }

    private static void fillTilePixels(int[] out, int tx, int ty, int color) {
        int base = ty * 8 * (GRID_W * 8) + tx * 8;
        for (int py = 0; py < 8; py++)
            for (int px = 0; px < 8; px++)
                out[base + py * (GRID_W * 8) + px] = color;
    }
}
