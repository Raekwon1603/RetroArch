package com.retroarch.browser.retroactivity;

/**
 * Decodes the real ROM ammo HUD icons (missile, super missile, power bomb)
 * for the second screen. Same approach as SuperMetroidRomIcons /
 * ZeroMissionMinimapTiles, just GBA 4bpp tiles instead of SNES.
 *
 * Addresses from metroidret/mzm's database.json, verified against the real
 * ROM by rendering each one and comparing to actual gameplay footage.
 * Tiles are 2x 8x8 side by side (16x8), not stacked, and the palette bank
 * isn't the same as hud.c's OAM paletteNum (that's a GBA OBJ slot number,
 * not an index into this table) - found the real banks by trial and
 * comparing against footage: missile is bank 1, super missile/power bomb
 * are bank 2.
 */
final class ZeroMissionHudIcons {
    private ZeroMissionHudIcons() {}

    private static final int ADDR_HUD_PALETTE = 0x32BA08; // hud/common.pal
    private static final int ICON_TILE_COUNT = 2;
    private static final int BYTES_PER_TILE = 32;
    private static final int HUD_PALETTE_BANKS = 6;
    private static final int COLORS_PER_BANK = 16;

    private static final int ADDR_MISSILE_ICON_GFX = 0x330548; // hud/missile_hud_active.gfx
    private static final int MISSILE_ICON_PALETTE_BANK = 1;
    private static final int ADDR_SUPER_MISSILE_ICON_GFX = 0x3306C8; // hud/super_missile_hud_active.gfx
    private static final int SUPER_MISSILE_ICON_PALETTE_BANK = 2;
    private static final int ADDR_POWER_BOMB_ICON_GFX = 0x330848; // hud/power_bomb_hud_active.gfx
    private static final int POWER_BOMB_ICON_PALETTE_BANK = 2;

    // Each icon has its own colored backdrop baked in (black for missile,
    // yellow for super missile, pink for power bomb) that it treat as
    // transparent too, or it renders as a solid block instead of a clean
    // icon.
    private static final int MISSILE_BACKDROP_INDEX = 15;
    private static final int SUPER_MISSILE_BACKDROP_INDEX = 15;
    private static final int POWER_BOMB_BACKDROP_INDEX = 7;

    private static int[] decodeIcon(byte[] iconGfx, int[] palette, int paletteBank, int backdropIndex) {
        int w = 8 * ICON_TILE_COUNT, h = 8;
        int[] out = new int[w * h];
        int paletteBase = paletteBank * COLORS_PER_BANK;

        for (int tile = 0; tile < ICON_TILE_COUNT; tile++) {
            int tileOffset = tile * BYTES_PER_TILE;
            for (int row = 0; row < 8; row++) {
                int rowOffset = tileOffset + row * 4;
                for (int col = 0; col < 8; col++) {
                    int b = iconGfx[rowOffset + (col >> 1)] & 0xFF;
                    int colorIndex = (col & 1) == 0 ? (b & 0xF) : (b >> 4);
                    if (colorIndex == 0 || colorIndex == backdropIndex) continue;
                    out[row * w + tile * 8 + col] = palette[paletteBase + colorIndex];
                }
            }
        }
        return out;
    }

    private static int bgr555ToArgb(int c) {
        int r5 = c & 0x1F, g5 = (c >> 5) & 0x1F, b5 = (c >> 10) & 0x1F;
        int r = r5 * 255 / 31, g = g5 * 255 / 31, b = b5 * 255 / 31;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int[] decodePalette(byte[] paletteBytes) {
        int count = HUD_PALETTE_BANKS * COLORS_PER_BANK;
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            int off = i * 2;
            int c = (paletteBytes[off] & 0xFF) | ((paletteBytes[off + 1] & 0xFF) << 8);
            out[i] = bgr555ToArgb(c);
        }
        return out;
    }

    private static int[] decodeAt(byte[] rom, int[] palette, int addr, int paletteBank, int backdropIndex) {
        int gfxLen = ICON_TILE_COUNT * BYTES_PER_TILE;
        if (addr + gfxLen > rom.length) return null;
        byte[] iconGfx = new byte[gfxLen];
        System.arraycopy(rom, addr, iconGfx, 0, gfxLen);
        return decodeIcon(iconGfx, palette, paletteBank, backdropIndex);
    }

    /** 16x8 ARGB8888 per icon, any may be null if the ROM is too short. */
    static final class Icons {
        final int[] missile;
        final int[] superMissile;
        final int[] powerBomb;
        Icons(int[] missile, int[] superMissile, int[] powerBomb) {
            this.missile = missile;
            this.superMissile = superMissile;
            this.powerBomb = powerBomb;
        }
    }

    static Icons decodeAll(byte[] rom) {
        if (rom == null) return null;
        int palLen = HUD_PALETTE_BANKS * COLORS_PER_BANK * 2;
        if (ADDR_HUD_PALETTE + palLen > rom.length) return null;

        byte[] paletteBytes = new byte[palLen];
        System.arraycopy(rom, ADDR_HUD_PALETTE, paletteBytes, 0, palLen);
        int[] palette = decodePalette(paletteBytes);

        return new Icons(
                decodeAt(rom, palette, ADDR_MISSILE_ICON_GFX, MISSILE_ICON_PALETTE_BANK, MISSILE_BACKDROP_INDEX),
                decodeAt(rom, palette, ADDR_SUPER_MISSILE_ICON_GFX, SUPER_MISSILE_ICON_PALETTE_BANK, SUPER_MISSILE_BACKDROP_INDEX),
                decodeAt(rom, palette, ADDR_POWER_BOMB_ICON_GFX, POWER_BOMB_ICON_PALETTE_BANK, POWER_BOMB_BACKDROP_INDEX));
    }
}
