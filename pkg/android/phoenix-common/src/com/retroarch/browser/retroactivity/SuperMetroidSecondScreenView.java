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

    // explored_map_tiles_saved (variables.h) - per-area explored-tile
    // bitmaps kept in sync on every area transition (not just at save
    // stations), one 256-byte slice per area, 8 areas. Used by the world
    // view for every area OTHER than the current one (which uses the live
    // OFF_MAP_TILES_EXPLORED above instead, freshest) - same split
    // GetExploredGridForArea (second_screen.c) makes.
    private static final int OFF_EXPLORED_MAP_TILES_SAVED = 0xCD52;
    private static final int EXPLORED_MAP_TILES_SAVED_LENGTH = 256 * 8;

    private static final int OFF_SAMUS_X_POS = 0xAF6;
    private static final int OFF_SAMUS_Y_POS = 0xAFA;
    private static final int SAMUS_POS_BLOCK_OFFSET = OFF_SAMUS_X_POS;
    private static final int SAMUS_POS_BLOCK_LENGTH = (OFF_SAMUS_Y_POS + 2) - OFF_SAMUS_X_POS;

    private static final int OFF_MAP_STATION_BYTE_ARRAY = 0xD908; // 8 bytes, one per area
    private static final int MAP_STATION_BYTE_ARRAY_LENGTH = 8;

    private static final int OFF_GAME_STATE = 0x998;
    private static final int GAME_STATE_LENGTH = 2;

    // Items tab WRAM offsets, ported from variables.h.
    private static final int OFF_EQUIPPED_ITEMS = 0x9A2;
    private static final int OFF_COLLECTED_ITEMS = 0x9A4;
    private static final int OFF_EQUIPPED_BEAMS = 0x9A6;
    private static final int OFF_COLLECTED_BEAMS = 0x9A8;
    // One read covering the whole 0x9A2-0x9AA block (8 bytes) - one JNI
    // call instead of four.
    private static final int ITEMS_BLOCK_OFFSET = OFF_EQUIPPED_ITEMS;
    private static final int ITEMS_BLOCK_LENGTH = (OFF_COLLECTED_BEAMS + 2) - OFF_EQUIPPED_ITEMS;

    private static final int OFF_GAME_TIME_SECONDS = 0x9DC;
    private static final int OFF_GAME_TIME_MINUTES = 0x9DE;
    private static final int OFF_GAME_TIME_HOURS = 0x9E0;
    private static final int TIME_BLOCK_OFFSET = OFF_GAME_TIME_SECONDS;
    private static final int TIME_BLOCK_LENGTH = (OFF_GAME_TIME_HOURS + 2) - OFF_GAME_TIME_SECONDS;

    // Real item/beam bit values and display names, copied verbatim from
    // MapStatusView.java's own EQUIP_SUIT/EQUIP_MISC/EQUIP_BOOTS/EQUIP_BEAM
    // (which themselves mirror the kSM2Item_*/kSM2Beam_* enums in
    // second_screen.h). isBeam selects which bitfield (collected_items vs
    // collected_beams) a group's bits belong to.
    private static final class EquipGroup {
        final String title;
        final int[] bits;
        final String[] labels;
        final boolean isBeam;
        EquipGroup(String title, int[] bits, String[] labels, boolean isBeam) {
            this.title = title;
            this.bits = bits;
            this.labels = labels;
            this.isBeam = isBeam;
        }
    }
    private static final EquipGroup EQUIP_SUIT = new EquipGroup("SUIT",
            new int[] { 0x0001, 0x0020 }, new String[] { "VARIA SUIT", "GRAVITY SUIT" }, false);
    private static final EquipGroup EQUIP_MISC = new EquipGroup("MISC.",
            new int[] { 0x0004, 0x1000, 0x0002, 0x0008 },
            new String[] { "MORPHING BALL", "BOMB", "SPRING BALL", "SCREW ATTACK" }, false);
    private static final EquipGroup EQUIP_BOOTS = new EquipGroup("BOOTS",
            new int[] { 0x0100, 0x0200, 0x2000 },
            new String[] { "HI-JUMP BOOTS", "SPACE JUMP", "SPEED BOOSTER" }, false);
    private static final EquipGroup EQUIP_BEAM = new EquipGroup("BEAM",
            new int[] { 0x1000, 0x0002, 0x0001, 0x0004, 0x0008 },
            new String[] { "CHARGE", "ICE", "WAVE", "SPAZER", "PLASMA" }, true);

    // Same palette as MapStatusView.java's own COL_* constants.
    private static final int COL_BG = Color.rgb(30, 33, 44);
    private static final int COL_PANEL_BG = Color.rgb(38, 42, 56);
    private static final int COL_BORDER_DARK = Color.rgb(58, 64, 86);
    private static final int COL_DIM_GRAY = Color.rgb(105, 110, 128);
    private static final int COL_ENERGY_PIP = Color.rgb(204, 71, 145);
    private static final int COL_ACCENT = Color.rgb(255, 158, 68);
    private static final int COL_SAMUS_DOT = Color.rgb(255, 70, 70);
    private static final int COL_TAB_ACTIVE_BG = Color.rgb(56, 61, 82);
    private static final int COL_TAB_LABEL = Color.rgb(205, 209, 225);
    private static final int COL_BORDER_HIGHLIGHT = Color.rgb(115, 124, 155);
    private static final int COL_SLOT_BG = Color.rgb(48, 52, 68);

    private static final int PIPS_PER_ROW = 7;
    private static final int MAX_STATUS_STRIP_PIPS = PIPS_PER_ROW * 2;

    // Tab bar + map controls bar, matching MapStatusView.java's own layout
    // (README screenshots: MAP/ITEMS/SETUP footer tabs, a controls strip
    // above it with room<->world jump + zoom out/in). ITEMS and SETUP are
    // placeholders for now (see docs/retroarch-fork-notes.md's Status
    // section) - only MAP has real content so far.
    private enum Tab { MAP, ITEMS, SETUP }
    private Tab currentTab = Tab.MAP;
    private static final String[] TAB_LABELS = { "MAP", "ITEMS", "SETUP" };
    private final RectF[] tabButtonRects = { new RectF(), new RectF(), new RectF() };

    private static final float ZOOM_BUTTON_STEP = 1.4f;
    // MIN_ZOOM below 1 gives a real zoom-out step beyond the base 20x12-tile
    // window (windowTilesW/H in drawRoomMap) - confirmed on-device as a
    // real, wanted step ("can't zoom out one more time" from that fixed
    // baseline).
    private static final float MIN_ZOOM = 0.6f, MAX_ZOOM = 6f;
    private static final float MIN_WORLD_ZOOM = 1f, MAX_WORLD_ZOOM = 4f;
    private float roomZoomFactor = MIN_ZOOM;
    private float worldZoomFactor = MIN_WORLD_ZOOM;
    private final RectF roomWorldToggleBtn = new RectF();
    private final RectF zoomOutBtn = new RectF();
    private final RectF zoomInBtn = new RectF();

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

    // World-view toggle - tap the map area to switch between the current
    // room's close-up (drawRoomMap) and the full 6-area world composite
    // (drawWorldMap/SuperMetroidWorldMap). Zoom/pan within the world view
    // aren't implemented yet (see docs/retroarch-fork-notes.md's Status
    // section) - this always auto-frames to the explored regions' extent.
    private boolean worldView = false;
    private final RectF mapTapRect = new RectF();
    private final SuperMetroidWorldMap worldMap = new SuperMetroidWorldMap();

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

        // ensureWorldMapRefreshed runs regardless of which tab/view is
        // currently showing - was gated behind worldView being true, so
        // the round-robin only ever started catching up the moment you
        // actually switched to it, confirmed on-device as a real,
        // noticeable delay ("LOADING MAP...") on every switch. Keeping it
        // warm in the background means the world composite is usually
        // already caught up by the time you tap over to it.
        ensureWorldMapRefreshed();

        // Footer: persistent MAP/ITEMS/SETUP tab bar, with a map-controls
        // strip (room<->world jump + zoom out/in) above it while on the
        // MAP tab - same layout as MapStatusView.java's own hudBarRect/
        // mapControlsBarRect (README screenshots). Reserved regardless of
        // tab so the tab bar itself never moves when switching tabs.
        float tabBarH = stripH * 0.85f;
        float controlsBarH = currentTab == Tab.MAP ? stripH * 0.85f : 0f;
        RectF tabBarRect = new RectF(margin, h - tabBarH - margin * 0.3f, w - margin, h - margin * 0.3f);
        RectF controlsBarRect = new RectF(margin, tabBarRect.top - controlsBarH - stripH * 0.1f,
                w - margin, tabBarRect.top - stripH * 0.1f);

        layoutTabButtons(tabBarRect);
        drawTabBar(canvas);

        if (currentTab == Tab.MAP) {
            layoutMapControlButtons(controlsBarRect);
            drawMapControlButtons(canvas);

            mapTapRect.set(strip.left, strip.bottom + stripH * 0.15f, w - strip.left, controlsBarRect.top - stripH * 0.15f);
            if (worldView) {
                drawWorldMap(canvas, mapTapRect.left, mapTapRect.top, mapTapRect.right, mapTapRect.bottom);
            } else {
                drawRoomMap(canvas, mapTapRect.left, mapTapRect.top, mapTapRect.width(), mapTapRect.height());
            }
        } else {
            mapTapRect.setEmpty();
            for (RectF r : weaponRects) r.setEmpty(); // strip isn't drawn on this tab - nothing tappable
            RectF tabArea = new RectF(strip.left, strip.bottom + stripH * 0.15f, w - strip.left, tabBarRect.top - stripH * 0.15f);
            if (currentTab == Tab.ITEMS) {
                drawItemsTab(canvas, tabArea);
            } else {
                drawPlaceholderTab(canvas, tabArea, "SETUP");
            }
        }
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
        if (areaIndex != lastRoomViewArea) {
            // A pan offset from the previous area's coordinate space is
            // meaningless once Samus has moved to a different area - reset
            // back to Samus-centered, matching MapStatusView.java's own
            // drawMap areaChanged handling.
            roomPanOffsetX = 0f;
            roomPanOffsetY = 0f;
            lastRoomViewArea = areaIndex;
        }
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

        // Reverted back to the original, confirmed-working fixed-size
        // window centered on Samus's integer map tile - the real
        // explored-tile-bounds auto-fit this replaced broke centering on
        // real hardware ("the map is not centered at all"), and wasn't
        // actually what was asked for anyway ("just wanted [zoom] to
        // centered view out one more time"). Only real, wanted change on
        // top of the original: the base window size itself now scales by
        // roomZoomFactor (still centered the exact same way), giving a
        // real zoom range including one more zoom-out step, plus manual
        // drag pan (roomPanOffsetX/Y - see onTouchEvent) on top of the
        // Samus-centered baseline.
        final int baseWindowTilesW = 20, baseWindowTilesH = 12;
        int windowTilesW = clampInt(Math.round(baseWindowTilesW / roomZoomFactor), 3, SuperMetroidRomMap.GRID_W);
        int windowTilesH = clampInt(Math.round(baseWindowTilesH / roomZoomFactor), 2, SuperMetroidRomMap.GRID_H);
        float centerTileX = tileX + roomPanOffsetX;
        float centerTileY = tileY + roomPanOffsetY;
        int cropTx = clampInt(Math.round(centerTileX - windowTilesW / 2f), 0, SuperMetroidRomMap.GRID_W - windowTilesW);
        int cropTy = clampInt(Math.round(centerTileY - windowTilesH / 2f), 0, SuperMetroidRomMap.GRID_H - windowTilesH);
        int cropX = cropTx * 8, cropY = cropTy * 8;
        int cropW = windowTilesW * 8, cropH = windowTilesH * 8;

        float scale = Math.min(mapArea.width() / cropW, mapArea.height() / cropH);
        float destW = cropW * scale, destH = cropH * scale;
        float destLeft = mapArea.centerX() - destW / 2f;
        float destTop = mapArea.centerY() - destH / 2f;
        // Cache the screen<->canvas-tile transform for onTouchEvent's
        // drag-pan handling. Real bug fixed here: scale is screen-pixels
        // per MAP-PIXEL-unit (cropW/cropH are in 8px-per-tile units), not
        // per TILE - roomPanOffsetX/Y are tile units (applied via
        // centerX*8 above), so this needs an extra /8 to actually convert
        // a screen-pixel drag delta into tiles. Without it, panning was 8x
        // too fast - confirmed on-device ("touch screen movement is way
        // too fast").
        roomViewTilesPerPixel = 1f / (scale * 8f);

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
        // +1 on Y. NOT guaranteed inside the crop window any more now that
        // manual pan (roomPanOffsetX/Y) can move the view away from Samus -
        // only drawn when it's actually visible.
        float dotX = destLeft + (tileX - cropTx + 0.5f) * 8 * scale;
        float dotY = destTop + (tileY - cropTy + 0.5f) * 8 * scale;
        if (dotX >= destLeft && dotX <= destLeft + destW && dotY >= destTop && dotY <= destTop + destH) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(COL_SAMUS_DOT);
            canvas.drawCircle(dotX, dotY, Math.max(3f, 8 * scale * 0.6f), paint);
        }
    }

    // Full 6-area world composite - see SuperMetroidWorldMap for the
    // actual round-robin decode/compositing (ported from MapStatusView.
    // java's own ensureWorldAreaFresh/drawWorldView). Auto-frames to the
    // combined extent of every area with at least one explored tile, same
    // as the original's MIN_WORLD_ZOOM baseline - no pinch-zoom/pan yet
    // (see docs/retroarch-fork-notes.md's Status section).
    private void drawWorldMap(Canvas canvas, float left, float top, float right, float bottom) {
        RectF mapArea = new RectF(left, top, right, bottom);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COL_PANEL_BG);
        canvas.drawRoundRect(mapArea, mapArea.height() * 0.03f, mapArea.height() * 0.03f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, mapArea.height() * 0.01f));
        paint.setColor(COL_BORDER_DARK);
        canvas.drawRoundRect(mapArea, mapArea.height() * 0.03f, mapArea.height() * 0.03f, paint);

        worldMap.ensureBitmapUpToDate();
        if (worldMap.compositeBitmap == null) {
            String msg = "LOADING MAP...";
            float pixelSize = PixelFont.pixelSizeForHeight(mapArea.height() * 0.06f);
            PixelFont.drawText(canvas, msg, mapArea.centerX(), mapArea.centerY() - PixelFont.glyphHeight(pixelSize) / 2f,
                    pixelSize, COL_DIM_GRAY, Paint.Align.CENTER);
            return;
        }

        // Frame just the explored areas' combined extent (same as the
        // original's drawWorldView), not the full 6-area canvas - early on,
        // with only one or two areas visible, this fills the screen
        // instead of leaving most of it dark for areas not reached yet.
        float visMinX = Float.MAX_VALUE, visMinY = Float.MAX_VALUE;
        float visMaxX = -Float.MAX_VALUE, visMaxY = -Float.MAX_VALUE;
        for (int a = 0; a < SuperMetroidWorldMap.AREA_COUNT; a++) {
            if (!worldMap.areaDrawn[a]) continue;
            float[] l = SuperMetroidWorldMap.AREA_LAYOUT[a];
            visMinX = Math.min(visMinX, l[4]);
            visMinY = Math.min(visMinY, l[5]);
            visMaxX = Math.max(visMaxX, l[4] + (l[2] - l[0]));
            visMaxY = Math.max(visMaxY, l[5] + (l[3] - l[1]));
        }
        if (visMinX > visMaxX) {
            String msg = "NOTHING EXPLORED YET";
            float pixelSize = PixelFont.pixelSizeForHeight(mapArea.height() * 0.06f);
            PixelFont.drawText(canvas, msg, mapArea.centerX(), mapArea.centerY() - PixelFont.glyphHeight(pixelSize) / 2f,
                    pixelSize, COL_DIM_GRAY, Paint.Align.CENTER);
            return;
        }

        float margin = Math.max(visMaxX - visMinX, visMaxY - visMinY) * 0.06f;
        visMinX -= margin; visMinY -= margin; visMaxX += margin; visMaxY += margin;
        float fitCenterX = (visMinX + visMaxX) / 2f, fitCenterY = (visMinY + visMaxY) / 2f;
        float fitCanvasW = visMaxX - visMinX, fitCanvasH = visMaxY - visMinY;

        // worldZoomFactor shrinks the visible window around a pan-adjusted
        // center (worldPanOffsetX/Y - see onTouchEvent's drag handling) -
        // at MIN_WORLD_ZOOM this covers exactly the auto-fit region, same
        // as MapStatusView.java's own drawWorldView.
        float canvasW = fitCanvasW / worldZoomFactor;
        float canvasH = fitCanvasH / worldZoomFactor;
        float halfW = canvasW / 2f, halfH = canvasH / 2f;
        float centerX = clampFloat(fitCenterX + worldPanOffsetX, visMinX + halfW, visMaxX - halfW);
        float centerY = clampFloat(fitCenterY + worldPanOffsetY, visMinY + halfH, visMaxY - halfH);
        float viewMinX = centerX - halfW, viewMinY = centerY - halfH;

        float availW = mapArea.width(), availH = mapArea.height();
        float scale = Math.min(availW / canvasW, availH / canvasH);
        float originX = mapArea.left + (availW - canvasW * scale) / 2f - viewMinX * scale;
        float originY = mapArea.top + (availH - canvasH * scale) / 2f - viewMinY * scale;
        // Cache tiles-per-screen-pixel for onTouchEvent's drag-pan handling.
        worldViewTilesPerPixel = 1f / scale;

        // Real bug fixed here: drew the WHOLE composite bitmap positioned
        // at originX/originY, relying on nothing to actually crop it to
        // mapArea's bounds - fine at worldZoomFactor=1 (the auto-fit
        // region happens to fill mapArea exactly), but once real zoom was
        // added, the oversized bitmap would draw straight past mapArea's
        // edges into the rest of the screen with no clip in place. Clip to
        // mapArea before drawing, same as any other zoomed content.
        int clipSave = canvas.save();
        canvas.clipRect(mapArea);
        srcRect.set(0, 0, SuperMetroidWorldMap.CANVAS_PX_W, SuperMetroidWorldMap.CANVAS_PX_H);
        dstRect.set(Math.round(originX), Math.round(originY),
                Math.round(originX + SuperMetroidWorldMap.CANVAS_TILES_W * scale),
                Math.round(originY + SuperMetroidWorldMap.CANVAS_TILES_H * scale));
        canvas.drawBitmap(worldMap.compositeBitmap, srcRect, dstRect, null);

        // Real ROM-decoded area-name labels (e.g. "BRINSTAR"), centered on
        // each area's own real explored-pixel centroid (worldMap.labelCenterX/Y -
        // see SuperMetroidWorldMap.recomputeLabelCentroids), NOT the
        // declared layout box's raw corner - that box deliberately
        // overlaps between areas, so a corner-anchored label could land
        // inside a different area's own art. Same fixed fraction-of-panel-
        // width sizing for every label regardless of that area's own
        // box/room-cluster size, matching MapStatusView.java's own
        // drawWorldView reasoning.
        float labelW = mapArea.width() * 0.16f;
        for (int a = 0; a < SuperMetroidWorldMap.AREA_COUNT; a++) {
            if (!worldMap.areaDrawn[a] || !worldMap.haveLabel[a]) continue;
            float labelCx = originX + worldMap.labelCenterX[a] * scale;
            float labelCy = originY + worldMap.labelCenterY[a] * scale;
            drawWorldLabel(canvas, worldMap.labelBitmaps[a], labelCx, labelCy, labelW);
        }

        // Samus's own position marker - same formula as SM2_GetSamusMapPosFixed
        // (second_screen.c): room_x/y_coordinate_on_map*256 + samus_x/y_pos
        // (+256 on Y), i.e. the same tileX/tileY drawRoomMap uses, kept at
        // sub-tile precision instead of truncated, transformed into world-
        // canvas space the same way every area's own art is (declared-box
        // offset + this area's own WORLD_AREA_LAYOUT destination).
        byte[] roomBlock = activity.nativeReadSystemRam(ROOM_BLOCK_OFFSET, ROOM_BLOCK_LENGTH);
        byte[] posBlock = activity.nativeReadSystemRam(SAMUS_POS_BLOCK_OFFSET, SAMUS_POS_BLOCK_LENGTH);
        if (roomBlock != null && roomBlock.length >= ROOM_BLOCK_LENGTH
                && posBlock != null && posBlock.length >= SAMUS_POS_BLOCK_LENGTH) {
            int currentArea = readUint16LE(roomBlock, OFF_AREA_INDEX - ROOM_BLOCK_OFFSET);
            int roomX = readUint16LE(roomBlock, OFF_ROOM_X_ON_MAP - ROOM_BLOCK_OFFSET);
            int roomY = readUint16LE(roomBlock, OFF_ROOM_Y_ON_MAP - ROOM_BLOCK_OFFSET);
            int samusXPos = readUint16LE(posBlock, OFF_SAMUS_X_POS - SAMUS_POS_BLOCK_OFFSET);
            int samusYPos = readUint16LE(posBlock, OFF_SAMUS_Y_POS - SAMUS_POS_BLOCK_OFFSET);
            float samusSmoothX = roomX + samusXPos / 256f;
            float samusSmoothY = roomY + samusYPos / 256f + 1f;

            if (currentArea >= 0 && currentArea < SuperMetroidWorldMap.AREA_COUNT) {
                float[] l = SuperMetroidWorldMap.AREA_LAYOUT[currentArea];
                float declMinX = l[0], declMinY = l[1], declMaxX = l[2], declMaxY = l[3];
                if (samusSmoothX >= declMinX && samusSmoothX < declMaxX && samusSmoothY >= declMinY && samusSmoothY < declMaxY) {
                    float mx = originX + (l[4] + (samusSmoothX - declMinX)) * scale;
                    float my = originY + (l[5] + (samusSmoothY - declMinY)) * scale;
                    float radius = Math.max(4f, scale * 0.55f);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(COL_SAMUS_DOT);
                    canvas.drawCircle(mx, my, radius, paint);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(Math.max(2f, radius * 0.25f));
                    paint.setColor(Color.WHITE);
                    canvas.drawCircle(mx, my, radius, paint);
                }
            }
        }
        canvas.restoreToCount(clipSave);
    }

    // Scales a 96x8px area-name label bitmap to labelW wide (preserving
    // its 12:1 aspect ratio), centered on (centerX, centerY), on top of a
    // small opaque nameplate rather than drawn translucently straight onto
    // the map - matches MapStatusView.java's own drawLabelBitmap. An
    // opaque plate behind the text guarantees contrast against busy/
    // colorful map art regardless of what's underneath.
    private void drawWorldLabel(Canvas canvas, Bitmap label, float centerX, float centerY, float labelW) {
        float labelH = labelW * (8f / (SuperMetroidRomMap.LABEL_TILES * 8f));
        float padX = labelW * 0.10f, padY = labelH * 0.35f;
        RectF plate = new RectF(centerX - labelW / 2f - padX, centerY - labelH / 2f - padY,
                centerX + labelW / 2f + padX, centerY + labelH / 2f + padY);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COL_PANEL_BG);
        canvas.drawRoundRect(plate, plate.height() * 0.15f, plate.height() * 0.15f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, plate.height() * 0.06f));
        paint.setColor(COL_BORDER_DARK);
        canvas.drawRoundRect(plate, plate.height() * 0.15f, plate.height() * 0.15f, paint);

        dstRect.set(Math.round(centerX - labelW / 2f), Math.round(centerY - labelH / 2f),
                Math.round(centerX + labelW / 2f), Math.round(centerY + labelH / 2f));
        canvas.drawBitmap(label, null, dstRect, null);
    }

    // 3 equal-width tab buttons filling the footer strip, small gaps
    // between them - matches MapStatusView.java's own layout.
    private void layoutTabButtons(RectF bar) {
        int tabCount = tabButtonRects.length;
        float tabGap = bar.width() * 0.012f;
        float tabW = (bar.width() - tabGap * (tabCount - 1)) / tabCount;
        for (int i = 0; i < tabCount; i++) {
            float tx0 = bar.left + i * (tabW + tabGap);
            tabButtonRects[i].set(tx0, bar.top, tx0 + tabW, bar.bottom);
        }
    }

    // Persistent footer tab bar - active tab gets a highlighted background
    // plus an accent border, matching MapStatusView.java's own drawTabBar.
    private void drawTabBar(Canvas canvas) {
        for (int i = 0; i < tabButtonRects.length; i++) {
            RectF r = tabButtonRects[i];
            boolean active = currentTab.ordinal() == i;
            drawPixelBox(canvas, r, active ? COL_TAB_ACTIVE_BG : COL_PANEL_BG,
                    active ? COL_ACCENT : COL_BORDER_DARK, true);

            float textSize = r.height() * 0.4f;
            PixelFont.drawText(canvas, TAB_LABELS[i], r.centerX(), r.centerY() - textSize / 2f,
                    PixelFont.pixelSizeForHeight(textSize), COL_TAB_LABEL, Paint.Align.CENTER);
        }
    }

    // 3 equal-width buttons: room/world jump (left), zoom out, zoom in
    // (right) - matches MapStatusView.java's own layoutMapControlsBar.
    private void layoutMapControlButtons(RectF bar) {
        float btnGap = bar.width() * 0.02f;
        float btnW = (bar.width() - btnGap * 2) / 3f;
        float bx = bar.left;
        roomWorldToggleBtn.set(bx, bar.top, bx + btnW, bar.bottom);
        bx += btnW + btnGap;
        zoomOutBtn.set(bx, bar.top, bx + btnW, bar.bottom);
        bx += btnW + btnGap;
        zoomInBtn.set(bx, bar.top, bx + btnW, bar.bottom);
    }

    // Small bordered box, borderless-corner variant (simple=true skips the
    // MapStatusView.java corner-accent squares, which look cluttered on
    // small controls like these) - shared by the tab bar and map control
    // buttons.
    private void drawPixelBox(Canvas canvas, RectF r, int fillColor, int borderColor, boolean simple) {
        float inset = Math.min(r.width(), r.height()) * 0.02f + 1.5f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fillColor);
        canvas.drawRect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(inset);
        paint.setColor(borderColor);
        canvas.drawRect(r.left + inset / 2f, r.top + inset / 2f, r.right - inset / 2f, r.bottom - inset / 2f, paint);
    }

    // Draws the room<->world jump button and zoom out/in buttons - matches
    // MapStatusView.java's own drawZoomButtons/drawRoomWorldToggleButton
    // icon designs (nested-square "zoom extent" pictogram, +/- lines).
    private void drawMapControlButtons(Canvas canvas) {
        drawPixelBox(canvas, roomWorldToggleBtn, COL_PANEL_BG, COL_BORDER_DARK, true);
        float pad = Math.min(roomWorldToggleBtn.width(), roomWorldToggleBtn.height()) * 0.24f;
        float cx = roomWorldToggleBtn.centerX(), cy = roomWorldToggleBtn.centerY();
        float outerHalf = pad, innerHalf = pad * 0.45f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, pad * 0.16f));
        // Outer square = world view, inner square = room view - the one
        // tapping this button would switch TO is drawn in the accent color.
        paint.setColor(worldView ? Color.WHITE : COL_ACCENT);
        canvas.drawRect(cx - outerHalf, cy - outerHalf, cx + outerHalf, cy + outerHalf, paint);
        paint.setColor(worldView ? COL_ACCENT : Color.WHITE);
        canvas.drawRect(cx - innerHalf, cy - innerHalf, cx + innerHalf, cy + innerHalf, paint);

        drawPixelBox(canvas, zoomInBtn, COL_PANEL_BG, COL_BORDER_DARK, true);
        drawPixelBox(canvas, zoomOutBtn, COL_PANEL_BG, COL_BORDER_DARK, true);
        paint.setColor(COL_ACCENT);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, Math.min(zoomInBtn.width(), zoomInBtn.height()) * 0.08f));
        float half = Math.min(zoomInBtn.width(), zoomInBtn.height()) * 0.28f;
        float icx1 = zoomInBtn.centerX(), icy1 = zoomInBtn.centerY();
        canvas.drawLine(icx1 - half, icy1, icx1 + half, icy1, paint);
        canvas.drawLine(icx1, icy1 - half, icx1, icy1 + half, paint);
        float icx2 = zoomOutBtn.centerX(), icy2 = zoomOutBtn.centerY();
        canvas.drawLine(icx2 - half, icy2, icx2 + half, icy2, paint);
    }

    // Same as clampInt but for floats, and tolerant of lo > hi (returns
    // their midpoint instead of misbehaving) - used when the viewport
    // itself is wider than the canvas (e.g. early game with only one
    // small area explored so far), where a naive clamp would otherwise
    // force lo > hi. Matches MapStatusView.java's own clampFloat.
    private static float clampFloat(float v, float lo, float hi) {
        if (hi < lo) return (lo + hi) / 2f;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private static int clampInt(int v, int lo, int hi) {
        if (hi < lo) return lo;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }


    // ITEMS/SETUP tabs - real content is real, later work (see
    // docs/retroarch-fork-notes.md's Status section: real ROM-decoded
    // equipment icons for ITEMS, the actual toggles/save-state UI for
    // SETUP, matching MapStatusView.java's own drawEquipmentTab/
    // drawSettingsTab). This just marks the tab as real and reachable
    // rather than leaving it silently missing.
    private void drawPlaceholderTab(Canvas canvas, RectF area, String label) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COL_PANEL_BG);
        canvas.drawRoundRect(area, area.height() * 0.02f, area.height() * 0.02f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, area.height() * 0.01f));
        paint.setColor(COL_BORDER_DARK);
        canvas.drawRoundRect(area, area.height() * 0.02f, area.height() * 0.02f, paint);

        String msg = label + " - COMING SOON";
        float pixelSize = PixelFont.pixelSizeForHeight(area.height() * 0.06f);
        PixelFont.drawText(canvas, msg, area.centerX(), area.centerY() - PixelFont.glyphHeight(pixelSize) / 2f,
                pixelSize, COL_DIM_GRAY, Paint.Align.CENTER);
    }

    // Real equipment data (SUIT/MISC/BOOTS/BEAM boxes + ITEMS%/TIME stats),
    // matching MapStatusView.java's own drawEquipmentTab - minus the
    // middle Samus wireframe/suit art column and its callout lines (a
    // separate ROM-decode task, sourced from a different ROM entirely -
    // Super Metroid Redux - see docs/retroarch-fork-notes.md's Status
    // section), so this is a 2-column layout (SUIT/MISC left,
    // BOOTS/BEAM right) rather than 3.
    private void drawItemsTab(Canvas canvas, RectF area) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COL_PANEL_BG);
        canvas.drawRoundRect(area, area.height() * 0.02f, area.height() * 0.02f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, area.height() * 0.01f));
        paint.setColor(COL_BORDER_DARK);
        canvas.drawRoundRect(area, area.height() * 0.02f, area.height() * 0.02f, paint);

        byte[] itemsBlock = activity.nativeReadSystemRam(ITEMS_BLOCK_OFFSET, ITEMS_BLOCK_LENGTH);
        if (itemsBlock == null || itemsBlock.length < ITEMS_BLOCK_LENGTH) {
            String msg = "NO GAME LOADED YET";
            float pixelSize = PixelFont.pixelSizeForHeight(area.height() * 0.06f);
            PixelFont.drawText(canvas, msg, area.centerX(), area.centerY() - PixelFont.glyphHeight(pixelSize) / 2f,
                    pixelSize, COL_DIM_GRAY, Paint.Align.CENTER);
            return;
        }
        int collectedItems = readUint16LE(itemsBlock, OFF_COLLECTED_ITEMS - ITEMS_BLOCK_OFFSET);
        int collectedBeams = readUint16LE(itemsBlock, OFF_COLLECTED_BEAMS - ITEMS_BLOCK_OFFSET);

        float pad = area.width() * 0.03f;
        float left = area.left + pad, right = area.right - pad;
        float top = area.top + pad, bottom = area.bottom - pad;
        float colGap = pad * 0.8f;

        int equippedItems = readUint16LE(itemsBlock, OFF_EQUIPPED_ITEMS - ITEMS_BLOCK_OFFSET);

        // 3 columns: SUIT/MISC boxes | wireframe body | BOOTS/BEAM boxes -
        // mirrors the real pause screen's layout, matching
        // MapStatusView.java's own drawEquipmentTab. The wireframe's own
        // native aspect ratio (64x136) sizes its column; the two box
        // columns split whatever width remains.
        float headerH = (bottom - top) * 0.16f;
        float wireframeAspect = SuperMetroidReduxSuit.PX_W / (float) SuperMetroidReduxSuit.PX_H;
        float wireframeW = Math.min((right - left) * 0.26f, (bottom - top - headerH) * wireframeAspect);
        float colW = (right - left - colGap * 2 - wireframeW) / 2f;

        drawStatBox(canvas, left, top, colW, headerH, "ITEMS", computeItemPercentText(collectedItems, collectedBeams));
        drawStatBox(canvas, left + colW + colGap + wireframeW + colGap, top, colW, headerH, "TIME", computeTimeText());

        float bodyTop = top + headerH + pad * 0.6f;
        float bodyH = bottom - bodyTop;
        drawEquipColumn(canvas, left, bodyTop, colW, bodyH, collectedItems, collectedBeams, EQUIP_SUIT, EQUIP_MISC);
        drawReduxSuit(canvas, left + colW + colGap, bodyTop, wireframeW, bodyH, equippedItems);
        drawEquipColumn(canvas, left + colW + colGap + wireframeW + colGap, bodyTop, colW, bodyH, collectedItems, collectedBeams, EQUIP_BOOTS, EQUIP_BEAM);
    }

    // Real full-color Redux Suit art (SuperMetroidReduxSuit.java), correct
    // pose and suit color for whatever's actually equipped right now -
    // centered in the given rect at its native aspect ratio (no stretch).
    // Cached and only re-rendered when equippedItems actually changes
    // (a real, if cheap, per-pixel decode - no reason to redo it every
    // single draw call for a value that only changes on pickup).
    private int lastReduxSuitEquippedItems = -1;
    private Bitmap reduxSuitBitmap;

    private void drawReduxSuit(Canvas canvas, float x, float top, float w, float h, int equippedItems) {
        if (reduxSuitBitmap == null || equippedItems != lastReduxSuitEquippedItems) {
            int[] pixels = SuperMetroidReduxSuit.render(equippedItems);
            reduxSuitBitmap = Bitmap.createBitmap(pixels, SuperMetroidReduxSuit.PX_W, SuperMetroidReduxSuit.PX_H, Bitmap.Config.ARGB_8888);
            lastReduxSuitEquippedItems = equippedItems;
        }
        float aspect = SuperMetroidReduxSuit.PX_W / (float) SuperMetroidReduxSuit.PX_H;
        float destH = h, destW = destH * aspect;
        if (destW > w) { destW = w; destH = destW / aspect; }
        float destLeft = x + (w - destW) / 2f;
        float destTop = top + (h - destH) / 2f;
        dstRect.set(Math.round(destLeft), Math.round(destTop), Math.round(destLeft + destW), Math.round(destTop + destH));
        canvas.drawBitmap(reduxSuitBitmap, null, dstRect, null);
    }

    private String computeItemPercentText(int collectedItems, int collectedBeams) {
        if (romBytes == null) return "--.-%";
        int percent = SuperMetroidItemPercent.compute(romBytes, collectedItems, collectedBeams,
                wramOffset -> {
                    byte[] b = activity.nativeReadSystemRam(wramOffset, 2);
                    return b != null && b.length >= 2 ? readUint16LE(b, 0) : 0;
                });
        return percent < 0 ? "--.-%" : percent + ".0%";
    }

    private String computeTimeText() {
        byte[] timeBlock = activity.nativeReadSystemRam(TIME_BLOCK_OFFSET, TIME_BLOCK_LENGTH);
        if (timeBlock == null || timeBlock.length < TIME_BLOCK_LENGTH) return "--:--:--";
        int seconds = readUint16LE(timeBlock, OFF_GAME_TIME_SECONDS - TIME_BLOCK_OFFSET);
        int minutes = readUint16LE(timeBlock, OFF_GAME_TIME_MINUTES - TIME_BLOCK_OFFSET);
        int hours = readUint16LE(timeBlock, OFF_GAME_TIME_HOURS - TIME_BLOCK_OFFSET);
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    // A single stat box (label above value, e.g. "ITEMS" / "100.0%") -
    // matches MapStatusView.java's own drawStatBox.
    private void drawStatBox(Canvas canvas, float x, float top, float w, float h, String label, String value) {
        RectF box = new RectF(x, top, x + w, top + h);
        drawPixelBox(canvas, box, COL_SLOT_BG, COL_BORDER_DARK, true);
        float labelSize = h * 0.22f;
        PixelFont.drawText(canvas, label, box.left + w * 0.08f, box.top + h * 0.12f,
                PixelFont.pixelSizeForHeight(labelSize), COL_DIM_GRAY, Paint.Align.LEFT);
        float valueSize = h * 0.36f;
        PixelFont.drawText(canvas, value, box.left + w * 0.08f, box.bottom - h * 0.14f - valueSize,
                PixelFont.pixelSizeForHeight(valueSize), Color.WHITE, Paint.Align.LEFT);
    }

    // One column of stacked equipment boxes (e.g. SUIT above MISC), each
    // listing every item in that group with a filled or hollow bullet
    // marker showing collected/not-collected - matches MapStatusView.
    // java's own drawEquipColumn, minus the returned box rects (only
    // needed there for the wireframe callout lines this fork doesn't have
    // yet).
    private void drawEquipColumn(Canvas canvas, float x, float top, float w, float h,
                                  int collectedItems, int collectedBeams, EquipGroup... groups) {
        int totalEntries = 0;
        for (EquipGroup g : groups) totalEntries += g.bits.length;
        float gap = h * 0.04f;
        float unitH = (h - gap * (groups.length - 1)) / totalEntries;

        float titleSize = unitH * 0.34f;
        float entrySize = unitH * 0.40f;
        float maxLabelW = w * 0.80f;
        float entryPixelSize = PixelFont.pixelSizeForHeight(entrySize);
        float widestLabelW = 0f;
        for (EquipGroup g : groups) {
            for (String label : g.labels) {
                widestLabelW = Math.max(widestLabelW, PixelFont.measureWidth(label, entryPixelSize));
            }
        }
        if (widestLabelW > maxLabelW) {
            float shrink = maxLabelW / widestLabelW;
            entryPixelSize *= shrink;
            entrySize *= shrink;
        }

        float y = top;
        for (EquipGroup g : groups) {
            int bits = g.isBeam ? collectedBeams : collectedItems;
            float boxH = unitH * g.bits.length;
            RectF box = new RectF(x, y, x + w, y + boxH);
            drawPixelBox(canvas, box, COL_SLOT_BG, COL_BORDER_DARK, false);

            PixelFont.drawText(canvas, g.title, box.left + w * 0.06f, box.top + titleSize * 0.5f,
                    PixelFont.pixelSizeForHeight(titleSize), COL_ACCENT, Paint.Align.LEFT);

            float rowH = (boxH - titleSize * 1.6f) / g.bits.length;
            float rowY = box.top + titleSize * 1.6f;
            for (int i = 0; i < g.bits.length; i++) {
                boolean collected = (bits & g.bits[i]) != 0;
                float cy = rowY + i * rowH + rowH * 0.65f;
                float dotR = entrySize * 0.28f;
                float dotCx = box.left + w * 0.09f, dotCy = cy - entrySize * 0.32f;
                paint.setStyle(collected ? Paint.Style.FILL : Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1.5f, dotR * 0.25f));
                paint.setColor(collected ? COL_ACCENT : COL_BORDER_DARK);
                canvas.drawCircle(dotCx, dotCy, dotR, paint);
                int textColor = collected ? Color.WHITE : COL_DIM_GRAY;
                PixelFont.drawText(canvas, g.labels[i], box.left + w * 0.16f, cy - entrySize * 0.7f,
                        entryPixelSize, textColor, Paint.Align.LEFT);
            }
            y += boxH + gap;
        }
    }

    // Explored-bits reads for the world map are heavier than the room
    // view's (one 2048-byte read covering all 8 areas' saved-explored
    // slices, plus the live current-area bitmap) and only the world view
    // needs them, so this is only called from drawWorldMap (i.e. only
    // while worldView is actually showing), same throttle interval as the
    // room view's ensureAreaMapLoaded.
    private long worldMapLastCheckUptimeMs = -1;

    private void ensureWorldMapRefreshed() {
        if (romBytes == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - worldMapLastCheckUptimeMs < AREA_MAP_CHECK_INTERVAL_MS) return;
        worldMapLastCheckUptimeMs = now;

        byte[] roomBlock = activity.nativeReadSystemRam(ROOM_BLOCK_OFFSET, ROOM_BLOCK_LENGTH);
        if (roomBlock == null || roomBlock.length < ROOM_BLOCK_LENGTH) return;
        int currentArea = readUint16LE(roomBlock, OFF_AREA_INDEX - ROOM_BLOCK_OFFSET);

        byte[] currentAreaExplored = activity.nativeReadSystemRam(OFF_MAP_TILES_EXPLORED, MAP_TILES_EXPLORED_LENGTH);
        byte[] savedExplored = activity.nativeReadSystemRam(OFF_EXPLORED_MAP_TILES_SAVED, EXPLORED_MAP_TILES_SAVED_LENGTH);
        byte[] mapStationBytes = activity.nativeReadSystemRam(OFF_MAP_STATION_BYTE_ARRAY, MAP_STATION_BYTE_ARRAY_LENGTH);
        if (savedExplored == null || savedExplored.length < EXPLORED_MAP_TILES_SAVED_LENGTH) return;

        byte[][] exploredPerArea = new byte[SuperMetroidWorldMap.AREA_COUNT][];
        boolean[] mapStationOwned = new boolean[SuperMetroidWorldMap.AREA_COUNT];
        for (int a = 0; a < SuperMetroidWorldMap.AREA_COUNT; a++) {
            if (a == currentArea && currentAreaExplored != null && currentAreaExplored.length >= MAP_TILES_EXPLORED_LENGTH) {
                exploredPerArea[a] = currentAreaExplored;
            } else {
                byte[] slice = new byte[256];
                System.arraycopy(savedExplored, a * 256, slice, 0, 256);
                exploredPerArea[a] = slice;
            }
            mapStationOwned[a] = mapStationBytes != null && a < mapStationBytes.length && mapStationBytes[a] != 0;
        }

        worldMap.refresh(romBytes, exploredPerArea, mapStationOwned, currentArea);
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

    // Manual drag pan on top of the Samus-centered baseline (see onScroll),
    // in map-tile units - reset whenever the area changes, same as
    // MapStatusView.java's own panOffsetX/Y.
    private float roomPanOffsetX = 0f, roomPanOffsetY = 0f;
    private int lastRoomViewArea = -1;
    // Cached tiles-per-screen-pixel from the most recent drawRoomMap call,
    // for onScroll to convert a drag's screen-pixel delta into a tile-space
    // pan delta.
    private float roomViewTilesPerPixel = 0f;

    // Same idea as roomPanOffsetX/Y/roomViewTilesPerPixel above, but for
    // the world view - reset whenever entering the world view (see
    // onTouchEvent's roomWorldToggleBtn handling), matching
    // MapStatusView.java's own enterWorldView.
    private float worldPanOffsetX = 0f, worldPanOffsetY = 0f;
    private float worldViewTilesPerPixel = 0f;
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
                // Tint per area, same accent colors and formula the world
                // view already uses (SuperMetroidWorldMap.tintAreaPixels) -
                // was never applied to this single-room view at all, a
                // real bug: every area rendered in the ROM's raw, mostly
                // blue/cyan palette regardless of which area it actually
                // was, confirmed on-device ("in Tourian but the map is
                // blue instead of purple").
                SuperMetroidWorldMap.tintAreaPixelsForColor(pixels, SuperMetroidWorldMap.remapAreaForColor(areaIndex));
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

    // Drag-to-pan state - real touch handling, not just ACTION_UP taps like
    // before. dragLastX/Y track the previous move point (for computing a
    // per-move delta); dragTotalMoved accumulates total travel distance so
    // ACTION_UP can tell a genuine tap (buttons/tabs/ammo icons - including
    // tap-to-arm/tap-again-to-disarm, a real WRAM write via
    // nativeWriteSystemRam, matching how SM2_SetSelectedAmmo does it on
    // that project's own native side) apart from the end of a drag (which
    // should NOT also fire whatever's under the finger) - a small
    // threshold rather than zero, so a finger that trembles slightly
    // during an intended tap doesn't get misread as a pan. Matches
    // MapStatusView.java's own onScroll-based approach, adapted to a plain
    // View (no GestureDetector here) since this is the only gesture beyond
    // simple taps this view needs.
    private static final float TAP_SLOP_PX = 12f;
    private float dragLastX, dragLastY, dragTotalMoved;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isPlayingLive())
            return true; // dim overlay is up - not a real gameplay tap target right now

        float x = event.getX(), y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragLastX = x;
                dragLastY = y;
                dragTotalMoved = 0f;
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = x - dragLastX, dy = y - dragLastY;
                dragTotalMoved += Math.abs(dx) + Math.abs(dy);
                // Only the MAP tab's own map area pans - not a drag
                // starting on the tab bar or a button, and not while a
                // real pan hasn't actually started yet (dragTotalMoved
                // still under the tap threshold) so a tap's own tiny
                // finger jitter never nudges the view.
                if (currentTab == Tab.MAP && mapTapRect.contains(dragLastX, dragLastY)
                        && dragTotalMoved > TAP_SLOP_PX) {
                    if (worldView) {
                        worldPanOffsetX -= dx * worldViewTilesPerPixel;
                        worldPanOffsetY -= dy * worldViewTilesPerPixel;
                    } else {
                        roomPanOffsetX -= dx * roomViewTilesPerPixel;
                        roomPanOffsetY -= dy * roomViewTilesPerPixel;
                    }
                    invalidate();
                }
                dragLastX = x;
                dragLastY = y;
                return true;
            }

            case MotionEvent.ACTION_UP:
                break; // handled below - a real tap, not a drag

            default:
                return true;
        }

        if (dragTotalMoved > TAP_SLOP_PX)
            return true; // that ACTION_UP ended a drag, not a tap - don't also fire a button/tab underneath it

        byte[] block = activity.nativeReadSystemRam(BLOCK_OFFSET, BLOCK_LENGTH);
        if (block == null || block.length < BLOCK_LENGTH) return true;

        int currentIndex = readUint16LE(block, OFF_HUD_ITEM_INDEX - BLOCK_OFFSET);
        for (int i = 0; i < weaponRects.length; i++) {
            if (!weaponRects[i].contains(x, y)) continue;
            boolean alreadySelected = currentIndex == AMMO_SLOTS[i];
            setHudItemIndex(alreadySelected ? AMMO_NONE : AMMO_SLOTS[i]);
            invalidate(); // don't wait for the next poll tick to show it
            return true;
        }

        for (int i = 0; i < tabButtonRects.length; i++) {
            if (!tabButtonRects[i].contains(x, y)) continue;
            currentTab = Tab.values()[i];
            invalidate();
            return true;
        }

        if (currentTab == Tab.MAP) {
            if (roomWorldToggleBtn.contains(x, y)) {
                worldView = !worldView;
                // Reset zoom/pan on entering either view fresh - a pan
                // offset or zoom level from the OTHER view's own
                // coordinate space is meaningless once switched, matching
                // MapStatusView.java's own enterWorldView/room-view resets.
                if (worldView) {
                    worldZoomFactor = MIN_WORLD_ZOOM;
                    worldPanOffsetX = 0f;
                    worldPanOffsetY = 0f;
                } else {
                    roomZoomFactor = MIN_ZOOM;
                    roomPanOffsetX = 0f;
                    roomPanOffsetY = 0f;
                }
                invalidate();
                return true;
            }
            if (zoomInBtn.contains(x, y) || zoomOutBtn.contains(x, y)) {
                boolean zoomIn = zoomInBtn.contains(x, y);
                if (worldView) {
                    worldZoomFactor = zoomIn
                            ? Math.min(MAX_WORLD_ZOOM, worldZoomFactor * ZOOM_BUTTON_STEP)
                            : Math.max(MIN_WORLD_ZOOM, worldZoomFactor / ZOOM_BUTTON_STEP);
                } else {
                    roomZoomFactor = zoomIn
                            ? Math.min(MAX_ZOOM, roomZoomFactor * ZOOM_BUTTON_STEP)
                            : Math.max(MIN_ZOOM, roomZoomFactor / ZOOM_BUTTON_STEP);
                }
                invalidate();
                return true;
            }
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
