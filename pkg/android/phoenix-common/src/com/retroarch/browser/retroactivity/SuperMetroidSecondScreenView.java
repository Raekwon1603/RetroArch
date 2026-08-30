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

    // Same palette as MapStatusView.java's own COL_* constants.
    private static final int COL_BG = Color.rgb(30, 33, 44);
    private static final int COL_PANEL_BG = Color.rgb(38, 42, 56);
    private static final int COL_BORDER_DARK = Color.rgb(58, 64, 86);
    private static final int COL_DIM_GRAY = Color.rgb(105, 110, 128);
    private static final int COL_ENERGY_PIP = Color.rgb(204, 71, 145);
    private static final int COL_ACCENT = Color.rgb(255, 158, 68);

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
            int[][] icons = SuperMetroidRomIcons.decodeAmmoIcons(romPath);
            if (icons != null) {
                Bitmap missile = Bitmap.createBitmap(icons[0], 24, 16, Bitmap.Config.ARGB_8888);
                Bitmap superMissile = Bitmap.createBitmap(icons[1], 16, 16, Bitmap.Config.ARGB_8888);
                Bitmap powerBomb = Bitmap.createBitmap(icons[2], 16, 16, Bitmap.Config.ARGB_8888);
                uiHandler.post(() -> {
                    missileIconBitmap = missile;
                    superMissileIconBitmap = superMissile;
                    powerBombIconBitmap = powerBomb;
                });
            }
            iconsLoadInFlight = false;
        }, "SuperMetroidRomIconDecode").start();
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

        // Below the strip: reserved for the real in-game map, not yet
        // built (see docs/retroarch-fork-notes.md's Status section) - a
        // small centered label rather than leaving it blank/unexplained.
        String placeholder = "MAP VIEW - COMING SOON";
        float placeholderPixelSize = PixelFont.pixelSizeForHeight(stripH * 0.22f);
        PixelFont.drawText(canvas, placeholder, w / 2f, strip.bottom + (h - strip.bottom) / 2f,
                placeholderPixelSize, COL_DIM_GRAY, Paint.Align.CENTER);
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
