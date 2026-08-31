package com.retroarch.browser.retroactivity;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Persistent, incrementally-updated world-map composite (all 6 real Super
 * Metroid areas at their true relative positions, tinted per area, joined
 * by real inter-area door connectors) for the dual-screen fork's second
 * screen (see super_metroid-android's docs/retroarch-fork-notes.md).
 *
 * A straight port of that project's own real, working implementation -
 * MapStatusView.java's WORLD_AREA_LAYOUT/WORLD_AREA_COLORS/WORLD_CONNECTORS
 * tables (copied verbatim - real, hard-won values: a BFS spanning-tree
 * layout solve anchoring each area to one real door connection, and every
 * actual inter-area door in the game) and its ensureWorldAreaFresh/
 * drawWorldConnector/tintAreaPixels/rasterizeThickLine algorithm - not a
 * reimplementation from scratch. Round-robin refreshes one area every
 * WORLD_REFRESH_STRIDE calls to refresh(), compositing only that area's
 * actually-explored pixels onto a persistent canvas (unexplored pixels
 * left transparent, so the shared dark background and already-drawn
 * neighbors/connectors are never overwritten) - real work (a full area
 * decode), so spread across calls rather than done for all 6 areas at
 * once every time.
 *
 * Differences from the original: this fork has no live JNI access to the
 * decompiled game logic (bsnes-hd runs the ROM as an opaque black box), so
 * explored-tile bits come from nativeReadSystemRam instead of direct field
 * reads, and area-name labels are left out for now (real ROM-decoded
 * labels, same as the ammo icons - a real follow-up, not done here) -
 * areas are identified by their tint color only in this first version.
 * Zoom/pan are also not implemented yet (see docs/retroarch-fork-notes.md's
 * Status section) - this always auto-frames to the explored regions'
 * combined extent, same as the original's own MIN_WORLD_ZOOM baseline.
 */
final class SuperMetroidWorldMap {

    static final int AREA_COUNT = 6;

    // {declMinX, declMinY, declMaxX, declMaxY, destX, destY} in shared-canvas
    // tile units - copied verbatim from MapStatusView.java's
    // WORLD_AREA_LAYOUT. See that file's own comment for what each column
    // means and why some areas' declared boxes overlap (real, not a bug).
    static final float[][] AREA_LAYOUT = {
            {6, 0, 57, 19, 6.07f, 4.00f},    // Crateria (anchor, unchanged)
            {5, 0, 58, 20, 2.07f, 12.00f},   // Brinstar
            {2, 0, 38, 18, 30.07f, 30.00f},  // Norfair
            {10, 10, 22, 20, 43.07f, 4.00f}, // Wrecked Ship
            {10, 0, 43, 20, 28.07f, 14.00f}, // Maridia
            {11, 9, 22, 22, 8.07f, 13.00f},  // Tourian
    };

    // Distinct accent color per area, copied verbatim from
    // MapStatusView.java's WORLD_AREA_COLORS.
    static final int[] AREA_COLORS = {
            Color.rgb(150, 165, 210), // Crateria - cool gray-blue
            Color.rgb(110, 210, 110), // Brinstar - green
            Color.rgb(235, 110, 90),  // Norfair - red-orange
            Color.rgb(210, 180, 110), // Wrecked Ship - tan/yellow
            Color.rgb(255, 100, 100), // Maridia - red
            Color.rgb(220, 110, 190), // Tourian - magenta/pink
    };

    // {areaA, ax, ay, areaB, bx, by} - copied verbatim from
    // MapStatusView.java's WORLD_CONNECTORS (every real inter-area door in
    // the game, in shared-canvas tile units at each door's own true
    // position).
    static final float[][] CONNECTORS = {
            {0, 6.07f, 12.00f, 1, 6.07f, 12.00f},
            {1, 23.07f, 20.00f, 0, 23.07f, 21.00f},
            {0, 18.07f, 21.00f, 5, 17.07f, 21.00f},
            {5, 17.07f, 13.00f, 0, 17.07f, 13.00f},
            {1, 34.07f, 16.00f, 0, 34.07f, 11.00f},
            {1, 36.07f, 19.00f, 4, 30.07f, 21.00f},
            {0, 45.07f, 8.00f, 3, 45.07f, 8.00f},
            {0, 45.07f, 4.00f, 3, 45.07f, 4.00f},
            {0, 45.07f, 5.00f, 3, 45.07f, 5.00f},
            {0, 43.07f, 7.00f, 3, 43.07f, 7.00f},
            {3, 54.07f, 8.00f, 0, 49.07f, 8.00f},
            {0, 52.07f, 14.00f, 4, 52.07f, 14.00f},
            {4, 28.07f, 32.00f, 1, 34.07f, 30.00f},
    };

    private static final float[] AREA_BBOX_TILES = new float[AREA_COUNT];
    static {
        for (int a = 0; a < AREA_COUNT; a++) {
            float[] l = AREA_LAYOUT[a];
            AREA_BBOX_TILES[a] = (l[2] - l[0]) * (l[3] - l[1]);
        }
    }

    static final int CANVAS_TILES_W = 70, CANVAS_TILES_H = 57;
    static final int CANVAS_PX_W = CANVAS_TILES_W * 8, CANVAS_PX_H = CANVAS_TILES_H * 8;

    private static final int WORLD_REFRESH_STRIDE = 4;
    private static final float CONNECTOR_HALF_WIDTH = 5f;
    private static final float CONNECTOR_MAX_GAP_TILES = 3f;

    // Persistent composite state - one instance per second-screen session
    // (owned by SuperMetroidSecondScreenView), not static, so a fresh game
    // session starts from a blank composite rather than carrying over a
    // previous ROM's explored state.
    final int[] compositePixels = new int[CANVAS_PX_W * CANVAS_PX_H];
    final byte[] pixelOwner = new byte[CANVAS_PX_W * CANVAS_PX_H];
    final boolean[] areaDrawn = new boolean[AREA_COUNT];
    private final boolean[] connectorDrawn = new boolean[CONNECTORS.length];
    private int areaCursor = 0;
    private int frameCounter = 0;
    Bitmap compositeBitmap;
    boolean dirty = false;
    // Real bug fix: this composite is built once and never cleared, so
    // loading a DIFFERENT save file mid-session (e.g. switching from a
    // near-100%-explored file to a fresh one) left the earlier file's
    // fully-explored world map showing, confirmed on-device. There is no
    // direct "a different save just loaded" signal available (no live
    // save-slot-index WRAM field this reads), so this instead notices the
    // one thing that's unambiguous: a real save always has explored-tile
    // COUNTS that only grow while playing it - if area a's live count ever
    // comes back LOWER than what the composite already reflects, that can
    // only mean a different (less-progressed) file just loaded, and that
    // area's own composited region is reset (unclaimed, made transparent
    // again) before compositing resumes from the new file's real state.
    private final int[] lastExploredCount = new int[AREA_COUNT];
    // Real bug fix: picking up an area's Map Station item (which reveals
    // that area's whole known room layout, dimmed, even into rooms never
    // actually visited) changes what an area's OWN decode looks like
    // without necessarily changing its explored-tile COUNT at all in that
    // same moment - confirmed on-device as a real ~a-minute delay before a
    // freshly-collected Map Station's reveal actually showed, since nothing
    // told this area it needed to re-decode until its explored count
    // happened to change again for an unrelated reason (walking into a
    // new room). Tracking ownership per area alongside the explored count
    // and treating a change in EITHER as "needs a real re-decode" fixes
    // this the same way the explored-count check already works.
    private final boolean[] lastMapStationOwned = new boolean[AREA_COUNT];

    // Real ROM-decoded area-name labels (e.g. "BRINSTAR") - see
    // SuperMetroidRomMap.renderAreaLabel. Cheap (12 tiles each), decoded
    // once per area as soon as the ROM is available, not tied to the
    // round-robin refresh above. labelCenterX/Y are each area's own real
    // explored-pixel centroid (in canvas TILE units, matching
    // AREA_LAYOUT's own units) - NOT the declared layout box's raw corner,
    // which is deliberately oversized/overlapping between areas (see
    // AREA_LAYOUT's own comment) and could land a label inside a
    // DIFFERENT area's art entirely. Recomputed by refreshArea every time
    // it runs (ownership can shift between areas on a later redraw via the
    // smaller-box-wins tiebreak, so this can't be accumulated
    // incrementally without drifting wrong over time - same reasoning as
    // MapStatusView.java's own recomputeWorldLabelCentroids).
    final Bitmap[] labelBitmaps = new Bitmap[AREA_COUNT];
    final boolean[] haveLabel = new boolean[AREA_COUNT];
    final float[] labelCenterX = new float[AREA_COUNT];
    final float[] labelCenterY = new float[AREA_COUNT];

    SuperMetroidWorldMap() {
        java.util.Arrays.fill(pixelOwner, (byte) -1);
    }

    /**
     * Decodes any area-name labels not yet decoded (cheap - real work only
     * happens once per area, ever, for the life of this instance). Call
     * once per refresh() alongside it.
     */
    void ensureLabelsLoaded(byte[] rom) {
        if (rom == null) return;
        for (int a = 0; a < AREA_COUNT; a++) {
            if (haveLabel[a]) continue;
            int[] pixels = SuperMetroidRomMap.renderAreaLabel(rom, a);
            if (pixels == null) continue;
            labelBitmaps[a] = Bitmap.createBitmap(pixels, SuperMetroidRomMap.LABEL_TILES * 8, 8, Bitmap.Config.ARGB_8888);
            haveLabel[a] = true;
        }
    }

    /**
     * Refreshes the world composite for this tick. The CURRENT area (the
     * one actively being explored right now) is checked and, if its
     * explored bits actually changed since last time, re-decoded and
     * composited every single call - real lag was confirmed on-device
     * otherwise (walking into a new room could take several seconds to
     * show, since it was only ever picked up on that area's turn in the
     * round-robin below). The other 5 areas (not being actively explored
     * right now) still use the cheaper round-robin - one of them gets a
     * chance to refresh every WORLD_REFRESH_STRIDE calls, since keeping
     * every one of the 6 areas current every tick would mean back-to-back
     * full decodes, real work this fork's Java tile decoder (unlike the
     * original app's native one) can't do that often for free. Call once
     * per second-screen poll tick (see SuperMetroidSecondScreenView's own
     * poll cadence).
     *
     * @param rom Loaded ROM bytes (SuperMetroidRom.load).
     * @param exploredBitsPerArea AREA_COUNT arrays of 256 bytes each - area
     *                            a's explored-tile bitmap (live
     *                            map_tiles_explored for the current area,
     *                            explored_map_tiles_saved's slice for any
     *                            other - see SuperMetroidSecondScreenView
     *                            for which is which).
     * @param mapStationOwned AREA_COUNT booleans - whether area a's Map
     *                        Station item is owned.
     * @param currentArea The area index Samus is in right now (0-5, or
     *                     anything else to skip the every-tick fast path
     *                     entirely - e.g. Ceres/debug areas).
     */
    void refresh(byte[] rom, byte[][] exploredBitsPerArea, boolean[] mapStationOwned, int currentArea) {
        frameCounter++;
        ensureLabelsLoaded(rom);

        // Cheap invalidation pass, every tick, for ALL 6 areas - just a
        // countSetBits (a 256-byte popcount) each, not a real decode.
        // Was only ever checked on an area's own round-robin turn (up to
        // WORLD_REFRESH_STRIDE ticks apart), so clearing a previous save
        // file's stale areas after loading a different one visibly
        // "chunked in" one area every ~800ms instead of clearing all at
        // once - confirmed on-device. Detecting "went backwards" (a
        // different/less-progressed file just loaded) is cheap enough to
        // just always do; only the expensive re-decode of NEW real content
        // stays throttled below.
        for (int a = 0; a < AREA_COUNT; a++) {
            byte[] bits = exploredBitsPerArea[a];
            if (bits == null || !areaDrawn[a]) continue;
            int count = countSetBits(bits);
            if (count < lastExploredCount[a]) {
                resetArea(a);
                lastExploredCount[a] = count;
                lastMapStationOwned[a] = false;
                recomputeLabelCentroids();
            }
        }

        if (currentArea >= 0 && currentArea < AREA_COUNT) {
            byte[] currentBits = exploredBitsPerArea[currentArea];
            if (currentBits != null) {
                int currentCount = countSetBits(currentBits);
                boolean currentStation = mapStationOwned[currentArea];
                if (currentCount > 0 && (currentCount != lastExploredCount[currentArea]
                        || currentStation != lastMapStationOwned[currentArea] || !areaDrawn[currentArea])) {
                    refreshArea(rom, currentArea, currentBits, currentStation, currentCount);
                }
            }
        }

        // Backlog burst: an area with real explored content that has never
        // actually been decoded yet (a fresh session, or a fully-explored
        // save just loaded) skips the round-robin throttle - confirmed
        // on-device as real, visible multi-second "areas popping in one at
        // a time" lag otherwise on a 100%-explored save (up to
        // WORLD_REFRESH_STRIDE ticks x 5 remaining areas before everything
        // settled). Capped to ONE undrawn area's decode per call though
        // (not all remaining at once): this runs synchronously on the UI
        // thread (SuperMetroidSecondScreenView.ensureWorldMapRefreshed,
        // called from onDraw) - decoding all 6 areas in a single call would
        // risk the same class of real UI-thread stall that caused a
        // genuine ANR earlier when icon loading ran inline (see
        // docs/retroarch-fork-notes.md). One area per ~200ms poll tick is
        // still far faster than the old fixed WORLD_REFRESH_STRIDE cadence
        // for a real backlog, while keeping each call's own work bounded.
        for (int a = 0; a < AREA_COUNT; a++) {
            if (a == currentArea || areaDrawn[a]) continue;
            byte[] bits = exploredBitsPerArea[a];
            if (bits == null) continue;
            int count = countSetBits(bits);
            if (count > 0) {
                refreshArea(rom, a, bits, mapStationOwned[a], count);
                break; // one undrawn area per call - see comment above
            }
        }

        if (frameCounter % WORLD_REFRESH_STRIDE != 0) return;

        int a = areaCursor;
        areaCursor = (areaCursor + 1) % AREA_COUNT;
        if (a == currentArea) return; // already handled above, every tick

        byte[] exploredBits = exploredBitsPerArea[a];
        if (exploredBits == null) return;
        int exploredCount = countSetBits(exploredBits);
        if (exploredCount == 0) return; // never explored in this file - nothing to do (already handled by the invalidation pass above if it WAS explored before)
        boolean haveStation = mapStationOwned[a];
        if (exploredCount == lastExploredCount[a] && haveStation == lastMapStationOwned[a] && areaDrawn[a]) return; // unchanged - skip the real decode work
        refreshArea(rom, a, exploredBits, haveStation, exploredCount);
    }

    private void refreshArea(byte[] rom, int a, byte[] exploredBits, boolean haveMapStation, int exploredCount) {
        if (exploredCount == 0) {
            // Same reset case as the round-robin path above, but reachable
            // from the current-area fast path too (e.g. a soft reset
            // landing you in a starting room whose explored bit hasn't
            // been set yet for one single frame).
            if (areaDrawn[a]) {
                resetArea(a);
                lastExploredCount[a] = 0;
                lastMapStationOwned[a] = false;
                recomputeLabelCentroids();
            }
            return;
        }
        if (areaDrawn[a] && exploredCount < lastExploredCount[a]) {
            resetArea(a);
        }
        lastExploredCount[a] = exploredCount;
        lastMapStationOwned[a] = haveMapStation;

        int[] mapPixels = SuperMetroidRomMap.renderAreaMap(rom, a, exploredBits, haveMapStation);
        if (mapPixels == null) return;

        tintAreaPixels(mapPixels, a);
        SuperMetroidRomMap.makeUnexploredTransparent(mapPixels);

        float[] l = AREA_LAYOUT[a];
        int declMinX = (int) l[0], declMinY = (int) l[1], declMaxX = (int) l[2], declMaxY = (int) l[3];
        int srcPx0 = declMinX * 8, srcPy0 = declMinY * 8;
        int destPx0 = (int) (l[4] * 8), destPy0 = (int) (l[5] * 8);
        int w = (declMaxX - declMinX) * 8, h = (declMaxY - declMinY) * 8;
        int mapStride = SuperMetroidRomMap.GRID_W * 8;

        // Manual per-pixel composite (not a plain blit): area boxes
        // routinely overlap, and each area gets redrawn on its own
        // round-robin turn - smaller-declared-box-wins tiebreak so overlap
        // zones settle instead of flickering between areas. See
        // MapStatusView.java's own AREA_BBOX_TILES comment for why.
        for (int y = 0; y < h; y++) {
            int destRow = (destPy0 + y) * CANVAS_PX_W + destPx0;
            int srcRow = (srcPy0 + y) * mapStride + srcPx0;
            for (int x = 0; x < w; x++) {
                int srcPixel = mapPixels[srcRow + x];
                if ((srcPixel >>> 24) == 0) continue; // transparent = unexplored
                int destIdx = destRow + x;
                byte owner = pixelOwner[destIdx];
                if (owner != -1 && owner != a && AREA_BBOX_TILES[a] >= AREA_BBOX_TILES[owner]) continue;
                compositePixels[destIdx] = srcPixel;
                pixelOwner[destIdx] = (byte) a;
            }
        }
        areaDrawn[a] = true;
        dirty = true;
        recomputeLabelCentroids();

        // Bake in any connector whose both endpoints are now drawn.
        for (int i = 0; i < CONNECTORS.length; i++) {
            if (connectorDrawn[i]) continue;
            float[] c = CONNECTORS[i];
            int areaA = (int) c[0], areaB = (int) c[3];
            if (areaDrawn[areaA] && areaDrawn[areaB]) {
                float gap = (float) Math.hypot(c[1] - c[4], c[2] - c[5]);
                if (gap <= CONNECTOR_MAX_GAP_TILES) drawConnector(c);
                connectorDrawn[i] = true;
            }
        }
    }

    // Recomputes labelCenterX/Y from pixelOwner - a full scan rather than
    // incrementally tracked during the composite loop above, since
    // ownership of any given pixel can change hands between areas on a
    // LATER redraw (the smaller-box-wins tiebreak can let a later-drawn
    // small area reclaim pixels a larger area initially won), which would
    // make an incrementally-accumulated centroid silently drift wrong over
    // time. Matches MapStatusView.java's own recomputeWorldLabelCentroids.
    // Only runs once per refreshArea call, so a full-canvas scan here is
    // cheap relative to that cadence.
    private void recomputeLabelCentroids() {
        long[] sumX = new long[AREA_COUNT], sumY = new long[AREA_COUNT];
        int[] count = new int[AREA_COUNT];
        for (int y = 0; y < CANVAS_PX_H; y++) {
            int row = y * CANVAS_PX_W;
            for (int x = 0; x < CANVAS_PX_W; x++) {
                byte owner = pixelOwner[row + x];
                if (owner < 0) continue;
                sumX[owner] += x;
                sumY[owner] += y;
                count[owner]++;
            }
        }
        for (int a = 0; a < AREA_COUNT; a++) {
            if (count[a] == 0) continue;
            labelCenterX[a] = (sumX[a] / (float) count[a]) / 8f;
            labelCenterY[a] = (sumY[a] / (float) count[a]) / 8f;
        }
    }

    /** Pushes compositePixels into compositeBitmap if refresh() changed anything since the last call. */
    void ensureBitmapUpToDate() {
        if (!dirty) return;
        if (compositeBitmap == null) {
            compositeBitmap = Bitmap.createBitmap(CANVAS_PX_W, CANVAS_PX_H, Bitmap.Config.ARGB_8888);
        }
        compositeBitmap.setPixels(compositePixels, 0, CANVAS_PX_W, 0, 0, CANVAS_PX_W, CANVAS_PX_H);
        dirty = false;
    }

    // Un-claims every composite pixel this area owns (back to transparent/
    // unowned) and marks it not-yet-drawn, so the next refresh() decode for
    // it starts clean instead of a stale earlier file's explored art
    // lingering underneath the new file's (possibly smaller) explored
    // region. Connectors touching this area are also un-baked, since a
    // connector's own gap check depends on both endpoint areas actually
    // being explored in the CURRENT file, not whatever file drew it
    // originally.
    private void resetArea(int a) {
        for (int i = 0; i < pixelOwner.length; i++) {
            if (pixelOwner[i] == a) {
                pixelOwner[i] = -1;
                compositePixels[i] = 0;
            }
        }
        areaDrawn[a] = false;
        // Real bug fixed here: connector-bridge pixels are never tracked in
        // pixelOwner (they're not owned by either endpoint area - see
        // drawConnector's own comment), so the loop above never erased
        // them. Setting connectorDrawn[i]=false alone only stops it from
        // being baked in a SECOND time later - it does not erase the pixels
        // already drawn, so a reset area used to leave every connector
        // that touched it as a permanent orphaned line/dot floating with
        // nothing around it, confirmed on-device (stray colored fragments
        // scattered across the canvas after loading a different save
        // file). Actually erasing them (rasterizing the same segment back
        // to fully transparent) before clearing connectorDrawn is the fix.
        for (int i = 0; i < CONNECTORS.length; i++) {
            float[] c = CONNECTORS[i];
            if ((int) c[0] == a || (int) c[3] == a) {
                if (connectorDrawn[i]) eraseConnector(c);
                connectorDrawn[i] = false;
            }
        }
        dirty = true;
    }

    private void eraseConnector(float[] c) {
        float ax = c[1] * 8, ay = c[2] * 8;
        float bx = c[4] * 8, by = c[5] * 8;
        // Slightly wider than the original draw (CONNECTOR_HALF_WIDTH is
        // the fill's own half-width; the border stroke goes right up to
        // it) so no border-stroke fringe survives at the fill/border seam.
        rasterizeThickLine(ax, ay, bx, by, CONNECTOR_HALF_WIDTH + 0.5f, 0);
    }

    private static int countSetBits(byte[] bits) {
        int count = 0;
        for (byte b : bits) count += Integer.bitCount(b & 0xFF);
        return count;
    }

    // Multiplies each RGB channel by the area's own accent color (0-1
    // range), tinting the decoded room art without replacing its shading -
    // matches MapStatusView.java's own tintAreaPixels. Real bug fixed here:
    // was tinting EVERY pixel including unexplored ones, which shifts them
    // off the exact UNEXPLORED_COLOR value makeUnexploredTransparent (below)
    // matches against - confirmed on-device as the actual cause of areas
    // rendering as solid opaque rectangles instead of just their explored
    // tiles (nothing was ever becoming transparent, since the equality
    // check no longer matched anything after tinting). Skipping unexplored
    // pixels here, same as the original does, is the fix.
    private static final int UNEXPLORED_MASK = SuperMetroidRomMap.UNEXPLORED_COLOR;
    private static void tintAreaPixels(int[] pixels, int area) {
        tintAreaPixelsForColor(pixels, remapAreaForColor(area));
    }

    // area 6 (Ceres) and 7 (unused debug) aren't part of the 6-color
    // AREA_COLORS table - matches MapStatusView.java's own
    // remapAreaForColor, falls back to Crateria's color.
    static int remapAreaForColor(int area) {
        return (area < 0 || area >= AREA_COUNT) ? 0 : area;
    }

    // Tints pixels (in place) with AREA_COLORS[colorIndex] directly, no
    // area-index remapping - shared by the world composite (tintAreaPixels,
    // above) and SuperMetroidSecondScreenView's own single-room view
    // (drawRoomMap), so both use the exact same tint so a given area reads
    // as the same color in both views. Multiplies each RGB channel rather
    // than replacing it, so walls/floor/shading detail from the real ROM
    // art is preserved - only the hue shifts. Unexplored-fill pixels are
    // skipped so unexplored area stays neutral, not a tinted box.
    static void tintAreaPixelsForColor(int[] pixels, int colorIndex) {
        int accent = AREA_COLORS[colorIndex];
        float tr = Color.red(accent) / 255f, tg = Color.green(accent) / 255f, tb = Color.blue(accent) / 255f;
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            if (p == UNEXPLORED_MASK) continue;
            int r = (int) (((p >> 16) & 0xFF) * tr);
            int g = (int) (((p >> 8) & 0xFF) * tg);
            int b = (int) ((p & 0xFF) * tb);
            pixels[i] = (p & 0xFF000000) | (r << 16) | (g << 8) | b;
        }
    }

    private void drawConnector(float[] c) {
        int areaA = (int) c[0], areaB = (int) c[3];
        float ax = c[1] * 8, ay = c[2] * 8;
        float bx = c[4] * 8, by = c[5] * 8;
        int blend = blendColors(AREA_COLORS[areaA], AREA_COLORS[areaB]);

        int fillColor = darken(blend, 0.35f) | 0xFF000000;
        int borderColor = darken(blend, 0.6f) | 0xFF000000;
        rasterizeThickLine(ax, ay, bx, by, CONNECTOR_HALF_WIDTH, fillColor);
        rasterizeThickLine(ax, ay, bx, by, CONNECTOR_HALF_WIDTH - 0.7f, borderColor);
        dirty = true;
    }

    private void rasterizeThickLine(float ax, float ay, float bx, float by, float halfWidth, int color) {
        int minX = Math.max(0, (int) Math.floor(Math.min(ax, bx) - halfWidth));
        int maxX = Math.min(CANVAS_PX_W - 1, (int) Math.ceil(Math.max(ax, bx) + halfWidth));
        int minY = Math.max(0, (int) Math.floor(Math.min(ay, by) - halfWidth));
        int maxY = Math.min(CANVAS_PX_H - 1, (int) Math.ceil(Math.max(ay, by) + halfWidth));

        float dx = bx - ax, dy = by - ay;
        float lenSq = dx * dx + dy * dy;
        float hw2 = halfWidth * halfWidth;

        for (int y = minY; y <= maxY; y++) {
            int row = y * CANVAS_PX_W;
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f, py = y + 0.5f;
                float t = lenSq > 0 ? ((px - ax) * dx + (py - ay) * dy) / lenSq : 0f;
                t = Math.max(0f, Math.min(1f, t));
                float cx = ax + t * dx, cy = ay + t * dy;
                float ddx = px - cx, ddy = py - cy;
                if (ddx * ddx + ddy * ddy <= hw2) {
                    compositePixels[row + x] = color;
                }
            }
        }
    }

    private static int darken(int color, float factor) {
        int r = (int) (Color.red(color) * factor);
        int g = (int) (Color.green(color) * factor);
        int b = (int) (Color.blue(color) * factor);
        return Color.rgb(r, g, b);
    }

    private static int blendColors(int a, int b) {
        int r = (Color.red(a) + Color.red(b)) / 2;
        int g = (Color.green(a) + Color.green(b)) / 2;
        int bl = (Color.blue(a) + Color.blue(b)) / 2;
        return Color.rgb(r, g, bl);
    }
}
