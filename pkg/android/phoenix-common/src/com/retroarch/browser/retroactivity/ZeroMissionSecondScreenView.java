package com.retroarch.browser.retroactivity;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/**
 * Second-screen HP/ammo strip + room-shape map for the Metroid: Zero
 * Mission (GBA, mGBA core) dual-screen fork, mirroring
 * SuperMetroidSecondScreenView's HP/ammo strip layout but reading a
 * completely different game's memory - all addresses below were found and
 * confirmed live (not from any prior disassembly project the way Super
 * Metroid's offsets were): cross-checked against
 * https://datacrystal.tcrf.net/wiki/Metroid_Zero_Mission/RAM_map, which
 * independently lists the same HP/missile addresses, and directly verified
 * on-device via RetroArch's own READ_CORE_MEMORY network command against a
 * real Metroid - Zero Mission (USA) ROM through mGBA.
 *
 * Deliberately smaller in scope than SuperMetroidSecondScreenView for now:
 * HP/ammo strip + one room-shape map view, no items tab, no pins, no
 * pan/zoom, no world-map composite. mGBA never implements
 * retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM) (confirmed against its own
 * upstream source), so this reads through
 * RetroActivityCommon.nativeReadCoreMemoryMapped instead of
 * nativeReadSystemRam - a real core address (e.g. 0x03000000 for GBA
 * IWRAM), not an offset into a single memory blob.
 */
public class ZeroMissionSecondScreenView extends View {
    // Confirmed live: current area/room/door, IWRAM. Area: 0=Brinstar,
    // 1=Kraid, 2=Norfair, 3=Ridley, 4=Tourian, 5=Crateria, 6=Chozodia
    // (matches datacrystal's RAM map).
    private static final int OFF_AREA = 0x03000054;
    private static final int OFF_ROOM = 0x03000055;
    private static final int OFF_DOOR = 0x03000056;
    private static final int AREA_ROOM_DOOR_LENGTH = 3;

    // Confirmed live via controlled HP/missile transitions (99->69->1->21,
    // and 5->1 missiles after firing) - u16 LE each, matches datacrystal's
    // independently documented addresses exactly.
    private static final int OFF_MAX_HP = 0x03001530;
    private static final int OFF_MAX_MISSILES = 0x03001532;
    private static final int OFF_CUR_HP = 0x03001536;
    private static final int OFF_CUR_MISSILES = 0x03001538;
    // One read covering 0x1530-0x153A (10 bytes, max HP through current
    // missiles) - one native call instead of four.
    private static final int STATS_BLOCK_OFFSET = OFF_MAX_HP;
    private static final int STATS_BLOCK_LENGTH = (OFF_CUR_MISSILES + 2) - OFF_MAX_HP;

    // Confirmed live: Samus's own sub-pixel X/Y position, u16 LE, tracked
    // correctly across a rightward walk (672->867).
    private static final int OFF_SAMUS_X = 0x030013E6;
    private static final int OFF_SAMUS_Y = 0x030013E8;

    // Confirmed live: gMinimapX/gMinimapY (per mzm disassembly project,
    // metroidret/mzm on GitHub - a real, source-level reimplementation of
    // this game, not a guess), the player marker's cell in the current
    // area's 32x32 minimap grid. u8 each, tracked correctly across a room
    // transition (12,10 -> 13,12).
    private static final int OFF_MINIMAP_X = 0x03000059;
    private static final int OFF_MINIMAP_Y = 0x0300005A;

    // gDecompressedMinimapVisitedTiles (mzm disassembly:
    // include/structs/minimap.h) - u16[32*32], row-major (index = y*32+x),
    // current area only, reloaded fresh on every area transition. Low 10
    // bits = tile shape id (0x140 = background/unexplored, confirmed by the
    // disassembly's own MINIMAP_TILE_BACKGROUND constant); bits 12-15
    // non-zero = this cell has been explored/drawn. Verified live: decoded
    // shape matched a real in-game map screenshot's room layout.
    private static final int OFF_MINIMAP_DATA = 0x02034000;
    private static final int MINIMAP_SIZE = 32;
    private static final int MINIMAP_TILE_BACKGROUND = 0x140;
    private static final int MINIMAP_DATA_LENGTH = MINIMAP_SIZE * MINIMAP_SIZE * 2;

    private static final int COL_BG = Color.rgb(30, 33, 44);
    private static final int COL_PANEL_BG = Color.rgb(38, 42, 56);
    private static final int COL_BORDER_DARK = Color.rgb(58, 64, 86);
    private static final int COL_ENERGY_PIP = Color.rgb(204, 71, 145);
    private static final int COL_ACCENT = Color.rgb(255, 158, 68);
    private static final int COL_SAMUS_DOT = Color.rgb(255, 70, 70);
    private static final int COL_MAP_ROOM = Color.rgb(90, 150, 220);
    private static final int COL_MAP_EXPLORED_DIM = Color.rgb(50, 70, 95);

    private static final int PIPS_PER_ROW = 7;

    private final RetroActivityCommon activity;
    private final Paint paint = new Paint();
    private final RectF rect = new RectF();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            invalidate();
            postOnAnimation(this);
        }
    };

    public ZeroMissionSecondScreenView(Context context, RetroActivityCommon activity) {
        super(context);
        this.activity = activity;
        paint.setAntiAlias(false);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        uiHandler.post(pollRunnable);
    }

    @Override
    protected void onDetachedFromWindow() {
        uiHandler.removeCallbacks(pollRunnable);
        super.onDetachedFromWindow();
    }

    private static int readUint16LE(byte[] block, int offsetInBlock) {
        int lo = block[offsetInBlock] & 0xFF;
        int hi = block[offsetInBlock + 1] & 0xFF;
        return lo | (hi << 8);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(COL_BG);

        float w = getWidth(), h = getHeight();

        byte[] statsBlock = activity.nativeReadCoreMemoryMapped(STATS_BLOCK_OFFSET, STATS_BLOCK_LENGTH);
        if (statsBlock == null || statsBlock.length < STATS_BLOCK_LENGTH) {
            drawLogo(canvas, w, h);
            return;
        }

        int maxHp = readUint16LE(statsBlock, OFF_MAX_HP - STATS_BLOCK_OFFSET);
        int maxMissiles = readUint16LE(statsBlock, OFF_MAX_MISSILES - STATS_BLOCK_OFFSET);
        int curHp = readUint16LE(statsBlock, OFF_CUR_HP - STATS_BLOCK_OFFSET);
        int curMissiles = readUint16LE(statsBlock, OFF_CUR_MISSILES - STATS_BLOCK_OFFSET);

        float stripH = h * 0.16f;
        drawStatusStrip(canvas, w, stripH, curHp, maxHp, curMissiles, maxMissiles);

        RectF mapArea = new RectF(w * 0.03f, stripH + h * 0.02f, w * 0.97f, h * 0.97f);
        drawMap(canvas, mapArea);
    }

    private void drawLogo(Canvas canvas, float w, float h) {
        float cx = w / 2f, cy = h / 2f;
        float textHeight = w * 0.11f;
        int fadedAccent = Color.argb(70, Color.red(COL_ACCENT), Color.green(COL_ACCENT), Color.blue(COL_ACCENT));
        float pixelSize = PixelFont.pixelSizeForHeight(textHeight);
        PixelFont.drawText(canvas, "METROID", cx, cy - textHeight / 2f, pixelSize, fadedAccent, Paint.Align.CENTER);
    }

    private void drawStatusStrip(Canvas canvas, float w, float stripH, int curHp, int maxHp, int curMissiles, int maxMissiles) {
        rect.set(0, 0, w, stripH);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COL_PANEL_BG);
        canvas.drawRect(rect, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setColor(COL_BORDER_DARK);
        canvas.drawRect(rect, paint);

        float pad = stripH * 0.12f;
        float pipSize = (stripH - pad * 3f) / 2f;
        float pipGap = pipSize * 0.25f;

        int maxTanks = Math.max(1, (maxHp + 98) / 99); // Zero Mission: 99 HP per energy tank
        int filledTanks = maxHp == 0 ? 0 : Math.round((float) curHp / maxHp * maxTanks);
        float x = pad;
        float y = pad;
        for (int i = 0; i < maxTanks && i < PIPS_PER_ROW * 2; i++) {
            float px = x + (i % PIPS_PER_ROW) * (pipSize + pipGap);
            float py = y + (i / PIPS_PER_ROW) * (pipSize + pipGap);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(i < filledTanks ? COL_ENERGY_PIP : COL_BORDER_DARK);
            canvas.drawRect(px, py, px + pipSize, py + pipSize, paint);
        }

        String hpText = curHp + "/" + maxHp;
        float hpTextSize = PixelFont.pixelSizeForHeight(stripH * 0.32f);
        PixelFont.drawText(canvas, hpText, w * 0.45f, pad, hpTextSize, COL_ENERGY_PIP, Paint.Align.LEFT);

        String missileText = "MSL " + curMissiles + "/" + maxMissiles;
        PixelFont.drawText(canvas, missileText, w * 0.45f, pad + stripH * 0.5f, hpTextSize, COL_ACCENT, Paint.Align.LEFT);
    }

    private void drawMap(Canvas canvas, RectF area) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COL_PANEL_BG);
        canvas.drawRoundRect(area, area.height() * 0.02f, area.height() * 0.02f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setColor(COL_BORDER_DARK);
        canvas.drawRoundRect(area, area.height() * 0.02f, area.height() * 0.02f, paint);

        byte[] mapData = activity.nativeReadCoreMemoryMapped(OFF_MINIMAP_DATA, MINIMAP_DATA_LENGTH);
        byte[] posData = activity.nativeReadCoreMemoryMapped(OFF_MINIMAP_X, 2);
        if (mapData == null || mapData.length < MINIMAP_DATA_LENGTH) return;

        float cellW = area.width() / MINIMAP_SIZE;
        float cellH = area.height() / MINIMAP_SIZE;
        float cellSize = Math.min(cellW, cellH);
        float originX = area.left + (area.width() - cellSize * MINIMAP_SIZE) / 2f;
        float originY = area.top + (area.height() - cellSize * MINIMAP_SIZE) / 2f;

        for (int gy = 0; gy < MINIMAP_SIZE; gy++) {
            for (int gx = 0; gx < MINIMAP_SIZE; gx++) {
                int idx = (gy * MINIMAP_SIZE + gx) * 2;
                int tile = readUint16LE(mapData, idx);
                int tileId = tile & 0x3FF;
                boolean explored = (tile & 0xF000) != 0;
                if (!explored || tileId == MINIMAP_TILE_BACKGROUND) continue;

                float cx = originX + gx * cellSize;
                float cy = originY + gy * cellSize;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(COL_MAP_ROOM);
                canvas.drawRect(cx + 1, cy + 1, cx + cellSize - 1, cy + cellSize - 1, paint);
            }
        }

        if (posData != null && posData.length >= 2) {
            int mapX = posData[0] & 0xFF;
            int mapY = posData[1] & 0xFF;
            if (mapX < MINIMAP_SIZE && mapY < MINIMAP_SIZE) {
                float px = originX + mapX * cellSize + cellSize / 2f;
                float py = originY + mapY * cellSize + cellSize / 2f;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(COL_SAMUS_DOT);
                canvas.drawCircle(px, py, cellSize * 0.4f, paint);
            }
        }
    }
}
