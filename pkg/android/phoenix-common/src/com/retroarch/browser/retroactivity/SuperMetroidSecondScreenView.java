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
import android.view.MotionEvent;
import android.view.View;

/**
 * Second-screen HP/ammo strip for the dual-screen Super Metroid fork of
 * RetroArch (see super_metroid-android's docs/emulator-core-migration.md
 * and docs/retroarch-fork-notes.md). Styled to match that project's own
 * real, working second screen (see its README's
 * docs/screenshots/second-screen-hp-ammo.png) - the same dark panel, energy
 * tank pips, and missile/super/power-bomb counts, laid out the same way as
 * MapStatusView.java's drawHealthAmmoStrip there. Missile/super missile/
 * power bomb icons are real, ROM-decoded pixel art (see
 * SuperMetroidRomIcons.java), not placeholders - decoded straight from the
 * loaded ROM file, same bytes and same decode logic as that project's own
 * SM2_RenderMissileIcon and friends in src/second_screen.c.
 *
 * Polls once a second on the UI thread via Handler.postDelayed, same as
 * before - a second screen showing HP/ammo does not need 60fps updates.
 */
public class SuperMetroidSecondScreenView extends View {
    // Real SNES WRAM offsets, ported from super_metroid-android's
    // src/variables.h.
    private static final int OFF_HEALTH = 0x9C2;
    private static final int OFF_MAX_HEALTH = 0x9C4;
    private static final int OFF_MISSILES = 0x9C6;
    private static final int OFF_MAX_MISSILES = 0x9C8;
    private static final int OFF_SUPER_MISSILES = 0x9CA;
    private static final int OFF_MAX_SUPER_MISSILES = 0x9CC;
    private static final int OFF_POWER_BOMBS = 0x9CE;
    private static final int OFF_MAX_POWER_BOMBS = 0x9D0;
    private static final int OFF_HUD_ITEM_INDEX = 0x9D2;
    // Two more real WRAM fields SM2_SetSelectedAmmo (second_screen.c) also
    // clears on every selection change - both must be zeroed alongside
    // hud_item_index itself, or the selection gets silently wiped a couple
    // of real frames later (see that function's own long comment on why -
    // a real, confirmed-on-device ~30ms select-then-deselect bug otherwise).
    private static final int OFF_SAMUS_AUTO_CANCEL_HUD_ITEM_INDEX = 0xA04;
    private static final int OFF_HUD_AUTO_CANCEL_FLAG = 0x9EA;
    // hud_item_index values (second_screen.c's own comment on
    // SM2_SetSelectedAmmo): 0=none, 1=Missiles, 2=Supers, 3=PBs (4/5 are
    // Grapple/X-Ray, not reachable from this strip, same as that app's own
    // AMMO_SLOTS only covering the first three).
    private static final int AMMO_NONE = 0;
    private static final int[] AMMO_SLOTS = { 1, 2, 3 };
    // One read covering the whole 0x9C2-0x9D4 block (18 bytes, through
    // hud_item_index) - one JNI call instead of nine. Was only sized
    // through OFF_MAX_POWER_BOMBS - a real bug (ArrayIndexOutOfBoundsException,
    // crashed on launch) once hud_item_index's own read was added without
    // extending this to actually cover it.
    private static final int BLOCK_OFFSET = OFF_HEALTH;
    private static final int BLOCK_LENGTH = (OFF_HUD_ITEM_INDEX + 2) - OFF_HEALTH;

    // Map-related WRAM offsets, also ported from variables.h - separate
    // reads since they sit in different regions than the HP/ammo block
    // above.
    private static final int OFF_HAS_AREA_MAP = 0x789;
    private static final int OFF_AREA_INDEX = 0x79F;
    private static final int OFF_ROOM_X_ON_MAP = 0x7A1;
    private static final int OFF_ROOM_Y_ON_MAP = 0x7A3;
    private static final int OFF_ROOM_WIDTH_BLOCKS = 0x7A5;
    private static final int OFF_ROOM_HEIGHT_BLOCKS = 0x7A7;
    // One read covering 0x789-0x7A9 (34 bytes, has_area_map through the end
    // of room_height_in_blocks).
    private static final int ROOM_BLOCK_OFFSET = OFF_HAS_AREA_MAP;
    private static final int ROOM_BLOCK_LENGTH = (OFF_ROOM_HEIGHT_BLOCKS + 2) - OFF_HAS_AREA_MAP;

    private static final int OFF_MAP_TILES_EXPLORED = 0x7F7; // 256-byte bitmap, current area only
    private static final int MAP_TILES_EXPLORED_LENGTH = 256;

    private static final int OFF_SAMUS_X_POS = 0xAF6;
    private static final int OFF_SAMUS_Y_POS = 0xAFA;
    private static final int SAMUS_POS_BLOCK_OFFSET = OFF_SAMUS_X_POS;
    private static final int SAMUS_POS_BLOCK_LENGTH = (OFF_SAMUS_Y_POS + 2) - OFF_SAMUS_X_POS;

    private static final int OFF_MAP_STATION_BYTE_ARRAY = 0xD908; // 8 bytes, one per area
    private static final int MAP_STATION_BYTE_ARRAY_LENGTH = 8;

    private static final int OFF_GAME_STATE = 0x998;
    private static final int GAME_STATE_LENGTH = 2;

    // Same palette as MapStatusView.java's own COL_* constants.
    private static final int COL_BG = Color.rgb(30, 33, 44);
    private static final int COL_PANEL_BG = Color.rgb(38, 42, 56);
    private static final int COL_BORDER_DARK = Color.rgb(58, 64, 86);
    private static final int COL_DIM_GRAY = Color.rgb(105, 110, 128);
    private static final int COL_ENERGY_PIP = Color.rgb(204, 71, 145);
    private static final int COL_ACCENT = Color.rgb(255, 158, 68);
    private static final int COL_SAMUS_DOT = Color.rgb(255, 70, 70);

    private static final int PIPS_PER_ROW = 7;
    private static final int MAX_STATUS_STRIP_PIPS = PIPS_PER_ROW * 2;

    private final RetroActivityCommon activity;
    private final Paint paint = new Paint();
    private final RectF rect = new RectF();
    private final Rect srcRect = new Rect();
    private final Rect dstRect = new Rect();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    // Was a 1-second, then a 100ms, Handler.postDelayed poll - both still
    // visibly lagged behind rapid changes (missile-spamming, fast damage)
    // on real on-device testing. The real cost that forced those
    // compromises has since been fixed at the source instead of worked
    // around here: the patched bsnes-hd core's retro_get_memory_data used
    // to refill its entire 128KB WRAM shadow buffer - 128K individual bus
    // reads - on every single call; it now refills once per emulated frame
    // instead (see docs/retroarch-fork-notes.md and that core's
    // libretro.cpp, smwide_refresh_wram_shadow/retro_run), so reading it
    // from here is now a cheap, already-fresh pointer return regardless of
    // how often this polls. Real per-frame polling via postOnAnimation
    // (ties to the display's vsync/Choreographer, not a busy-loop) is safe
    // now that the expensive part isn't tied to poll rate any more.
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            invalidate();
            postOnAnimation(this);
        }
    };

    // Decoded once and cached - real ROM asset data, never changes for the
    // life of a loaded ROM (see SuperMetroidRomIcons.decodeAmmoIcons's own
    // comment). Decoding runs on a background thread, not inline in
    // onDraw/ensureIconsLoaded - a real, on-device-confirmed ANR
    // ("Activity pause timeout"/"Input dispatching timed out") happened
    // when this used to run synchronously on the UI thread the first time
    // the second-screen Presentation's content view attached: reading and
    // decoding real ROM file bytes (RandomAccessFile I/O, not instant) as
    // part of the very first layout/draw pass blocked the whole Activity's
    // lifecycle transition long enough to trip Android's ANR watchdog.
    // iconsLoadInFlight guards against starting a second decode thread on
    // every poll tick while the first one hasn't finished yet or genuinely
    // failed (a missing/unreadable ROM) - retried on the next poll either
    // way since "not ready this frame" and "will never succeed" look the
    // same from here, and a cheap failed RandomAccessFile open is not
    // worth tracking permanently as a terminal failure.
    private volatile Bitmap missileIconBitmap;
    private volatile Bitmap superMissileIconBitmap;
    private volatile Bitmap powerBombIconBitmap;
    // Whole ROM bytes, loaded once alongside the icons (same background
    // thread/ANR-avoidance reasoning) and cached for SuperMetroidRomMap's
    // per-frame area-map decode to reuse, rather than every map redraw
    // re-reading the ~3MB file from disk.
    private volatile byte[] romBytes;
    private volatile boolean iconsLoadInFlight = false;

    // Where each ammo icon+count group last drew, in the same view's own
    // coordinate space - onTouchEvent hit-tests against these, same pattern
    // as MapStatusView.java's own statusStripWeaponRects. Updated every
    // onDraw, so a tap always hits whatever was actually last shown, even
    // across a resize/rotation.
    private final RectF[] weaponRects = { new RectF(), new RectF(), new RectF() };

    public SuperMetroidSecondScreenView(Context context, RetroActivityCommon activity) {
        super(context);
        this.activity = activity;
        paint.setAntiAlias(false);
    }

    private void ensureIconsLoaded() {
        if (missileIconBitmap != null || iconsLoadInFlight) return;
        iconsLoadInFlight = true;
        new Thread(() -> {
            String romPath = activity.nativeGetContentPath();
            byte[] rom = SuperMetroidRom.load(romPath);
            int[][] icons = SuperMetroidRomIcons.decodeAmmoIcons(rom);
            if (rom != null && icons != null) {
                Bitmap missile = Bitmap.createBitmap(icons[0], 24, 16, Bitmap.Config.ARGB_8888);
                Bitmap superMissile = Bitmap.createBitmap(icons[1], 16, 16, Bitmap.Config.ARGB_8888);
                Bitmap powerBomb = Bitmap.createBitmap(icons[2], 16, 16, Bitmap.Config.ARGB_8888);
                uiHandler.post(() -> {
                    missileIconBitmap = missile;
                    superMissileIconBitmap = superMissile;
                    powerBombIconBitmap = powerBomb;
                    romBytes = rom;
                });
            }
            iconsLoadInFlight = false;
        }, "SuperMetroidRomLoad").start();
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

        float w0 = getWidth(), h0 = getHeight();
        // Menus/pause/cutscenes/death/demo attract-mode: don't draw the
        // HP/map view at all, just the logo screen - was drawing the real
        // view underneath a translucent dim overlay first, but that let
        // stale room/HP data (whatever was last on screen, or leftover
        // zeroed WRAM before any real session) visibly bleed through,
        // which read as a real bug rather than a deliberate subtle dim
        // (confirmed on-device: room outlines/Samus dot showing through
        // the "METROID" wordmark on the main menu). Only live gameplay
        // gets the real view now, matching what was actually asked for.
        if (!isPlayingLive()) {
            for (RectF r : weaponRects) r.setEmpty(); // nothing tappable while this screen is up
            drawDimOverlay(canvas, w0, h0);
            return;
        }

        ensureIconsLoaded();
        byte[] block = activity.nativeReadSystemRam(BLOCK_OFFSET, BLOCK_LENGTH);

        // This view fills the whole second-screen Presentation (see
        // SuperMetroidSecondScreenPresentation's MATCH_PARENT content view),
        // not just a slim strip - so everything below has to size itself
        // off a fraction of the screen WIDTH (a stable reference for a
        // horizontal strip regardless of aspect ratio), never off the
        // view's full height, or the strip balloons to fill the entire
        // screen vertically (pips the size of the whole panel, text pushed
        // off-screen). The rest of the view, below the strip, is reserved
        // for the real in-game map (see docs/retroarch-fork-notes.md's
        // Status section - not yet built) - background only for now.
        float w = getWidth(), h = getHeight();
        float margin = w * 0.025f;
        float stripH = Math.min(h * 0.16f, w * 0.11f);
        RectF strip = new RectF(margin, margin, w - margin, margin + stripH);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COL_PANEL_BG);
        canvas.drawRoundRect(strip, stripH * 0.1f, stripH * 0.1f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, stripH * 0.02f));
        paint.setColor(COL_BORDER_DARK);
        canvas.drawRoundRect(strip, stripH * 0.1f, stripH * 0.1f, paint);

        if (block == null || block.length < BLOCK_LENGTH)
        {
            for (RectF r : weaponRects) r.setEmpty(); // no game loaded - nothing tappable
            String msg = "NO GAME LOADED YET";
            float pixelSize = PixelFont.pixelSizeForHeight(stripH * 0.3f);
            PixelFont.drawText(canvas, msg, strip.left + stripH * 0.3f,
                    strip.centerY() - PixelFont.glyphHeight(pixelSize) / 2f,
                    pixelSize, COL_DIM_GRAY, Paint.Align.LEFT);
            return;
        }

        int health          = readUint16LE(block, OFF_HEALTH - BLOCK_OFFSET);
        int maxHealth        = readUint16LE(block, OFF_MAX_HEALTH - BLOCK_OFFSET);
        int missiles         = readUint16LE(block, OFF_MISSILES - BLOCK_OFFSET);
        int maxMissiles      = readUint16LE(block, OFF_MAX_MISSILES - BLOCK_OFFSET);
        int superMissiles    = readUint16LE(block, OFF_SUPER_MISSILES - BLOCK_OFFSET);
        int maxSuperMissiles = readUint16LE(block, OFF_MAX_SUPER_MISSILES - BLOCK_OFFSET);
        int powerBombs       = readUint16LE(block, OFF_POWER_BOMBS - BLOCK_OFFSET);
        int maxPowerBombs    = readUint16LE(block, OFF_MAX_POWER_BOMBS - BLOCK_OFFSET);

        float textSize = stripH * 0.32f;
        float pipSize = stripH * 0.28f;

        // Energy tank pips - same min(health/100, maxTanks) convention as
        // MapStatusView.java, wrapping to a second row past PIPS_PER_ROW.
        int maxTanks = Math.min(maxHealth / 100, MAX_STATUS_STRIP_PIPS);
        int filledTanks = Math.min(health / 100, maxTanks);
        int pipCols = Math.min(maxTanks, PIPS_PER_ROW);
        int pipRowCount = maxTanks <= PIPS_PER_ROW ? 1 : 2;
        float pipRowStep = pipSize * 1.3f;
        float pipBlockH = pipRowStep * (pipRowCount - 1) + pipSize;
        float pipBlockTop = strip.top + (stripH - pipBlockH) * 0.5f;
        float pipLeft = strip.left + stripH * 0.3f;

        for (int i = 0; i < maxTanks; i++) {
            int row = i / PIPS_PER_ROW, col = i % PIPS_PER_ROW;
            float rowMidY = pipBlockTop + pipRowStep * row + pipSize * 0.5f;
            float px = pipLeft + col * (pipSize + stripH * 0.07f);
            RectF dest = new RectF(px, rowMidY - pipSize / 2f, px + pipSize, rowMidY + pipSize / 2f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(i < filledTanks ? COL_ENERGY_PIP : COL_DIM_GRAY);
            canvas.drawRect(dest, paint);
            if (i < filledTanks) {
                paint.setColor(Color.WHITE);
                float thickness = pipSize * 0.16f;
                canvas.drawRect(dest.left, dest.top, dest.right, dest.top + thickness, paint);
                canvas.drawRect(dest.left, dest.top, dest.left + thickness, dest.bottom, paint);
            }
        }

        // Health number (current energy within the topmost partial tank,
        // 0-99 - same convention the real in-game HUD uses, see
        // sm_81.c's samus_health % 100) and weapon counts, to the right of
        // the pips, vertically centered across the whole strip.
        float midY = strip.top + stripH * 0.5f;
        float x = pipLeft + pipCols * (pipSize + stripH * 0.07f) + stripH * 0.35f;
        float pixelSize = PixelFont.pixelSizeForHeight(textSize);

        String healthText = String.valueOf(maxTanks > 0 ? (health % 100) : health);
        PixelFont.drawText(canvas, healthText, x, midY - textSize / 2f, pixelSize, Color.WHITE, Paint.Align.LEFT);
        x += PixelFont.measureWidth(healthText, pixelSize) + stripH * 0.5f;

        int[] counts    = { missiles, superMissiles, powerBombs };
        int[] maxCounts = { maxMissiles, maxSuperMissiles, maxPowerBombs };
        // Real ROM-decoded icon bitmaps (see ensureIconsLoaded above) - the
        // missile icon is naturally 24x16 (3x2 tiles), the other two 16x16
        // (2x2 tiles), so each is scaled to the same iconH for a
        // consistent row instead of the missile icon (widest natively)
        // looking oversized next to the others.
        Bitmap[] icons = { missileIconBitmap, superMissileIconBitmap, powerBombIconBitmap };
        float iconH = stripH * 0.34f;

        int hudItemIndex = readUint16LE(block, OFF_HUD_ITEM_INDEX - BLOCK_OFFSET);

        for (int i = 0; i < 3; i++) {
            weaponRects[i].setEmpty();
            if (maxCounts[i] <= 0) continue; // not yet collected - matches in-game HUD behavior

            float slotStartX = x;
            Bitmap icon = icons[i];
            if (icon != null) {
                float iconW = iconH * ((float) icon.getWidth() / icon.getHeight());
                RectF iconDest = new RectF(x, midY - iconH / 2f, x + iconW, midY + iconH / 2f);
                if (hudItemIndex == AMMO_SLOTS[i]) {
                    // Real WRAM says this slot is currently armed - same
                    // accent-colored highlight border MapStatusView.java
                    // draws for its own selected slot.
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(stripH * 0.04f);
                    paint.setColor(COL_ACCENT);
                    canvas.drawRect(iconDest.left - stripH * 0.05f, iconDest.top - stripH * 0.05f,
                            iconDest.right + stripH * 0.05f, iconDest.bottom + stripH * 0.05f, paint);
                }
                srcRect.set(0, 0, icon.getWidth(), icon.getHeight());
                dstRect.set(Math.round(iconDest.left), Math.round(iconDest.top),
                        Math.round(iconDest.right), Math.round(iconDest.bottom));
                canvas.drawBitmap(icon, srcRect, dstRect, null);
                x += iconW + stripH * 0.15f;
            }

            String countText = String.valueOf(counts[i]);
            PixelFont.drawText(canvas, countText, x, midY - textSize / 2f, pixelSize, Color.WHITE, Paint.Align.LEFT);
            x += PixelFont.measureWidth(countText, pixelSize);
            // Generous tap target: the whole icon+count group, not just
            // the icon itself - same as MapStatusView.java's own
            // statusStripWeaponRects.
            weaponRects[i].set(slotStartX, strip.top, x, strip.bottom);
            x += stripH * 0.45f;
        }

        // Below the strip: the room Samus is currently standing in, real
        // ROM-decoded map art (see SuperMetroidRomMap.java) cropped to just
        // that room - not the full multi-area world view yet (see
        // docs/retroarch-fork-notes.md's Status section).
        drawRoomMap(canvas, strip.left, strip.bottom + stripH * 0.15f,
                w - strip.left * 2f, h - strip.bottom - stripH * 0.3f);
    }

    // Matches SM2_IsPlayingLive (second_screen.c): 7 = fade-in into
    // gameplay, 8 = main gameplay, 9..0xB = the brief hit-door-block/
    // loading-next-room blip. Everything else is a menu, pause, cutscene,
    // death sequence, or demo attract-mode.
    private boolean isPlayingLive() {
        byte[] block = activity.nativeReadSystemRam(OFF_GAME_STATE, GAME_STATE_LENGTH);
        if (block == null || block.length < GAME_STATE_LENGTH) return false;
        int gameState = readUint16LE(block, 0);
        return gameState >= 7 && gameState <= 0xB;
    }

    private static final String LOGO_TEXT = "METROID";

    // Dims the whole screen down to near-black and shows a faint, dimmed
    // Metroid wordmark - same treatment as MapStatusView.java's own
    // drawDimOverlay (itself mirroring the zelda3-android dual-screen
    // mod's title/cutscene handling), dimmed rather than bright since this
    // panel is idle, not the thing being looked at right now.
    private void drawDimOverlay(Canvas canvas, float w, float h) {
        // Background is already the solid COL_BG fill from the top of
        // onDraw - nothing real is drawn underneath this any more (see
        // onDraw's own early-return comment), so there's no bleed-through
        // to dim over. Just the logo.
        float cx = w / 2f, cy = h / 2f;
        float textHeight = w * 0.11f;
        int fadedAccent = Color.argb(70, Color.red(COL_ACCENT), Color.green(COL_ACCENT), Color.blue(COL_ACCENT));
        float pixelSize = PixelFont.pixelSizeForHeight(textHeight);
        float textWidth = PixelFont.measureWidth(LOGO_TEXT, pixelSize);
        float lineHalf = textWidth * 0.55f;
        float lineY1 = cy - textHeight * 0.9f;
        float lineY2 = cy + textHeight * 0.7f;

        paint.setColor(fadedAccent);
        paint.setStrokeWidth(3f);
        canvas.drawLine(cx - lineHalf, lineY1, cx + lineHalf, lineY1, paint);
        canvas.drawLine(cx - lineHalf, lineY2, cx + lineHalf, lineY2, paint);
        PixelFont.drawText(canvas, LOGO_TEXT, cx, cy - textHeight / 2f, pixelSize, fadedAccent, Paint.Align.CENTER);
    }

    private void drawRoomMap(Canvas canvas, float left, float top, float areaW, float areaH) {
        RectF mapArea = new RectF(left, top, left + areaW, top + areaH);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COL_PANEL_BG);
        canvas.drawRoundRect(mapArea, areaH * 0.03f, areaH * 0.03f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, areaH * 0.01f));
        paint.setColor(COL_BORDER_DARK);
        canvas.drawRoundRect(mapArea, areaH * 0.03f, areaH * 0.03f, paint);

        byte[] roomBlock = activity.nativeReadSystemRam(ROOM_BLOCK_OFFSET, ROOM_BLOCK_LENGTH);
        if (roomBlock == null || roomBlock.length < ROOM_BLOCK_LENGTH) return; // "NO GAME LOADED" already shown above

        int areaIndex = readUint16LE(roomBlock, OFF_AREA_INDEX - ROOM_BLOCK_OFFSET);
        int roomX = readUint16LE(roomBlock, OFF_ROOM_X_ON_MAP - ROOM_BLOCK_OFFSET);
        int roomY = readUint16LE(roomBlock, OFF_ROOM_Y_ON_MAP - ROOM_BLOCK_OFFSET);
        // room_width/height_in_blocks (also in this block) are NOT map-tile
        // units - RoomDefHeader parsing (sm_82.c) sets them to
        // 16*width_in_screens, a gameplay-scroll-region unit unrelated to
        // the pause map's 8px tile grid, so they can't be used to size a
        // crop window here. Center on Samus's own real map tile instead
        // (same tileX/tileY formula as the position dot below) with a
        // fixed-size window - simpler, and doesn't depend on figuring out
        // the real blocks-to-map-tiles conversion.

        ensureAreaMapLoaded(areaIndex);
        int[] map = areaMapPixels;
        byte[] posBlock = activity.nativeReadSystemRam(SAMUS_POS_BLOCK_OFFSET, SAMUS_POS_BLOCK_LENGTH);
        if (map == null || areaMapForIndex != areaIndex
                || posBlock == null || posBlock.length < SAMUS_POS_BLOCK_LENGTH) {
            String msg = map == null ? "LOADING MAP..." : "NO POSITION DATA";
            float pixelSize = PixelFont.pixelSizeForHeight(mapArea.height() * 0.06f);
            PixelFont.drawText(canvas, msg, mapArea.centerX(), mapArea.centerY() - PixelFont.glyphHeight(pixelSize) / 2f,
                    pixelSize, COL_DIM_GRAY, Paint.Align.CENTER);
            return;
        }

        int samusXPos = readUint16LE(posBlock, OFF_SAMUS_X_POS - SAMUS_POS_BLOCK_OFFSET);
        int samusYPos = readUint16LE(posBlock, OFF_SAMUS_Y_POS - SAMUS_POS_BLOCK_OFFSET);
        int tileX = roomX + (samusXPos >> 8);
        int tileY = roomY + (samusYPos >> 8) + 1;

        // Fixed-size window (in map tiles) centered on Samus, clamped to
        // stay inside the real GRID_W x GRID_H area bounds.
        final int windowTilesW = 20, windowTilesH = 12;
        int cropTx = Math.max(0, Math.min(SuperMetroidRomMap.GRID_W - windowTilesW, tileX - windowTilesW / 2));
        int cropTy = Math.max(0, Math.min(SuperMetroidRomMap.GRID_H - windowTilesH, tileY - windowTilesH / 2));
        int cropX = cropTx * 8, cropY = cropTy * 8;
        int cropW = windowTilesW * 8, cropH = windowTilesH * 8;

        float scale = Math.min(mapArea.width() / cropW, mapArea.height() / cropH);
        float destW = cropW * scale, destH = cropH * scale;
        float destLeft = mapArea.centerX() - destW / 2f;
        float destTop = mapArea.centerY() - destH / 2f;

        // Rebuild whenever the actual pixel array changed, not just when
        // the area index changed - was keyed on area index alone, a real
        // bug: ensureAreaMapLoaded re-decodes areaMapPixels (a new array)
        // whenever explored tiles change WITHIN the same area (walking
        // into a newly-explored room), but this bitmap never picked that
        // up, since the area index itself hadn't changed - confirmed
        // on-device as "map doesn't live update". Identity comparison
        // (map != areaMapBitmapSourcePixels), not equals() - a new decode
        // is always a genuinely new int[] even if a room's tiles happen to
        // produce identical pixel values.
        if (areaMapBitmap == null || areaMapBitmapSourcePixels != map) {
            areaMapBitmap = Bitmap.createBitmap(map, SuperMetroidRomMap.GRID_W * 8, SuperMetroidRomMap.GRID_H * 8, Bitmap.Config.ARGB_8888);
            areaMapBitmapSourcePixels = map;
        }
        srcRect.set(cropX, cropY, cropX + cropW, cropY + cropH);
        dstRect.set(Math.round(destLeft), Math.round(destTop), Math.round(destLeft + destW), Math.round(destTop + destH));
        canvas.drawBitmap(areaMapBitmap, srcRect, dstRect, null);

        // Samus's own position dot - same formula as SM2_GetSamusMapTile
        // (second_screen.c): room_x/y_coordinate_on_map + samus_x/y_pos>>8,
        // +1 on Y. Always inside the crop window since that window is
        // itself centered on this same tile (clamped to area bounds).
        float dotX = destLeft + (tileX - cropTx + 0.5f) * 8 * scale;
        float dotY = destTop + (tileY - cropTy + 0.5f) * 8 * scale;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COL_SAMUS_DOT);
        canvas.drawCircle(dotX, dotY, Math.max(3f, 8 * scale * 0.6f), paint);
    }

    // Cached per (area index, explored-bits snapshot) - a full-area decode
    // is real work (2048 map tiles, each an 8x8 4bpp SNES tile decode), not
    // something to redo every frame. Was cached per area index ONLY (never
    // re-decoding again once an area had been decoded once) - a real bug,
    // confirmed on-device: walking into newly-explored rooms within the
    // SAME area never updated the map, since the cache guard skipped
    // re-running this entirely. areaMapExploredHash is a lightweight
    // Arrays.hashCode of the last-decoded explored bits (and map-station-
    // owned flag), checked on every poll tick (cheap - just a 256-byte
    // hash) to detect when a real re-decode (expensive) is actually
    // needed, without decoding on every single tick regardless of change.
    private volatile int[] areaMapPixels;
    private volatile int areaMapForIndex = -1;
    private volatile int areaMapExploredHash;
    private volatile boolean areaMapLoadInFlight = false;
    private Bitmap areaMapBitmap;
    private int[] areaMapBitmapSourcePixels;
    // Checking for a change (2 extra WRAM reads + a 256-byte hash) is cheap
    // compared to the decode itself, but still real per-frame overhead if
    // done on every one of onDraw's up-to-60fps calls for no reason most of
    // those frames - throttled to a few times a second instead, plenty
    // responsive for a room-transition-triggered map update.
    private static final long AREA_MAP_CHECK_INTERVAL_MS = 200;
    private long areaMapLastCheckUptimeMs = -1;

    private void ensureAreaMapLoaded(int areaIndex) {
        if (romBytes == null) return; // ROM not loaded yet - ensureIconsLoaded will get it
        if (areaMapLoadInFlight) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (areaMapPixels != null && areaMapForIndex == areaIndex
                && now - areaMapLastCheckUptimeMs < AREA_MAP_CHECK_INTERVAL_MS) return;
        areaMapLastCheckUptimeMs = now;

        byte[] exploredBits = activity.nativeReadSystemRam(OFF_MAP_TILES_EXPLORED, MAP_TILES_EXPLORED_LENGTH);
        byte[] mapStationBytes = activity.nativeReadSystemRam(OFF_MAP_STATION_BYTE_ARRAY, MAP_STATION_BYTE_ARRAY_LENGTH);
        if (exploredBits == null || exploredBits.length < MAP_TILES_EXPLORED_LENGTH) return;

        boolean haveMapStation = mapStationBytes != null && areaIndex >= 0 && areaIndex < mapStationBytes.length
                && mapStationBytes[areaIndex] != 0;
        int exploredHash = java.util.Arrays.hashCode(exploredBits) * 31 + (haveMapStation ? 1 : 0);
        if (areaMapPixels != null && areaMapForIndex == areaIndex && areaMapExploredHash == exploredHash) return;

        areaMapLoadInFlight = true;
        byte[] romSnapshot = romBytes;
        new Thread(() -> {
            int[] pixels = SuperMetroidRomMap.renderAreaMap(romSnapshot, areaIndex, exploredBits, haveMapStation);
            if (pixels != null) {
                uiHandler.post(() -> {
                    areaMapPixels = pixels;
                    areaMapForIndex = areaIndex;
                    areaMapExploredHash = exploredHash;
                    invalidate();
                });
            }
            areaMapLoadInFlight = false;
        }, "SuperMetroidRomMapDecode").start();
    }

    // Tap-to-arm/tap-again-to-disarm, same behavior as MapStatusView.java's
    // own ACTION_UP handler on statusStripWeaponRects - a real WRAM write
    // (nativeWriteSystemRam), not a synthetic controller-button injection,
    // matching how SM2_SetSelectedAmmo does it on that project's own native
    // side (see that function's own comment on why hud_item_index alone
    // isn't enough - both auto-cancel flags below have to be cleared too,
    // or the game silently reverts the selection a couple of frames later).
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_UP)
            return true; // still consume the whole gesture (DOWN, MOVE, ...)
        if (!isPlayingLive())
            return true; // dim overlay is up - not a real gameplay tap target right now

        float x = event.getX(), y = event.getY();
        byte[] block = activity.nativeReadSystemRam(BLOCK_OFFSET, BLOCK_LENGTH);
        if (block == null || block.length < BLOCK_LENGTH) return true;

        int currentIndex = readUint16LE(block, OFF_HUD_ITEM_INDEX - BLOCK_OFFSET);
        for (int i = 0; i < weaponRects.length; i++) {
            if (!weaponRects[i].contains(x, y)) continue;
            boolean alreadySelected = currentIndex == AMMO_SLOTS[i];
            setHudItemIndex(alreadySelected ? AMMO_NONE : AMMO_SLOTS[i]);
            invalidate(); // don't wait for the next poll tick to show it
            break;
        }
        return true;
    }

    private void setHudItemIndex(int index) {
        writeUint16LE(OFF_HUD_ITEM_INDEX, index);
        writeUint16LE(OFF_SAMUS_AUTO_CANCEL_HUD_ITEM_INDEX, 0);
        writeUint16LE(OFF_HUD_AUTO_CANCEL_FLAG, 0);
    }

    private void writeUint16LE(int offset, int value) {
        activity.nativeWriteSystemRam(offset, (byte) (value & 0xFF));
        activity.nativeWriteSystemRam(offset + 1, (byte) ((value >> 8) & 0xFF));
    }
}
