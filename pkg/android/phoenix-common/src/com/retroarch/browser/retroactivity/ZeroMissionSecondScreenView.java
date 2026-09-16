package com.retroarch.browser.retroactivity;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
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
    // bits = real ROM tile-graphic id (see ZeroMissionMinimapTiles -
    // decoded and blitted as actual room/wall/corner art now, not a
    // placeholder filled square); bits 10-11 = flip; bits 12-15 = which of
    // 5 palette banks (non-zero also means "explored/drawn" - 0x140 itself,
    // MINIMAP_TILE_BACKGROUND, always has every bit clear). Verified live
    // against a real in-game map screenshot and this project's own
    // metroidret/mzm disassembly clone.
    //
    // Reads gDecompressedMinimapData (the static, always-fully-populated
    // per-area shape - CallLZ77UncompWram straight from ROM on area load,
    // per pause_screen.c:2688-2694), not gDecompressedMinimapVisitedTiles
    // (0x02034000, the "as explored so far" copy Super Metroid-style
    // per-room exploration would use) - a real, live-explored-only view
    // isn't achievable here without also reading gEquipment's
    // downloadedMapStatus bitflag to replicate MinimapSetDownloadedTiles's
    // own Map-Station-reveal logic exactly (minimap.c:675-727), which
    // needs more struct-offset verification than this fork currently has
    // confirmed. Showing the full static shape unconditionally is the
    // simpler, deliberate tradeoff for now: on an in-progress save this
    // will show more than you've actually walked through (dimmer, e.g.
    // areas your own Map Station reveal would show); on a 100%-explored
    // save (or after grabbing an area's Map Station) it matches the real
    // in-game map exactly, which is what this was tuned against.
    private static final int OFF_MINIMAP_DATA = 0x02034800;
    private static final int MINIMAP_SIZE = 32;
    private static final int MINIMAP_TILE_BACKGROUND = 0x140;
    private static final int MINIMAP_DATA_LENGTH = MINIMAP_SIZE * MINIMAP_SIZE * 2;
    private static final int TILE_PX = 8; // sMinimapTilesGfx's own native tile size

    private static final int COL_BG = Color.rgb(30, 33, 44);
    private static final int COL_PANEL_BG = Color.rgb(38, 42, 56);
    private static final int COL_BORDER_DARK = Color.rgb(58, 64, 86);
    private static final int COL_ENERGY_PIP = Color.rgb(204, 71, 145);
    private static final int COL_ACCENT = Color.rgb(255, 158, 68);
    private static final int COL_SAMUS_DOT = Color.rgb(255, 70, 70);

    private static final int PIPS_PER_ROW = 7;

    private final RetroActivityCommon activity;
    private final Paint paint = new Paint();
    private final Paint bitmapPaint = new Paint();
    private final RectF rect = new RectF();
    private final Rect srcRect = new Rect();
    private final Rect dstRect = new Rect();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            invalidate();
            postOnAnimation(this);
        }
    };

    // Decoded once and cached - real ROM asset data, never changes for the
    // life of a loaded ROM (see SuperMetroidRomIcons's own comment on the
    // same pattern). Decoding runs on a background thread, not inline in
    // onDraw/ensureTilesLoaded, for the same ANR-avoidance reason
    // SuperMetroidSecondScreenView's ensureIconsLoaded documents - real ROM
    // file I/O on the very first layout/draw pass risks tripping Android's
    // ANR watchdog if done synchronously on the UI thread.
    private volatile ZeroMissionMinimapTiles.Decoded minimapTiles;
    private volatile boolean tilesLoadInFlight = false;

    // The current area's fully-composited map, rebuilt only when the raw
    // grid data actually changes (compared byte-for-byte against
    // lastMapData) rather than every single onDraw call - 32x32 real tile
    // blits (1024 decodeTile calls) every frame would be wasted work when
    // nothing on the map has changed since the last poll tick, which is
    // most ticks.
    private Bitmap mapBitmap;
    private byte[] lastMapData;

    public ZeroMissionSecondScreenView(Context context, RetroActivityCommon activity) {
        super(context);
        this.activity = activity;
        paint.setAntiAlias(false);
        bitmapPaint.setAntiAlias(false);
        bitmapPaint.setFilterBitmap(false); // crisp pixel-art scaling, no blur
    }

    private void ensureTilesLoaded() {
        if (minimapTiles != null || tilesLoadInFlight) return;
        tilesLoadInFlight = true;
        new Thread(() -> {
            String romPath = activity.nativeGetContentPath();
            byte[] rom = SuperMetroidRom.load(romPath, activity);
            ZeroMissionMinimapTiles.Decoded decoded = ZeroMissionMinimapTiles.decodeAll(rom);
            if (decoded != null) {
                uiHandler.post(() -> minimapTiles = decoded);
            }
            tilesLoadInFlight = false;
        }, "ZeroMissionRomLoad").start();
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

        ensureTilesLoaded();

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

    /** Rebuilds mapBitmap from mapData if it actually changed since the last call, real tile art per cell. */
    private void ensureMapBitmapUpToDate(byte[] mapData) {
        if (lastMapData != null && java.util.Arrays.equals(lastMapData, mapData)) return;
        lastMapData = mapData.clone();

        if (mapBitmap == null) {
            mapBitmap = Bitmap.createBitmap(MINIMAP_SIZE * TILE_PX, MINIMAP_SIZE * TILE_PX, Bitmap.Config.ARGB_8888);
        }
        int[] canvasPixels = new int[MINIMAP_SIZE * TILE_PX * MINIMAP_SIZE * TILE_PX];
        int stride = MINIMAP_SIZE * TILE_PX;

        for (int gy = 0; gy < MINIMAP_SIZE; gy++) {
            for (int gx = 0; gx < MINIMAP_SIZE; gx++) {
                int idx = (gy * MINIMAP_SIZE + gx) * 2;
                int tile = readUint16LE(mapData, idx);
                int tileId = tile & 0x3FF;
                int flip = (tile >> 10) & 0x3;
                // Real bug fixed here: this used to treat "palette nibble
                // == 0" as "unexplored", but the actual game (minimap.c's
                // own MinimapCopyTile*Gfx family - "palette = *tmp >> 0xC",
                // used verbatim as the palette bank with no special-casing)
                // draws bank-0 tiles completely normally - bank 0 is a
                // real, valid, frequently-used palette (confirmed on a
                // real 100%-explored save: large swaths of Chozodia's
                // authored map data use bank 0 and rendered as a large
                // missing chunk of the map before this fix). The one real
                // sentinel for "not drawn" is tileId == MINIMAP_TILE_BACKGROUND
                // (0x140) itself - MinimapUpdateForExploredTiles's own
                // reset path (minimap.c:699,722) always sets *dst = 0x140
                // wholesale (tileId AND palette both zeroed) for a tile
                // that isn't explored, never leaves a real shape id behind
                // with palette 0 to mean "not explored yet".
                int paletteBank = (tile >> 12) & 0xF;
                // REAL BUG FIXED HERE: this gate (tileId >= MINIMAP_TILE_BACKGROUND,
                // i.e. >= 0x140) was fine on its own, but ZeroMissionMinimapTiles'
                // own graphics table only actually covered tiles 0x000-0x09F
                // (TILE_COUNT was wrongly 160, not the real 320) - so real,
                // explored shape tiles with ids in [0xA0, 0x13F] passed this
                // gate but then silently decoded as blank/transparent in
                // decodeTile's own out-of-range check, rendering as gaps in
                // the map. Fixed at the source (ZeroMissionMinimapTiles.TILE_COUNT),
                // confirmed against a real ROM's tile data. This gate itself
                // is correct as-is (MINIMAP_TILE_BACKGROUND == 0x140 == the
                // real table size, so ">=" alone already covers the redundant
                // "== MINIMAP_TILE_BACKGROUND" case).
                if (tileId >= MINIMAP_TILE_BACKGROUND) continue;

                int[] tilePixels = ZeroMissionMinimapTiles.decodeTile(
                        minimapTiles.tileGfx, minimapTiles.palette, tileId, paletteBank, flip);
                int destX0 = gx * TILE_PX, destY0 = gy * TILE_PX;
                for (int py = 0; py < TILE_PX; py++) {
                    int destRow = (destY0 + py) * stride + destX0;
                    int srcRow = py * TILE_PX;
                    System.arraycopy(tilePixels, srcRow, canvasPixels, destRow, TILE_PX);
                }
            }
        }
        mapBitmap.setPixels(canvasPixels, 0, stride, 0, 0, stride, MINIMAP_SIZE * TILE_PX);
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
        if (mapData == null || mapData.length < MINIMAP_DATA_LENGTH || minimapTiles == null) return;

        ensureMapBitmapUpToDate(mapData);

        float cellW = area.width() / MINIMAP_SIZE;
        float cellH = area.height() / MINIMAP_SIZE;
        float cellSize = Math.min(cellW, cellH);
        float originX = area.left + (area.width() - cellSize * MINIMAP_SIZE) / 2f;
        float originY = area.top + (area.height() - cellSize * MINIMAP_SIZE) / 2f;

        srcRect.set(0, 0, MINIMAP_SIZE * TILE_PX, MINIMAP_SIZE * TILE_PX);
        dstRect.set(Math.round(originX), Math.round(originY),
                Math.round(originX + cellSize * MINIMAP_SIZE), Math.round(originY + cellSize * MINIMAP_SIZE));
        canvas.drawBitmap(mapBitmap, srcRect, dstRect, bitmapPaint);

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
