package com.retroarch.browser.retroactivity;

/**
 * Computes the real vanilla "ITEMS COLLECTED xx%" value, for the dual-screen
 * fork's second screen (see super_metroid-android's
 * docs/retroarch-fork-notes.md).
 *
 * A straight port of that project's own real, working implementation -
 * src/second_screen.c's SM2_GetItemPercent, extracted from the real ROM
 * routine CalcItemPercentageCount (sm_8b.c:5214, ROM 0x8BE627 - the actual
 * code that computes the digits drawn on the ending screen). See that
 * function's own comment for why this is the vanilla formula, not Redux's
 * own different one, and why it's reimplemented as a pure numeric
 * computation rather than calling the real routine (which writes into VRAM
 * tilemap state as a side effect, not safe to call outside the real ending
 * cutscene).
 *
 * Needs both live WRAM (collected_items/collected_beams, and the 5 tank
 * values the ROM's own address table points at) and real ROM data (the
 * divisor/mask tables) - this fork has neither in one place the way the
 * original project's direct C access does, so both are read here via
 * RetroActivityCommon.nativeReadSystemRam and the loaded ROM bytes
 * (SuperMetroidRom.load) respectively.
 */
final class SuperMetroidItemPercent {
    private SuperMetroidItemPercent() {}

    // Real ROM addresses, copied verbatim from second_screen.c.
    private static final int ADDR_TANK_RAM_ADDRS = 0x8be70d; // uint16[5], live WRAM addresses of the 5 tank values
    private static final int ADDR_TANK_DIVISORS = 0x8be717;  // uint16[5] (low byte used), "per 1%" divisors
    private static final int ADDR_ITEM_MASKS = 0x8be721;     // uint16[11], collected_items bitmasks
    private static final int ADDR_BEAM_MASKS = 0x8be737;     // uint16[5], collected_beams bitmasks

    /**
     * @param rom Loaded ROM bytes (SuperMetroidRom.load).
     * @param collectedItems Live collected_items WRAM value (0x9A4).
     * @param collectedBeams Live collected_beams WRAM value (0x9A8).
     * @param tankValueReader Reads a live uint16 WRAM value at an arbitrary
     *                        offset (0-0x1FFF) - for the 5 tank-value reads
     *                        this needs at addresses the ROM table itself
     *                        specifies, not fixed offsets known ahead of
     *                        time.
     * @return 0-100+ (real vanilla item% can exceed 100 briefly with certain
     *         collection orders, same as the real game), or -1 if rom is
     *         null.
     */
    interface TankValueReader {
        int readUint16(int wramOffset);
    }

    static int compute(byte[] rom, int collectedItems, int collectedBeams, TankValueReader tankValueReader) {
        if (rom == null) return -1;

        int total = 0;
        for (int i = 4; i >= 0; i--) {
            int ramAddr = SuperMetroidRom.readU16(rom, ADDR_TANK_RAM_ADDRS + i * 2);
            int divisor = SuperMetroidRom.readU16(rom, ADDR_TANK_DIVISORS + i * 2) & 0xFF; // (uint8) cast in the original
            if (ramAddr < 0 || ramAddr >= 0x2000) continue; // matches RomPtr_RAM's own assert bound
            int value = tankValueReader.readUint16(ramAddr);
            total += divisor == 0 ? 0xFFFF : value / divisor; // SnesDivide
        }

        for (int i = 0; i < 11; i++) {
            int mask = SuperMetroidRom.readU16(rom, ADDR_ITEM_MASKS + i * 2);
            if ((mask & collectedItems) != 0) total++;
        }

        for (int i = 0; i < 5; i++) {
            int mask = SuperMetroidRom.readU16(rom, ADDR_BEAM_MASKS + i * 2);
            if ((mask & collectedBeams) != 0) total++;
        }

        return total;
    }
}
