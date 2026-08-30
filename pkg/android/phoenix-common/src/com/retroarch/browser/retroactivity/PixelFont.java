package com.retroarch.browser.retroactivity;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.HashMap;
import java.util.Map;

/**
 * Copied as-is from super_metroid-android's own
 * android/app/src/main/java/com/raekwon/supermetroid/PixelFont.java (that
 * project's real, working second-screen font renderer) - see this fork's
 * docs/retroarch-fork-notes.md. A small hand-drawn bitmap-style font used to
 * match that app's retro SNES-menu look on MetroidArch's own second screen,
 * without needing SM's own ROM font decoded (not available here - this
 * fork only reads raw WRAM bytes via nativeReadSystemRam, no access to the
 * decompile's native game-logic layer the original app's GameState has).
 * Each glyph is a 5-wide x 7-tall bitmask (one byte per row, bit 4..0 = left
 * to right column) covering A-Z, 0-9, and the punctuation actually used.
 * Unknown characters draw as blank space rather than throwing.
 */
public final class PixelFont {
    private PixelFont() {}

    private static final int GLYPH_W = 5, GLYPH_H = 7;
    private static final Map<Character, byte[]> GLYPHS = new HashMap<>();

    private static void g(char c, String... rows) {
        byte[] bits = new byte[GLYPH_H];
        for (int y = 0; y < GLYPH_H && y < rows.length; y++) {
            String row = rows[y];
            byte b = 0;
            for (int x = 0; x < GLYPH_W && x < row.length(); x++) {
                if (row.charAt(x) != ' ') b |= (byte) (1 << (GLYPH_W - 1 - x));
            }
            bits[y] = b;
        }
        GLYPHS.put(c, bits);
    }

    static {
        g('A', "  #  ", " # # ", "#   #", "#   #", "#####", "#   #", "#   #");
        g('B', "#### ", "#   #", "#   #", "#### ", "#   #", "#   #", "#### ");
        g('C', " ####", "#    ", "#    ", "#    ", "#    ", "#    ", " ####");
        g('D', "#### ", "#   #", "#   #", "#   #", "#   #", "#   #", "#### ");
        g('E', "#####", "#    ", "#    ", "#### ", "#    ", "#    ", "#####");
        g('F', "#####", "#    ", "#    ", "#### ", "#    ", "#    ", "#    ");
        g('G', " ####", "#    ", "#    ", "# ###", "#   #", "#   #", " ####");
        g('H', "#   #", "#   #", "#   #", "#####", "#   #", "#   #", "#   #");
        g('I', "#####", "  #  ", "  #  ", "  #  ", "  #  ", "  #  ", "#####");
        g('J', "  ###", "   # ", "   # ", "   # ", "   # ", "#  # ", " ##  ");
        g('K', "#   #", "#  # ", "# #  ", "##   ", "# #  ", "#  # ", "#   #");
        g('L', "#    ", "#    ", "#    ", "#    ", "#    ", "#    ", "#####");
        g('M', "#   #", "## ##", "# # #", "# # #", "#   #", "#   #", "#   #");
        g('N', "#   #", "##  #", "# # #", "# # #", "#  ##", "#   #", "#   #");
        g('O', " ### ", "#   #", "#   #", "#   #", "#   #", "#   #", " ### ");
        g('P', "#### ", "#   #", "#   #", "#### ", "#    ", "#    ", "#    ");
        g('Q', " ### ", "#   #", "#   #", "#   #", "# # #", "#  # ", " ## #");
        g('R', "#### ", "#   #", "#   #", "#### ", "# #  ", "#  # ", "#   #");
        g('S', " ####", "#    ", "#    ", " ### ", "    #", "    #", "#### ");
        g('T', "#####", "  #  ", "  #  ", "  #  ", "  #  ", "  #  ", "  #  ");
        g('U', "#   #", "#   #", "#   #", "#   #", "#   #", "#   #", " ### ");
        g('V', "#   #", "#   #", "#   #", "#   #", "#   #", " # # ", "  #  ");
        g('W', "#   #", "#   #", "#   #", "# # #", "# # #", "## ##", "#   #");
        g('X', "#   #", "#   #", " # # ", "  #  ", " # # ", "#   #", "#   #");
        g('Y', "#   #", "#   #", " # # ", "  #  ", "  #  ", "  #  ", "  #  ");
        g('Z', "#####", "    #", "   # ", "  #  ", " #   ", "#    ", "#####");
        g('0', " ### ", "#   #", "#  ##", "# # #", "##  #", "#   #", " ### ");
        g('1', "  #  ", " ##  ", "  #  ", "  #  ", "  #  ", "  #  ", "#####");
        g('2', " ### ", "#   #", "    #", "   # ", "  #  ", " #   ", "#####");
        g('3', "#### ", "    #", "    #", "  ## ", "    #", "    #", "#### ");
        g('4', "   # ", "  ## ", " # # ", "#  # ", "#####", "   # ", "   # ");
        g('5', "#####", "#    ", "#    ", "#### ", "    #", "    #", "#### ");
        g('6', " ### ", "#    ", "#    ", "#### ", "#   #", "#   #", " ### ");
        g('7', "#####", "    #", "   # ", "  #  ", " #   ", " #   ", " #   ");
        g('8', " ### ", "#   #", "#   #", " ### ", "#   #", "#   #", " ### ");
        g('9', " ### ", "#   #", "#   #", " ####", "    #", "    #", " ### ");
        g('.', "     ", "     ", "     ", "     ", "     ", "  #  ", "  #  ");
        g('/', "    #", "    #", "   # ", "  #  ", " #   ", "#    ", "#    ");
        g('-', "     ", "     ", "     ", "#####", "     ", "     ", "     ");
        g(':', "     ", "  #  ", "  #  ", "     ", "  #  ", "  #  ", "     ");
        g('\'', " #   ", " #   ", "     ", "     ", "     ", "     ", "     ");
        g('%', "#   #", "#  # ", "   # ", "  #  ", " #   ", " #  #", "#   #");
        g(' ', "     ", "     ", "     ", "     ", "     ", "     ", "     ");
    }

    private static final Paint scratchPaint = new Paint();
    private static final RectF scratchRect = new RectF();

    public static float measureWidth(String text, float pixelSize) {
        String up = text.toUpperCase();
        return (up.length() * (GLYPH_W + 1) - 1) * pixelSize;
    }

    public static float glyphHeight(float pixelSize) {
        return GLYPH_H * pixelSize;
    }

    public static float pixelSizeForHeight(float targetHeight) {
        return targetHeight / GLYPH_H;
    }

    public static void drawText(Canvas canvas, String text, float x, float y, float pixelSize,
                                 int color, Paint.Align align) {
        String up = text.toUpperCase();
        float totalW = measureWidth(up, pixelSize);
        float startX = x;
        if (align == Paint.Align.CENTER) startX = x - totalW / 2f;
        else if (align == Paint.Align.RIGHT) startX = x - totalW;

        scratchPaint.setColor(color);
        scratchPaint.setStyle(Paint.Style.FILL);
        scratchPaint.setAntiAlias(false);

        float cx = startX;
        for (int i = 0; i < up.length(); i++) {
            byte[] bits = GLYPHS.get(up.charAt(i));
            if (bits != null) {
                for (int row = 0; row < GLYPH_H; row++) {
                    int b = bits[row];
                    for (int col = 0; col < GLYPH_W; col++) {
                        if ((b & (1 << (GLYPH_W - 1 - col))) == 0) continue;
                        float px = cx + col * pixelSize, py = y + row * pixelSize;
                        scratchRect.set(px, py, px + pixelSize, py + pixelSize);
                        canvas.drawRect(scratchRect, scratchPaint);
                    }
                }
            }
            cx += (GLYPH_W + 1) * pixelSize;
        }
    }
}
