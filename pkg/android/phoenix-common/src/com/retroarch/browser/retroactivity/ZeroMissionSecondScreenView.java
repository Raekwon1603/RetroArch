package com.retroarch.browser.retroactivity;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Second-screen HP/ammo strip + room map for Metroid: Zero Mission (GBA, mGBA core).
 * Mirrors SuperMetroidSecondScreenView's layout but reads Zero Mission's own memory addresses.
 * mGBA doesn't implement retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM), so this reads through
 * nativeReadCoreMemoryMapped with real core addresses (e.g. 0x03000000 for GBA IWRAM) instead of
 * nativeReadSystemRam.
 *
 * There used to be a "world map" view (ZeroMissionWorldMap) showing all 7 areas on one canvas.
 * Removed it, cramming everything onto one small canvas looked bad. Instead EXIT_ARROWS below
 * draws small triangles + area labels directly on the single-room view at doors leading to
 * another area (drawExitArrows, called from drawMap). Tapping one sets previewArea instead of
 * switching the live game's area.
 */
public class ZeroMissionSecondScreenView extends View {
    // Current area/room/door, IWRAM. Area: 0=Brinstar, 1=Kraid, 2=Norfair,
    // 3=Ridley, 4=Tourian, 5=Crateria, 6=Chozodia.
    private static final int OFF_AREA = 0x03000054;
    private static final int OFF_ROOM = 0x03000055;
    private static final int OFF_DOOR = 0x03000056;
    private static final int AREA_ROOM_DOOR_LENGTH = 3;

    // gMainGameMode, s16. GM_INGAME(4) is real room gameplay (door
    // transitions count as sub-modes within it). Anything else is title,
    // file select, pause map, cutscene, etc. Used to gate the second
    // screen to live gameplay only.
    private static final int OFF_GAME_MODE = 0x03000C70;
    private static final int GAME_MODE_INGAME = 4;

    // ---- Chozo Statue hint targets ----
    // Grabbing a hint statue's hand sets a bit in gEventsTriggered
    // (EVENT_STATUE_*_GRABBED). The real pause map re-derives which hint
    // targets are still active from that bit plus gEquipment's
    // beamBombs/suitMisc (do you have the item yet).
    //
    // gEventsTriggered: u32[8], one bit per Event enum value.
    // word = event/32, bit = event%32 (LSB-first), src/event.c.
    private static final int OFF_EVENTS_TRIGGERED = 0x02037E00;

    // beamBombs/suitMisc: u8 fields inside struct Equipment (gEquipment,
    // base 0x03001530). beamBombs at +12, suitMisc at +14.
    private static final int OFF_BEAM_BOMBS = 0x0300153C;
    private static final int OFF_SUIT_MISC = 0x0300153E;

    // Bit values, include/constants/samus.h.
    private static final int BBF_LONG_BEAM = 1;
    private static final int BBF_ICE_BEAM = 1 << 1;
    private static final int BBF_WAVE_BEAM = 1 << 2;
    private static final int BBF_BOMBS = 1 << 7;
    private static final int SMF_HIGH_JUMP = 1;
    private static final int SMF_SPEEDBOOSTER = 1 << 1;
    private static final int SMF_SCREW_ATTACK = 1 << 3;
    private static final int SMF_VARIA_SUIT = 1 << 4;

    // Event enum indices, only the 8 statue-hint-grabbed ones we need.
    // Kraid/Ridley flame targets are boss markers, not equipment hints, skipped.
    private static final int EVENT_STATUE_LONG_BEAM_GRABBED = 8;
    private static final int EVENT_STATUE_BOMBS_GRABBED = 9;
    private static final int EVENT_STATUE_ICE_BEAM_GRABBED = 10;
    private static final int EVENT_STATUE_SPEEDBOOSTER_GRABBED = 11;
    private static final int EVENT_STATUE_HIGH_JUMP_GRABBED = 12;
    private static final int EVENT_STATUE_VARIA_SUIT_GRABBED = 13;
    private static final int EVENT_STATUE_WAVE_BEAM_GRABBED = 14;
    private static final int EVENT_STATUE_SCREW_ATTACK_GRABBED = 15;

    // {targetAreaIndex, targetCellX, targetCellY, hintEvent, conditionKind, conditionBit}
    // From src/data/menus/pause_screen_sub_menus_data.c's statue target tables.
    // targetX/targetY are already in the same cell units as EXIT_ARROWS/ROOM_MAP_POS_*.
    // conditionKind: 0 = beamBombs bit, 1 = suitMisc bit.
    //
    // Only the 8 beam/suit upgrade hints, not the Kraid/Ridley flame markers
    // (those are boss-kill animations, different marker type, skipped).
    private static final int COND_BEAM_BOMBS = 0;
    private static final int COND_SUIT_MISC = 1;
    private static final int[][] STATUE_TARGETS = {
            // {targetArea, targetX, targetY, hintEvent, conditionKind, conditionBit}
            {0, 6, 6, EVENT_STATUE_LONG_BEAM_GRABBED, COND_BEAM_BOMBS, BBF_LONG_BEAM},
            {0, 24, 6, EVENT_STATUE_BOMBS_GRABBED, COND_BEAM_BOMBS, BBF_BOMBS},
            {2, 18, 3, EVENT_STATUE_ICE_BEAM_GRABBED, COND_BEAM_BOMBS, BBF_ICE_BEAM},
            {1, 8, 15, EVENT_STATUE_SPEEDBOOSTER_GRABBED, COND_SUIT_MISC, SMF_SPEEDBOOSTER},
            {2, 19, 8, EVENT_STATUE_HIGH_JUMP_GRABBED, COND_SUIT_MISC, SMF_HIGH_JUMP},
            {0, 14, 2, EVENT_STATUE_VARIA_SUIT_GRABBED, COND_SUIT_MISC, SMF_VARIA_SUIT},
            {2, 10, 12, EVENT_STATUE_WAVE_BEAM_GRABBED, COND_BEAM_BOMBS, BBF_WAVE_BEAM},
            {2, 6, 7, EVENT_STATUE_SCREW_ATTACK_GRABBED, COND_SUIT_MISC, SMF_SCREW_ATTACK},
    };

    // u16 LE each, matches datacrystal's addresses.
    private static final int OFF_MAX_HP = 0x03001530;
    private static final int OFF_MAX_MISSILES = 0x03001532;
    private static final int OFF_CUR_HP = 0x03001536;
    private static final int OFF_CUR_MISSILES = 0x03001538;
    // struct Equipment, base 0x03001530 (OFF_MAX_HP) - super missiles and
    // power bombs are u8 fields here, not u16 like HP/missiles.
    private static final int OFF_MAX_SUPER_MISSILES = 0x03001534; // u8
    private static final int OFF_CUR_SUPER_MISSILES = 0x0300153A; // u8
    private static final int OFF_MAX_POWER_BOMBS = 0x03001535; // u8
    private static final int OFF_CUR_POWER_BOMBS = 0x0300153B; // u8
    // 0 = missiles selected, 1 = super missiles selected. Select button
    // toggles this in game.
    private static final int OFF_MISSILE_SELECTOR = 0x03001417;
    // One read covering the whole range instead of separate reads per field.
    private static final int STATS_BLOCK_OFFSET = OFF_MAX_HP;
    private static final int STATS_BLOCK_LENGTH = (OFF_CUR_POWER_BOMBS + 1) - OFF_MAX_HP;

    // Samus's sub-pixel X/Y position, u16 LE.
    private static final int OFF_SAMUS_X = 0x030013E6;
    private static final int OFF_SAMUS_Y = 0x030013E8;

    // gMinimapX/gMinimapY, player marker's cell in the current area's
    // 32x32 minimap grid. u8 each.
    private static final int OFF_MINIMAP_X = 0x03000059;
    private static final int OFF_MINIMAP_Y = 0x0300005A;

    // gDecompressedMinimapData: u16[32*32], row-major (index = y*32+x),
    // current area only, reloaded on area transition. Low 10 bits = ROM
    // tile-graphic id (decoded/blitted by ZeroMissionMinimapTiles), bits
    // 10-11 = flip, bits 12-15 = palette bank. 0x140 (MINIMAP_TILE_BACKGROUND)
    // means empty/no tile.
    //
    // This is the static per-area shape, always fully populated regardless
    // of exploration. Per-tile explored gating (matching the game's own
    // MinimapSetDownloadedTiles) is applied separately in
    // ensureMapBitmapUpToDate using OFF_WORLD_VISITED_TILES and
    // OFF_DOWNLOADED_MAP_STATUS.
    private static final int OFF_MINIMAP_DATA = 0x02034800;
    private static final int MINIMAP_SIZE = 32;
    private static final int MINIMAP_TILE_BACKGROUND = 0x140;
    private static final int MINIMAP_DATA_LENGTH = MINIMAP_SIZE * MINIMAP_SIZE * 2;
    private static final int TILE_PX = 8; // native tile size

    // gDecompressedMinimapVisitedTiles (EWRAM_BASE + 0x34000) is NOT what
    // the pause screen's regular map reads. It's only cells Samus's coarse
    // minimap position has actually walked through this session. The real
    // pause screen rebuilds its own buffer from the static shape
    // (OFF_MINIMAP_DATA) plus MinimapSetDownloadedTiles gating, which is
    // what ensureMapBitmapUpToDate replicates.

    // gVisitedMinimapTiles: one 32x32-tile explored-bit grid per area (8
    // slots, 0=Brinstar..6=Chozodia, 7=unused). Densely packed u32[8][32],
    // 128 bytes per area, no padding between areas.
    private static final int OFF_WORLD_VISITED_TILES = 0x02037400;
    private static final int WORLD_VISITED_TILES_STRIDE = 128; // dense, no padding
    private static final int WORLD_AREA_COUNT = 7; // Brinstar..Chozodia; index 7 unused

    // gEquipment.downloadedMapStatus, one bit per area, set once that
    // area's Map Station item is collected. Base 0x03001530 (gEquipment,
    // same base as OFF_MAX_HP) + 0x10 struct offset.
    //
    // Used to replicate MinimapSetDownloadedTiles: a tile draws if its bit
    // in OFF_WORLD_VISITED_TILES (explored) is set, or this area's Map
    // Station bit is set (draws dimmed instead of hidden).
    private static final int OFF_DOWNLOADED_MAP_STATUS = 0x03001540;

    // gMinimapX/Y are the game's own quantized position: it only updates
    // once per full screen-width of movement, since that's fine for the
    // small in-game minimap. At this view's zoom level that shows up as
    // the Samus dot teleporting instead of sliding.
    //
    // Fix: recompute the same formula ourselves in floating point from
    // Samus's raw sub-pixel position (OFF_SAMUS_X/Y) and the current
    // room's mapX/mapY (ROOM_MAP_POS_* tables), skipping the final integer
    // truncation the game applies. Gives a smooth continuous position
    // instead of gMinimapX/Y's discrete per-screen steps.
    private static final int BLOCK_SIZE_SUBPIXEL = 64; // PIXEL_PER_BLOCK(16) * SUB_PIXEL_RATIO(4)
    private static final int SCREEN_SIZE_X_BLOCKS = 15; // GBA screen width (240px) in blocks
    // Y uses a different divisor than X since GBA screens aren't square (240x160).
    // SCREEN_SIZE_Y_BLOCKS = 160px * 4 / 64 = 10.
    private static final int SCREEN_SIZE_Y_BLOCKS = 10;

    // Per-room mapX/mapY, packed as (mapX<<8)|mapY per room (both u8, no
    // precision lost). Indexed by OFF_ROOM's room index for the current area.
    private static final short[] ROOM_MAP_POS_BRINSTAR = {
            13, 1294, 1551, 2563, 1798, 1542, 2824, 3075, 1554, 1027, 515, 259, 3340, 3336, 4360, 4359,
            5384, 4874, 4618, 5640, 7428, 5380, 4866, 3842, 6406, 5894, 5644, 3586, 3332, 2819, 2307, 7174,
            7177, 6919, 3337, 2052, 2831, 2066, 6660, 5124, 2828, 2314,
    };
    private static final short[] ROOM_MAP_POS_KRAID = {
            2308, 2566, 3841, 3073, 3074, 3075, 2817, 1029, 1031, 2567, 1801, 1803, 2569, 1545, 2571, 2827,
            3084, 3082, 3340, 3085, 2829, 1548, 1549, 1292, 1036, 1293, 773, 4105, 3086, 2830, 2316, 2054,
            1038, 1806, 2063, 1291, 4361, 2316, 2820, 3586, 3587, 4617,
    };
    private static final short[] ROOM_MAP_POS_NORFAIR = {
            3587, 1541, 1283, 1542, 4100, 5633, 4353, 4097, 4355, 4867, 4866, 4870, 5128, 4872, 3335, 2824,
            2311, 1799, 1543, 1544, 1288, 1545, 1546, 2314, 2571, 3339, 2828, 2572, 2573, 3851, 2569, 5129,
            3850, 4364, 3596, 4365, 2568, 3334, 2822, 3845, 1033, 3595, 4875, 3596, 3843, 5388, 4875, 5121,
            4877, 4360, 4366, 3854, 2318, 5385, 3596, 778, 776,
    };
    private static final short[] ROOM_MAP_POS_RIDLEY = {
            3841, 3587, 3328, 2051, 1795, 1283, 1027, 1285, 2565, 3077, 3332, 2310, 1798, 1287, 4103, 5123,
            4098, 3845, 1544, 2304, 2823, 1288, 2308, 2052, 773, 4872, 3335, 2304, 3328, 520, 5638, 5637,
            3331,
    };
    private static final short[] ROOM_MAP_POS_TOURIAN = {
            5122, 5382, 7431, 5899, 4874, 4609, 6667, 4876, 4353, 4874, 5899, 5379, 5635, 5891, 5380, 5123,
            6923, 7430, 7174, 6662,
    };
    private static final short[] ROOM_MAP_POS_CRATERIA = {
            2049, 1031, 1802, 2314, 780, 2049, 3847, 4358, 3843, 4354, 5891, 3335, 3590, 5895, 4869, 3331,
            3843, 3843, 780, 1285, 5891, 1031,
    };
    private static final short[] ROOM_MAP_POS_CHOZODIA = {
            7181, 6413, 5647, 5389, 4623, 4110, 4621, 1300, 6155, 3339, 5135, 4879, 6664, 5380, 5127, 5896,
            4874, 4872, 3848, 3337, 3594, 4617, 3592, 2576, 2577, 2065, 2057, 1044, 273, 1041, 1806, 1038,
            779, 270, 529, 1803, 2821, 1556, 1801, 519, 1799, 1797, 1283, 4871, 3334, 3332, 3333, 3588,
            5893, 3844, 6158, 4612, 4357, 3590, 2060, 6152, 1806, 2821, 3590, 4099, 3329, 4354, 2580, 2069,
            1813, 1557, 1044, 2065, 533, 4096, 5633, 4865, 5888, 4615, 2838, 268, 788, 5131, 3848, 1290,
            528, 528, 270, 519, 524, 2576, 5647, 6413, 7181, 1809, 1797, 524, 5893, 3594, 3080, 3597,
            3329, 4098, 3339,
    };
    private static final short[][] ROOM_MAP_POS_BY_AREA = {
            ROOM_MAP_POS_BRINSTAR, ROOM_MAP_POS_KRAID, ROOM_MAP_POS_NORFAIR, ROOM_MAP_POS_RIDLEY,
            ROOM_MAP_POS_TOURIAN, ROOM_MAP_POS_CRATERIA, ROOM_MAP_POS_CHOZODIA,
    };

    /** Room mapX (upper byte of the packed short), or -1 if area/room is out of range. */
    private static int roomMapX(int areaIndex, int roomIndex) {
        if (areaIndex < 0 || areaIndex >= ROOM_MAP_POS_BY_AREA.length) return -1;
        short[] rooms = ROOM_MAP_POS_BY_AREA[areaIndex];
        if (roomIndex < 0 || roomIndex >= rooms.length) return -1;
        return (rooms[roomIndex] >> 8) & 0xFF;
    }

    /** Room mapY (lower byte of the packed short), or -1 if area/room is out of range. */
    private static int roomMapY(int areaIndex, int roomIndex) {
        if (areaIndex < 0 || areaIndex >= ROOM_MAP_POS_BY_AREA.length) return -1;
        short[] rooms = ROOM_MAP_POS_BY_AREA[areaIndex];
        if (roomIndex < 0 || roomIndex >= rooms.length) return -1;
        return rooms[roomIndex] & 0xFF;
    }

    // Same palette as SuperMetroidSecondScreenView, a Zero Mission specific
    // reskin is later work.
    private static final int COL_BG = Color.rgb(30, 33, 44);
    private static final int COL_PANEL_BG = Color.rgb(38, 42, 56);
    private static final int COL_BORDER_DARK = Color.rgb(58, 64, 86);
    private static final int COL_DIM_GRAY = Color.rgb(105, 110, 128);
    private static final int COL_ENERGY_PIP = Color.rgb(204, 71, 145);
    // Low health warning, real game's own energy<30 threshold.
    private static final int COL_LOW_HEALTH = Color.rgb(230, 57, 57);
    private static final int COL_ACCENT = Color.rgb(255, 158, 68);
    private static final int COL_SAMUS_DOT = Color.rgb(255, 70, 70);
    private static final int COL_TAB_ACTIVE_BG = Color.rgb(56, 61, 82);
    private static final int COL_TAB_LABEL = Color.rgb(205, 209, 225);
    private static final int COL_BORDER_HIGHLIGHT = Color.rgb(115, 124, 155);
    private static final int COL_SLOT_BG = Color.rgb(48, 52, 68);

    // Real HUD wraps energy tank pips at 6 per row.
    private static final int PIPS_PER_ROW = 6;

    // ---- tabs ----
    // Only MAP has real content. ITEMS/SETUP are "COMING SOON" placeholders
    // (drawPlaceholderTab) for later work.
    private enum Tab { MAP, ITEMS, SETUP }
    private Tab currentTab = Tab.MAP;
    private static final String[] TAB_LABELS = { "MAP", "ITEMS", "SETUP" };
    private final RectF[] tabButtonRects = { new RectF(), new RectF(), new RectF() };

    // ---- room-view zoom/pan ----
    // DEFAULT_ZOOM and MIN_ZOOM are whole steps apart so the +/- buttons
    // can always land back on the default without needing the reset button.
    private static final float ZOOM_BUTTON_STEP = 1.2f;
    private static final float DEFAULT_ZOOM = 0.6f / ZOOM_BUTTON_STEP;
    private static final float MIN_ZOOM = DEFAULT_ZOOM / ZOOM_BUTTON_STEP / ZOOM_BUTTON_STEP, MAX_ZOOM = 6f;
    private float roomZoomFactor = DEFAULT_ZOOM;
    private final RectF resetCameraBtn = new RectF();
    private final RectF zoomOutBtn = new RectF();
    private final RectF zoomInBtn = new RectF();

    // Manual drag pan on top of the Samus-centered baseline, minimap-cell
    // units, reset when the area changes.
    private float roomPanOffsetX = 0f, roomPanOffsetY = 0f;
    private int lastRoomViewArea = -1;
    // Cells-per-screen-pixel from the last drawMap call, for onTouchEvent's
    // drag handling.
    private float roomViewCellsPerPixel = 0f;

    // Cached every drawMap call so a long-press (raw screen coords from
    // onTouchEvent) can convert back to minimap-cell space.
    private float roomMapDestLeft, roomMapDestTop, roomMapCropCx, roomMapCropCy, roomMapCellSize;

    // ---- cross-area exit arrows ----
    //
    // {areaIndex, cellX, cellY, destAreaIndex}, one row per door leading
    // out of areaIndex into destAreaIndex. cellX/cellY are in the same
    // cell units as OFF_MINIMAP_X/Y. Not deduplicated by destination area
    // since some areas have more than one physical door to the same
    // neighbor (e.g. two Crateria<->Chozodia doors); dedup by screen
    // position happens at draw time instead (see drawExitArrows).
    // 5th column: exit direction (0=UP,1=DOWN,2=LEFT,3=RIGHT), derived from
    // each door's span direction in the room data.
    private static final int DIR_UP = 0, DIR_DOWN = 1, DIR_LEFT = 2, DIR_RIGHT = 3;
    private static final float[][] EXIT_ARROWS = {
            {0, 2.60f, 13.13f, 5, DIR_UP},    // Brinstar -> Crateria
            {0, 6.60f, 19.40f, 1, DIR_DOWN},  // Brinstar -> Kraid
            {0, 22.60f, 13.40f, 2, DIR_DOWN}, // Brinstar -> Norfair
            {0, 1.60f, 4.40f, 4, DIR_DOWN},   // Brinstar -> Tourian
            {1, 9.60f, 4.13f, 0, DIR_UP},     // Kraid -> Brinstar
            {1, 20.07f, 9.43f, 2, DIR_RIGHT}, // Kraid -> Norfair
            {2, 14.60f, 3.13f, 0, DIR_UP},    // Norfair -> Brinstar
            {2, 17.60f, 14.40f, 3, DIR_DOWN}, // Norfair -> Ridley
            {2, 5.60f, 3.13f, 5, DIR_UP},     // Norfair -> Crateria
            {2, 15.53f, 14.80f, 3, DIR_DOWN}, // Norfair -> Ridley
            {2, 3.07f, 8.43f, 1, DIR_LEFT},   // Norfair -> Kraid
            {3, 15.60f, 1.13f, 2, DIR_UP},    // Ridley -> Norfair
            {3, 13.53f, 0.07f, 2, DIR_UP},    // Ridley -> Norfair
            {4, 20.60f, 2.13f, 0, DIR_UP},    // Tourian -> Brinstar
            {4, 18.60f, 1.13f, 5, DIR_UP},    // Tourian -> Crateria
            {4, 18.60f, 1.13f, 5, DIR_UP},    // Tourian -> Crateria
            {5, 9.60f, 11.40f, 0, DIR_DOWN},  // Crateria -> Brinstar
            {5, 3.60f, 13.40f, 4, DIR_DOWN},  // Crateria -> Tourian
            {5, 15.60f, 8.40f, 2, DIR_DOWN},  // Crateria -> Norfair
            {5, 25.07f, 3.43f, 6, DIR_RIGHT}, // Crateria -> Chozodia
            {5, 25.07f, 7.47f, 6, DIR_RIGHT}, // Crateria -> Chozodia
            {5, 25.07f, 3.43f, 6, DIR_RIGHT}, // Crateria -> Chozodia
            {5, 3.60f, 13.40f, 4, DIR_DOWN},  // Crateria -> Tourian
            {6, 2.13f, 21.47f, 5, DIR_LEFT},  // Chozodia -> Crateria
            {6, 2.13f, 17.43f, 5, DIR_LEFT},  // Chozodia -> Crateria
    };
    private static final String[] AREA_NAMES = {
            "BRINSTAR", "KRAID", "NORFAIR", "RIDLEY", "TOURIAN", "CRATERIA", "CHOZODIA"
    };

    // Tappable screen rect for each exit arrow drawn this frame, rebuilt
    // every drawExitArrows call.
    private final RectF[] exitArrowRects = new RectF[EXIT_ARROWS.length];
    private final int[] exitArrowRow = new int[EXIT_ARROWS.length]; // EXIT_ARROWS row index per visible arrow, not the dest area index
    private int exitArrowVisibleCount = 0;
    {
        for (int i = 0; i < exitArrowRects.length; i++) exitArrowRects[i] = new RectF();
    }

    // Non-null while showing a neighboring area's map instead of the live
    // one, set when the player taps an exit arrow, cleared by the BACK
    // button. Zero Mission only keeps the current area's tile-shape grid
    // in memory, there's no live address for every area at once, so
    // preview mode shows a placeholder panel instead of real tile art.
    private Integer previewArea = null;
    private int previewDestAreaOriginRow = -1; // EXIT_ARROWS row tapped to enter this preview, for the "leads to" sub-label
    private final RectF backButtonRect = new RectF();

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

    // Decoded once and cached, ROM asset data never changes for the life of
    // a loaded ROM. Decoding runs on a background thread so ROM file I/O
    // doesn't risk an ANR on the UI thread during first layout.
    private volatile ZeroMissionMinimapTiles.Decoded minimapTiles;
    private volatile boolean tilesLoadInFlight = false;
    // Real ROM-decoded ammo icons (see ZeroMissionHudIcons), drawn next to
    // each ammo count in drawStatusStrip - each null until decoded.
    private volatile Bitmap missileIconBitmap;
    private volatile Bitmap superMissileIconBitmap;
    private volatile Bitmap powerBombIconBitmap;

    // Throttled cache for explored-bits/Map-Station reads. These used to
    // run unconditionally every frame (drawMap polls at ~60fps), adding 2
    // extra JNI round trips per frame and causing visible lag. They don't
    // change often, so refresh a few times a second instead. Position/area
    // reads driving the Samus dot are NOT throttled, only these two.
    private static final long EXPLORED_BITS_REFRESH_INTERVAL_MS = 150;
    private long lastExploredBitsCheckUptimeMs = 0;
    private byte[] cachedExploredBits;
    private boolean cachedHasMapStation;
    private int cachedExploredBitsArea = -1;

    // Current area's composited map, rebuilt only when the raw grid data
    // actually changes (byte compare against lastMapData) instead of every
    // onDraw call, since a full 32x32 tile blit is wasted work most ticks.
    private Bitmap mapBitmap;
    private byte[] lastMapData;
    // Explored/Map-Station gating inputs from the last ensureMapBitmapUpToDate
    // call, needed alongside mapData as part of the cache key.
    private byte[] lastExploredBitsForBitmap;
    private boolean lastMapStationForBitmap;

    public ZeroMissionSecondScreenView(Context context, RetroActivityCommon activity) {
        super(context);
        this.activity = activity;
        paint.setAntiAlias(false);
        bitmapPaint.setAntiAlias(false);
        bitmapPaint.setFilterBitmap(false); // crisp pixel-art scaling, no blur
        // Pins are loaded lazily once a ROM path is known, see
        // ensurePinsMatchCurrentRom. No ROM is guaranteed available yet at
        // construction time.

        // Pinch-to-zoom, just clamps at either end of the zoom range.
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (currentTab != Tab.MAP) return true;
                roomZoomFactor = clampFloat(roomZoomFactor * detector.getScaleFactor(), MIN_ZOOM, MAX_ZOOM);
                invalidate();
                return true;
            }
        });
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
            ZeroMissionHudIcons.Icons icons = ZeroMissionHudIcons.decodeAll(rom);
            if (icons != null) {
                Bitmap missileBmp = toBitmap(icons.missile);
                Bitmap superMissileBmp = toBitmap(icons.superMissile);
                Bitmap powerBombBmp = toBitmap(icons.powerBomb);
                uiHandler.post(() -> {
                    missileIconBitmap = missileBmp;
                    superMissileIconBitmap = superMissileBmp;
                    powerBombIconBitmap = powerBombBmp;
                });
            }
            tilesLoadInFlight = false;
        }, "ZeroMissionRomLoad").start();
    }

    private static Bitmap toBitmap(int[] pixels) {
        if (pixels == null) return null;
        Bitmap bmp = Bitmap.createBitmap(16, 8, Bitmap.Config.ARGB_8888);
        bmp.setPixels(pixels, 0, 16, 0, 0, 16, 8);
        return bmp;
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
        // Only show second-screen content during live gameplay, otherwise
        // it stays frozen on pause/title/cutscene screens with stale data.
        // gMainGameMode is GM_INGAME (4) during real room gameplay
        // (door-transitions are sub-modes within it, not separate modes).
        byte[] gameModeBytes = activity.nativeReadCoreMemoryMapped(OFF_GAME_MODE, 2);
        boolean playingLive = statsBlock != null && statsBlock.length >= STATS_BLOCK_LENGTH
                && gameModeBytes != null && gameModeBytes.length >= 2
                && readUint16LE(gameModeBytes, 0) == GAME_MODE_INGAME;
        if (!playingLive) {
            for (RectF r : tabButtonRects) r.setEmpty(); // nothing tappable while not in live gameplay
            resetCameraBtn.setEmpty();
            zoomInBtn.setEmpty();
            zoomOutBtn.setEmpty();
            mapTapRect.setEmpty();
            backButtonRect.setEmpty();
            exitArrowVisibleCount = 0;
            drawLogo(canvas, w, h);
            return;
        }

        ensureTilesLoaded();
        ensurePinsMatchCurrentRom();

        int maxHp = readUint16LE(statsBlock, OFF_MAX_HP - STATS_BLOCK_OFFSET);
        int maxMissiles = readUint16LE(statsBlock, OFF_MAX_MISSILES - STATS_BLOCK_OFFSET);
        int curHp = readUint16LE(statsBlock, OFF_CUR_HP - STATS_BLOCK_OFFSET);
        int curMissiles = readUint16LE(statsBlock, OFF_CUR_MISSILES - STATS_BLOCK_OFFSET);
        // & 0xFF since Java bytes are signed and these are never negative.
        int maxSuperMissiles = statsBlock[OFF_MAX_SUPER_MISSILES - STATS_BLOCK_OFFSET] & 0xFF;
        int curSuperMissiles = statsBlock[OFF_CUR_SUPER_MISSILES - STATS_BLOCK_OFFSET] & 0xFF;
        int maxPowerBombs = statsBlock[OFF_MAX_POWER_BOMBS - STATS_BLOCK_OFFSET] & 0xFF;
        int curPowerBombs = statsBlock[OFF_CUR_POWER_BOMBS - STATS_BLOCK_OFFSET] & 0xFF;

        // Far from STATS_BLOCK's own range, needs its own read.
        byte[] selectorRaw = activity.nativeReadCoreMemoryMapped(OFF_MISSILE_SELECTOR, 1);
        boolean superMissilesSelected = selectorRaw != null && selectorRaw.length >= 1 && (selectorRaw[0] & 1) != 0;

        // Capped by width too, not just height, or it balloons on a wide
        // panel like the Thor's second screen.
        float stripH = Math.min(h * 0.16f, w * 0.11f);
        drawStatusStrip(canvas, w, stripH, curHp, maxHp, curMissiles, maxMissiles,
                curSuperMissiles, maxSuperMissiles, curPowerBombs, maxPowerBombs, superMissilesSelected);

        // Footer: persistent MAP/ITEMS/SETUP tab bar, with a zoom controls
        // strip above it while on the MAP tab. Space is reserved regardless
        // of tab so the tab bar never moves when switching tabs.
        float margin = w * 0.03f;
        float tabBarH = stripH * 0.85f;
        float controlsBarH = currentTab == Tab.MAP ? stripH * 0.85f : 0f;
        RectF tabBarRect = new RectF(margin, h - tabBarH - margin * 0.3f, w - margin, h - margin * 0.3f);
        RectF controlsBarRect = new RectF(margin, tabBarRect.top - controlsBarH - stripH * 0.1f,
                w - margin, tabBarRect.top - stripH * 0.1f);

        layoutTabButtons(tabBarRect);
        drawTabBar(canvas);

        float contentTop = stripH + h * 0.02f;
        if (currentTab == Tab.MAP) {
            layoutZoomButtons(controlsBarRect);
            drawZoomButtons(canvas);
            mapTapRect.set(w * 0.03f, contentTop, w * 0.97f, controlsBarRect.top - stripH * 0.15f);
            drawMap(canvas, mapTapRect);
        } else {
            mapTapRect.setEmpty();
            resetCameraBtn.setEmpty();
            zoomInBtn.setEmpty();
            zoomOutBtn.setEmpty();
            backButtonRect.setEmpty();
            exitArrowVisibleCount = 0;
            RectF tabArea = new RectF(w * 0.03f, contentTop, w * 0.97f, tabBarRect.top - stripH * 0.15f);
            drawPlaceholderTab(canvas, tabArea, currentTab == Tab.ITEMS ? "ITEMS" : "SETUP");
        }
    }

    private void drawLogo(Canvas canvas, float w, float h) {
        float cx = w / 2f, cy = h / 2f;
        float textHeight = w * 0.11f;
        int fadedAccent = Color.argb(70, Color.red(COL_ACCENT), Color.green(COL_ACCENT), Color.blue(COL_ACCENT));
        float pixelSize = PixelFont.pixelSizeForHeight(textHeight);
        PixelFont.drawText(canvas, "METROID", cx, cy - textHeight / 2f, pixelSize, fadedAccent, Paint.Align.CENTER);
    }

    // Single horizontal row: pips, HP number, then each ammo type's icon
    // and current count.
    private void drawStatusStrip(Canvas canvas, float w, float stripH, int curHp, int maxHp, int curMissiles, int maxMissiles,
                                  int curSuperMissiles, int maxSuperMissiles, int curPowerBombs, int maxPowerBombs,
                                  boolean superMissilesSelected) {
        rect.set(0, 0, w, stripH);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COL_PANEL_BG);
        canvas.drawRoundRect(rect, stripH * 0.1f, stripH * 0.1f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, stripH * 0.02f));
        paint.setColor(COL_BORDER_DARK);
        canvas.drawRoundRect(rect, stripH * 0.1f, stripH * 0.1f, paint);

        float textSize = stripH * 0.32f;
        float pipSize = stripH * 0.28f;
        float pipGap = stripH * 0.07f;

        // 100 HP per tank. Shows filled pips plus the remainder as text,
        // matching the real HUD.
        int maxTanks = maxHp / 100;
        int filledTanks = Math.min(curHp / 100, maxTanks);
        int pipCols = Math.min(maxTanks, PIPS_PER_ROW);
        int pipRowCount = maxTanks <= PIPS_PER_ROW ? 1 : 2;
        float pipRowStep = pipSize * 1.3f;
        float pipBlockH = pipRowStep * (pipRowCount - 1) + pipSize;
        float pipBlockTop = (stripH - pipBlockH) * 0.5f;
        float pipLeft = stripH * 0.3f;

        // Real game's low health threshold is energy < 30.
        boolean lowHealth = curHp < 30;
        int filledPipColor = lowHealth ? COL_LOW_HEALTH : COL_ENERGY_PIP;

        for (int i = 0; i < maxTanks; i++) {
            int row = i / PIPS_PER_ROW, col = i % PIPS_PER_ROW;
            float rowMidY = pipBlockTop + pipRowStep * row + pipSize * 0.5f;
            float px = pipLeft + col * (pipSize + pipGap);
            float pipTop = rowMidY - pipSize / 2f, pipBottom = rowMidY + pipSize / 2f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(i < filledTanks ? filledPipColor : COL_BORDER_DARK);
            canvas.drawRect(px, pipTop, px + pipSize, pipBottom, paint);
            if (i < filledTanks) {
                // Gloss highlight along the top and left edges.
                paint.setColor(Color.WHITE);
                float thickness = pipSize * 0.16f;
                canvas.drawRect(px, pipTop, px + pipSize, pipTop + thickness, paint);
                canvas.drawRect(px, pipTop, px + thickness, pipBottom, paint);
            }
        }

        float midY = stripH * 0.5f;
        float pixelSize = PixelFont.pixelSizeForHeight(textSize);
        String hpText = String.valueOf(maxTanks > 0 ? (curHp % 100) : curHp);
        float x = pipLeft + pipCols * (pipSize + pipGap) + stripH * 0.2f;
        PixelFont.drawText(canvas, hpText, x, midY - textSize / 2f, pixelSize, Color.WHITE, Paint.Align.LEFT);
        x += PixelFont.measureWidth(hpText, pixelSize) + stripH * 0.6f;

        // Only show an ammo type once it's actually collected.
        if (maxMissiles > 0) {
            x = drawAmmoIcon(canvas, missileIconBitmap, curMissiles, x, midY, textSize, pixelSize,
                    !superMissilesSelected, stripH);
        }
        if (maxSuperMissiles > 0) {
            x = drawAmmoIcon(canvas, superMissileIconBitmap, curSuperMissiles, x, midY, textSize, pixelSize,
                    superMissilesSelected, stripH);
        }
        if (maxPowerBombs > 0) {
            // Power bombs aren't part of the Select-button toggle, never highlighted.
            drawAmmoIcon(canvas, powerBombIconBitmap, curPowerBombs, x, midY, textSize, pixelSize, false, stripH);
        }
    }

    // One ammo type's icon + current count, with an optional selected-weapon
    // highlight border. Returns the new x position for the next ammo type.
    private float drawAmmoIcon(Canvas canvas, Bitmap iconBitmap, int curCount, float x, float midY,
                                float textSize, float pixelSize, boolean selected, float stripH) {
        if (iconBitmap != null) {
            float iconH = textSize * 0.9f;
            float iconW = iconH * ((float) iconBitmap.getWidth() / iconBitmap.getHeight());
            float pad = stripH * 0.06f;
            if (selected) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(2f, stripH * 0.03f));
                paint.setColor(COL_ACCENT);
                canvas.drawRect(x - pad, midY - iconH / 2f - pad, x + iconW + pad, midY + iconH / 2f + pad, paint);
            }
            srcRect.set(0, 0, iconBitmap.getWidth(), iconBitmap.getHeight());
            dstRect.set(Math.round(x), Math.round(midY - iconH / 2f),
                    Math.round(x + iconW), Math.round(midY + iconH / 2f));
            canvas.drawBitmap(iconBitmap, srcRect, dstRect, bitmapPaint);
            x += iconW + stripH * 0.15f;
        }

        // Current count only, no "/max" - not enough room for both here.
        String text = String.valueOf(curCount);
        PixelFont.drawText(canvas, text, x, midY - textSize / 2f, pixelSize, Color.WHITE, Paint.Align.LEFT);
        return x + PixelFont.measureWidth(text, pixelSize) + stripH * 0.5f;
    }

    // Bit (gy*32+gx) of exploredBits (128-byte per-area slice of
    // OFF_WORLD_VISITED_TILES, u32 rows LSB-first).
    private static boolean isExploredCell(byte[] exploredBits, int gx, int gy) {
        int bitIndex = gy * MINIMAP_SIZE + gx;
        int byteIndex = bitIndex >> 3;
        if (byteIndex < 0 || byteIndex >= exploredBits.length) return false;
        return ((exploredBits[byteIndex] >> (bitIndex & 7)) & 1) != 0;
    }

    /** Rebuilds mapBitmap from mapData/exploredBits/hasMapStation if they changed since the last call.
     *  Replicates MinimapSetDownloadedTiles (src/minimap.c): explored cells get dimmed (0x1000) unless
     *  already a special tile; unexplored cells are hidden or downgraded depending on Map Station status.
     *  Plain wall/corner tiles (no 0xF000 bits) always draw regardless of exploration. */
    private void ensureMapBitmapUpToDate(byte[] mapData, byte[] exploredBits, boolean hasMapStation) {
        boolean sameMapData = lastMapData != null && java.util.Arrays.equals(lastMapData, mapData);
        boolean sameExplored = (lastExploredBitsForBitmap == null) == (exploredBits == null)
                && (exploredBits == null || java.util.Arrays.equals(lastExploredBitsForBitmap, exploredBits));
        if (sameMapData && sameExplored && lastMapStationForBitmap == hasMapStation) return;
        lastMapData = mapData.clone();
        lastExploredBitsForBitmap = exploredBits == null ? null : exploredBits.clone();
        lastMapStationForBitmap = hasMapStation;

        if (mapBitmap == null) {
            mapBitmap = Bitmap.createBitmap(MINIMAP_SIZE * TILE_PX, MINIMAP_SIZE * TILE_PX, Bitmap.Config.ARGB_8888);
        }
        int[] canvasPixels = new int[MINIMAP_SIZE * TILE_PX * MINIMAP_SIZE * TILE_PX];
        int stride = MINIMAP_SIZE * TILE_PX;

        for (int gy = 0; gy < MINIMAP_SIZE; gy++) {
            for (int gx = 0; gx < MINIMAP_SIZE; gx++) {
                int idx = (gy * MINIMAP_SIZE + gx) * 2;
                int tile = readUint16LE(mapData, idx);
                boolean explored = exploredBits != null && isExploredCell(exploredBits, gx, gy);

                // MinimapSetDownloadedTiles logic, see doc comment above.
                if (explored) {
                    if ((tile & 0xF000) == 0) tile |= 0x1000;
                } else if (hasMapStation) {
                    if (tile >= 0x3000) tile = MINIMAP_TILE_BACKGROUND;
                    else if ((tile & 0x3000) != 0) tile &= 0xFFF;
                } else {
                    if ((tile & 0xF000) != 0) tile = MINIMAP_TILE_BACKGROUND;
                }

                int tileId = tile & 0x3FF;
                if (tileId >= MINIMAP_TILE_BACKGROUND) continue;
                int flip = (tile >> 10) & 0x3;
                int paletteBank = (tile >> 12) & 0xF;

                int[] tilePixels = ZeroMissionMinimapTiles.decodeTile(
                        minimapTiles.tileGfx, minimapTiles.palette, tileId, paletteBank, flip);
                int destX0 = gx * TILE_PX, destY0 = gy * TILE_PX;
                for (int py = 0; py < TILE_PX; py++) {
                    int destRow = (destY0 + py) * stride + destX0;
                    int srcRow = py * TILE_PX;
                    for (int px = 0; px < TILE_PX; px++) {
                        int p = tilePixels[srcRow + px];
                        if ((p >>> 24) == 0) continue;
                        canvasPixels[destRow + px] = p;
                    }
                }
            }
        }
        mapBitmap.setPixels(canvasPixels, 0, stride, 0, 0, stride, MINIMAP_SIZE * TILE_PX);
    }

    // Crops to a zoomed, pannable window centered on the player's minimap
    // cell (OFF_MINIMAP_X/Y), in 32x32-grid cell units. The crop is
    // expressed and clamped in cells, then multiplied by TILE_PX (8) only
    // when indexing into mapBitmap's own pixel space.
    private void drawMap(Canvas canvas, RectF area) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COL_PANEL_BG);
        canvas.drawRoundRect(area, area.height() * 0.02f, area.height() * 0.02f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setColor(COL_BORDER_DARK);
        canvas.drawRoundRect(area, area.height() * 0.02f, area.height() * 0.02f, paint);

        // Preview mode (an exit arrow was tapped) shows a placeholder panel
        // instead of the real tile shapes, see previewArea's field comment.
        if (previewArea != null) {
            roomMapCellSize = 0f; // don't let long-press-to-pin use a stale live-area transform
            exitArrowVisibleCount = 0;
            drawAreaPreviewPlaceholder(canvas, area);
            drawBackButton(canvas, area);
            return;
        }
        backButtonRect.setEmpty();

        // Smooth Samus tracking, see BLOCK_SIZE_SUBPIXEL/SCREEN_SIZE_X_BLOCKS
        // comment. Reads Samus's raw sub-pixel position and the current
        // room's mapX/mapY and computes position in floating point.
        byte[] samusXBytes = activity.nativeReadCoreMemoryMapped(OFF_SAMUS_X, 2);
        byte[] samusYBytes = activity.nativeReadCoreMemoryMapped(OFF_SAMUS_Y, 2);
        byte[] roomData = activity.nativeReadCoreMemoryMapped(OFF_ROOM, 1);
        byte[] areaData = activity.nativeReadCoreMemoryMapped(OFF_AREA, 1);
        byte[] mapData = activity.nativeReadCoreMemoryMapped(OFF_MINIMAP_DATA, MINIMAP_DATA_LENGTH);
        if (mapData == null || mapData.length < MINIMAP_DATA_LENGTH || minimapTiles == null) {
            roomMapCellSize = 0f; // nothing drawn this frame, don't use stale transforms
            exitArrowVisibleCount = 0;
            return;
        }

        int areaIndex = areaData != null && areaData.length >= 1 ? (areaData[0] & 0xFF) : lastRoomViewArea;
        if (areaIndex != lastRoomViewArea) {
            // Old pan offset is meaningless in a new area's coordinate space.
            roomPanOffsetX = 0f;
            roomPanOffsetY = 0f;
            lastRoomViewArea = areaIndex;
        }

        // Explored-gating inputs for the current area, throttled (see
        // EXPLORED_BITS_REFRESH_INTERVAL_MS).
        long nowUptimeMs = android.os.SystemClock.uptimeMillis();
        if (areaIndex != cachedExploredBitsArea
                || nowUptimeMs - lastExploredBitsCheckUptimeMs >= EXPLORED_BITS_REFRESH_INTERVAL_MS) {
            lastExploredBitsCheckUptimeMs = nowUptimeMs;
            cachedExploredBitsArea = areaIndex;
            cachedExploredBits = null;
            if (areaIndex >= 0 && areaIndex < WORLD_AREA_COUNT) {
                byte[] raw = activity.nativeReadCoreMemoryMapped(
                        OFF_WORLD_VISITED_TILES + areaIndex * WORLD_VISITED_TILES_STRIDE, 128);
                if (raw != null && raw.length >= 128) cachedExploredBits = raw;
            }
            byte[] mapStationByte = activity.nativeReadCoreMemoryMapped(OFF_DOWNLOADED_MAP_STATUS, 1);
            cachedHasMapStation = areaIndex >= 0 && areaIndex < WORLD_AREA_COUNT
                    && mapStationByte != null && mapStationByte.length >= 1
                    && ((mapStationByte[0] >> areaIndex) & 1) != 0;
        }

        ensureMapBitmapUpToDate(mapData, cachedExploredBits, cachedHasMapStation);

        // cellX/cellY are floats so the Samus dot/crop-centering slides
        // continuously instead of snapping to whole cells.
        float cellX = -1f, cellY = -1f;
        boolean havePos = false;
        if (samusXBytes != null && samusXBytes.length >= 2 && samusYBytes != null && samusYBytes.length >= 2
                && roomData != null && roomData.length >= 1) {
            int roomIndex = roomData[0] & 0xFF;
            int roomMx = roomMapX(areaIndex, roomIndex);
            int roomMy = roomMapY(areaIndex, roomIndex);
            if (roomMx >= 0 && roomMy >= 0) {
                int samusXRaw = readUint16LE(samusXBytes, 0);
                int samusYRaw = readUint16LE(samusYBytes, 0);
                // Same conversion MinimapCheckForUnexploredTile applies, kept
                // in floating point instead of truncating. Room-edge clamping
                // isn't replicated, so positions at a room's extreme edge can
                // drift slightly, a minor cosmetic tradeoff vs the teleporting
                // this replaces.
                float samusXBlocks = Math.max(0f, (samusXRaw - BLOCK_SIZE_SUBPIXEL * 2)) / (float) BLOCK_SIZE_SUBPIXEL;
                float samusYBlocks = Math.max(0f, (samusYRaw - BLOCK_SIZE_SUBPIXEL * 2)) / (float) BLOCK_SIZE_SUBPIXEL;
                cellX = samusXBlocks / SCREEN_SIZE_X_BLOCKS + roomMx;
                cellY = samusYBlocks / SCREEN_SIZE_Y_BLOCKS + roomMy;
                havePos = cellX >= 0f && cellX < MINIMAP_SIZE && cellY >= 0f && cellY < MINIMAP_SIZE;
            }
        }

        // windowCells is sized per-axis (not a single square value) since
        // the map panel is wide and short, not square. A square crop left
        // empty space on both sides. Each axis scales by the panel's aspect
        // ratio so the map fills the whole panel.
        final int baseWindowCells = 14; // reference window size at zoom=1
        float panelAspect = area.width() / area.height();
        float windowCellsWf = baseWindowCells * (float) Math.sqrt(panelAspect) / roomZoomFactor;
        float windowCellsHf = baseWindowCells / (float) Math.sqrt(panelAspect) / roomZoomFactor;
        int windowCellsW = clampInt(Math.round(windowCellsWf), 3, MINIMAP_SIZE);
        int windowCellsH = clampInt(Math.round(windowCellsHf), 3, MINIMAP_SIZE);

        float centerCellX = (havePos ? cellX : MINIMAP_SIZE / 2f) + roomPanOffsetX;
        float centerCellY = (havePos ? cellY : MINIMAP_SIZE / 2f) + roomPanOffsetY;
        int cropCx = clampInt(Math.round(centerCellX - windowCellsW / 2f), 0, MINIMAP_SIZE - windowCellsW);
        int cropCy = clampInt(Math.round(centerCellY - windowCellsH / 2f), 0, MINIMAP_SIZE - windowCellsH);
        int cropPx = cropCx * TILE_PX, cropPy = cropCy * TILE_PX;
        int cropWidthPx = windowCellsW * TILE_PX, cropHeightPx = windowCellsH * TILE_PX;

        // Per-axis scale so the crop rectangle fills the whole panel, not
        // just its shorter dimension.
        float scale = Math.min(area.width() / cropWidthPx, area.height() / cropHeightPx);
        float destWidth = cropWidthPx * scale, destHeight = cropHeightPx * scale;
        float destLeft = area.centerX() - destWidth / 2f;
        float destTop = area.centerY() - destHeight / 2f;
        // Cache the screen<->cell transform for onTouchEvent's drag-pan.
        // Extra /TILE_PX since scale is per map-pixel, not per cell.
        roomViewCellsPerPixel = 1f / (scale * TILE_PX);

        srcRect.set(cropPx, cropPy, cropPx + cropWidthPx, cropPy + cropHeightPx);
        dstRect.set(Math.round(destLeft), Math.round(destTop), Math.round(destLeft + destWidth), Math.round(destTop + destHeight));
        canvas.drawBitmap(mapBitmap, srcRect, dstRect, bitmapPaint);

        // Cache the screen<->cell transform for long-press pin placement.
        roomMapDestLeft = destLeft;
        roomMapDestTop = destTop;
        roomMapCropCx = cropCx;
        roomMapCropCy = cropCy;
        roomMapCellSize = TILE_PX * scale;

        drawPins(canvas, areaIndex, destLeft, destTop, cropCx, cropCy, scale);

        if (havePos) {
            // No +0.5 here, cellX/Y are already Samus's continuous sub-cell
            // position, not a whole-cell index.
            float dotX = destLeft + (cellX - cropCx) * TILE_PX * scale;
            float dotY = destTop + (cellY - cropCy) * TILE_PX * scale;
            // Each axis checked against its own bound since width/height
            // aren't forced equal.
            if (dotX >= destLeft && dotX <= destLeft + destWidth && dotY >= destTop && dotY <= destTop + destHeight) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(COL_SAMUS_DOT);
                canvas.drawCircle(dotX, dotY, Math.max(3f, TILE_PX * scale * 0.6f), paint);
            }
        }

        // Drawn last, on top of room art and Samus dot, reusing the same
        // transform so zoom/pan stays consistent.
        drawExitArrows(canvas, areaIndex, destLeft, destTop, destWidth, destHeight, cropCx, cropCy, scale, cachedExploredBits, cachedHasMapStation);
        drawStatueHints(canvas, areaIndex, destLeft, destTop, destWidth, destHeight, cropCx, cropCy, scale);
    }

    // Throttled cache for gEventsTriggered/beamBombs/suitMisc, changes
    // rarely so no need to re-read every frame.
    private static final long STATUE_HINT_REFRESH_INTERVAL_MS = 300;
    private long lastStatueHintCheckUptimeMs = 0;
    private int[] cachedEventsTriggered; // 8 words, or null if the last read failed
    private int cachedBeamBombs = -1, cachedSuitMisc = -1; // -1 = not yet read successfully

    private void ensureStatueHintCacheFresh() {
        long now = android.os.SystemClock.uptimeMillis();
        if (cachedEventsTriggered != null && now - lastStatueHintCheckUptimeMs < STATUE_HINT_REFRESH_INTERVAL_MS) return;
        lastStatueHintCheckUptimeMs = now;

        byte[] eventsRaw = activity.nativeReadCoreMemoryMapped(OFF_EVENTS_TRIGGERED, 32);
        if (eventsRaw != null && eventsRaw.length >= 32) {
            int[] words = new int[8];
            for (int i = 0; i < 8; i++) {
                words[i] = (eventsRaw[i * 4] & 0xFF) | ((eventsRaw[i * 4 + 1] & 0xFF) << 8)
                        | ((eventsRaw[i * 4 + 2] & 0xFF) << 16) | ((eventsRaw[i * 4 + 3] & 0xFF) << 24);
            }
            cachedEventsTriggered = words;
        }
        byte[] beamBombsRaw = activity.nativeReadCoreMemoryMapped(OFF_BEAM_BOMBS, 1);
        if (beamBombsRaw != null && beamBombsRaw.length >= 1) cachedBeamBombs = beamBombsRaw[0] & 0xFF;
        byte[] suitMiscRaw = activity.nativeReadCoreMemoryMapped(OFF_SUIT_MISC, 1);
        if (suitMiscRaw != null && suitMiscRaw.length >= 1) cachedSuitMisc = suitMiscRaw[0] & 0xFF;
    }

    /** True if event index `event` is set (word=event/32, bit=event%32). */
    private boolean checkEvent(int event) {
        if (cachedEventsTriggered == null) return false;
        int word = event / 32, bit = event % 32;
        if (word < 0 || word >= cachedEventsTriggered.length) return false;
        return ((cachedEventsTriggered[word] >> bit) & 1) != 0;
    }

    // Hint target is active if its grab event fired and the item isn't
    // owned yet, matches ChozoStatueHintCheckTargetIsActivated.
    private boolean isStatueTargetActive(int[] target) {
        int hintEvent = target[3];
        if (!checkEvent(hintEvent)) return false;
        int conditionKind = target[4], conditionBit = target[5];
        int have = conditionKind == COND_BEAM_BOMBS ? cachedBeamBombs : cachedSuitMisc;
        if (have < 0) return false; // read failed, don't guess
        return (have & conditionBit) == 0; // not owned yet = still active
    }

    private static final float STATUE_HINT_SIZE_CELLS = 1.0f;

    private void drawStatueHints(Canvas canvas, int areaIndex, float destLeft, float destTop, float destWidth, float destHeight,
                                  float cropCx, float cropCy, float scale) {
        ensureStatueHintCacheFresh();
        if (cachedEventsTriggered == null) return;

        float cellPx = TILE_PX * scale;
        float destRight = destLeft + destWidth, destBottom = destTop + destHeight;

        for (int[] target : STATUE_TARGETS) {
            if (target[0] != areaIndex) continue;
            if (!isStatueTargetActive(target)) continue;

            // +0.5f centers the marker in the cell instead of the corner,
            // target[1]/target[2] are the cell's top-left indices.
            float sx = destLeft + (target[1] + 0.5f - cropCx) * TILE_PX * scale;
            float sy = destTop + (target[2] + 0.5f - cropCy) * TILE_PX * scale;
            float half = cellPx * STATUE_HINT_SIZE_CELLS * 0.5f;
            if (sx < destLeft - half || sx > destRight + half || sy < destTop - half || sy > destBottom + half) continue;

            // Diamond shape so it's never confused with exit arrow triangles.
            // No label, unlike exit arrows, presence alone is the hint.
            Path diamond = new Path();
            diamond.moveTo(sx, sy - half);
            diamond.lineTo(sx + half, sy);
            diamond.lineTo(sx, sy + half);
            diamond.lineTo(sx - half, sy);
            diamond.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(COL_ENERGY_PIP);
            canvas.drawPath(diamond, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.5f, cellPx * 0.08f));
            paint.setColor(Color.BLACK);
            canvas.drawPath(diamond, paint);
        }
    }

    // ---- cross-area exit arrows ----
    //
    // Must reuse drawMap's own destLeft/destTop/cropCx/cropCy/scale rather
    // than recompute them, otherwise this can drift out of sync with the
    // room art's own zoom/pan transform.
    private static final float ARROW_SIZE_CELLS = 1.15f; // fixed cell size so arrows zoom with the room art
    private static final float ARROW_HIT_PAD_PX = 14f; // extra tappable margin, glyph alone is a small target

    private void drawExitArrows(Canvas canvas, int areaIndex, float destLeft, float destTop, float destWidth, float destHeight,
                                 float cropCx, float cropCy, float scale, byte[] exploredBits, boolean hasMapStation) {
        exitArrowVisibleCount = 0;
        float cellPx = TILE_PX * scale;
        float destRight = destLeft + destWidth, destBottom = destTop + destHeight;

        for (int row = 0; row < EXIT_ARROWS.length && exitArrowVisibleCount < exitArrowRects.length; row++) {
            float[] e = EXIT_ARROWS[row];
            if ((int) e[0] != areaIndex) continue;
            float doorCellX = e[1], doorCellY = e[2];
            int destArea = (int) e[3];

            // Only show an arrow once its door cell is explored, or the
            // area's Map Station is collected. Otherwise arrows spoiled
            // exits to unexplored areas.
            boolean doorExplored = exploredBits != null
                    && isExploredCell(exploredBits, clampInt(Math.round(doorCellX), 0, MINIMAP_SIZE - 1), clampInt(Math.round(doorCellY), 0, MINIMAP_SIZE - 1));
            if (!doorExplored && !hasMapStation) continue;

            // Cell -> screen-pixel, same transform as the Samus dot above.
            float sx = destLeft + (doorCellX - cropCx) * TILE_PX * scale;
            float sy = destTop + (doorCellY - cropCy) * TILE_PX * scale;

            // Skip if outside the current crop/viewport window.
            float halfGlyph = cellPx * ARROW_SIZE_CELLS * 0.5f;
            if (sx < destLeft - halfGlyph || sx > destRight + halfGlyph
                    || sy < destTop - halfGlyph || sy > destBottom + halfGlyph) {
                continue;
            }

            // Dedup by screen position: two doors landing a few px apart
            // would draw overlapping glyphs.
            boolean duplicate = false;
            for (int i = 0; i < exitArrowVisibleCount; i++) {
                RectF prior = exitArrowRects[i];
                if (Math.abs(prior.centerX() - sx) < 6f && Math.abs(prior.centerY() - sy) < 6f) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) continue;

            int direction = (int) e[4];
            drawSingleExitArrow(canvas, sx, sy, cellPx, destArea, direction);

            int idx = exitArrowVisibleCount++;
            float glyphHalf = cellPx * ARROW_SIZE_CELLS * 0.5f;
            exitArrowRects[idx].set(sx - glyphHalf - ARROW_HIT_PAD_PX, sy - glyphHalf - ARROW_HIT_PAD_PX,
                    sx + glyphHalf + ARROW_HIT_PAD_PX, sy + glyphHalf + ARROW_HIT_PAD_PX);
            exitArrowRow[idx] = row; // row index, not destArea, so onTouchEvent can look up the door position too
        }
    }

    // Arrow glyph + destination label at a door, pointing the direction
    // that door's wall actually faces. direction is 0=UP/1=DOWN/2=LEFT/3=RIGHT.
    private void drawSingleExitArrow(Canvas canvas, float sx, float sy, float cellPx, int destArea, int direction) {
        float triHalfW = cellPx * ARROW_SIZE_CELLS * 0.5f;
        float triH = cellPx * ARROW_SIZE_CELLS * 0.85f;

        // Build pointing up, then rotate to the real direction.
        Path tri = new Path();
        tri.moveTo(sx, sy - triH * 0.6f);
        tri.lineTo(sx - triHalfW, sy + triH * 0.4f);
        tri.lineTo(sx + triHalfW, sy + triH * 0.4f);
        tri.close();

        float rotationDegrees;
        switch (direction) {
            case DIR_DOWN: rotationDegrees = 180f; break;
            case DIR_LEFT: rotationDegrees = -90f; break;
            case DIR_RIGHT: rotationDegrees = 90f; break;
            default: rotationDegrees = 0f; break; // DIR_UP
        }
        int rotateSave = -1;
        if (rotationDegrees != 0f) {
            rotateSave = canvas.save();
            canvas.rotate(rotationDegrees, sx, sy);
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COL_ACCENT);
        canvas.drawPath(tri, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, cellPx * 0.08f));
        paint.setColor(Color.BLACK);
        canvas.drawPath(tri, paint);

        if (rotateSave >= 0) canvas.restoreToCount(rotateSave);

        // Label offsets outward along whichever wall the door is on. Text
        // itself is never rotated, only its anchor position moves.
        String label = destArea >= 0 && destArea < AREA_NAMES.length ? AREA_NAMES[destArea] : "?";
        float pixelSize = PixelFont.pixelSizeForHeight(Math.max(6f, cellPx * 0.5f));
        float labelOffset = triH * 0.6f + cellPx * 0.15f;
        float labelCx = sx, labelTopY;
        Paint.Align labelAlign = Paint.Align.CENTER;
        switch (direction) {
            case DIR_DOWN:
                labelTopY = sy + labelOffset;
                break;
            case DIR_LEFT:
                labelCx = sx - labelOffset;
                labelTopY = sy - PixelFont.glyphHeight(pixelSize) / 2f;
                labelAlign = Paint.Align.RIGHT;
                break;
            case DIR_RIGHT:
                labelCx = sx + labelOffset;
                labelTopY = sy - PixelFont.glyphHeight(pixelSize) / 2f;
                labelAlign = Paint.Align.LEFT;
                break;
            default: // DIR_UP
                labelTopY = sy - labelOffset - PixelFont.glyphHeight(pixelSize);
                break;
        }

        // Dark backing chip so the label stays legible over busy room art.
        float textW = PixelFont.measureWidth(label, pixelSize);
        float chipPad = pixelSize * 1.5f;
        float chipLeft, chipRight;
        if (labelAlign == Paint.Align.LEFT) {
            chipLeft = labelCx - chipPad; chipRight = labelCx + textW + chipPad;
        } else if (labelAlign == Paint.Align.RIGHT) {
            chipLeft = labelCx - textW - chipPad; chipRight = labelCx + chipPad;
        } else {
            chipLeft = labelCx - textW / 2f - chipPad; chipRight = labelCx + textW / 2f + chipPad;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(180, 0, 0, 0));
        canvas.drawRect(chipLeft, labelTopY - chipPad * 0.5f,
                chipRight, labelTopY + PixelFont.glyphHeight(pixelSize) + chipPad * 0.5f, paint);
        PixelFont.drawText(canvas, label, labelCx, labelTopY, pixelSize, Color.WHITE, labelAlign);
    }

    // Hit test against exitArrowRects, rebuilt fresh every drawMap call.
    // Returns the matching EXIT_ARROWS row index, or -1.
    private int findExitArrowAt(float x, float y) {
        for (int i = 0; i < exitArrowVisibleCount; i++) {
            if (exitArrowRects[i].contains(x, y)) return exitArrowRow[i];
        }
        return -1;
    }

    // Enters preview mode for the area a tapped exit arrow leads to.
    private void enterAreaPreview(int exitArrowRow) {
        float[] e = EXIT_ARROWS[exitArrowRow];
        int destArea = (int) e[3];
        previewArea = destArea;
        previewDestAreaOriginRow = exitArrowRow;
        invalidate();
    }

    // Shown instead of the real room grid while previewArea != null. Also
    // shows which door was tapped to get here and how to get back.
    private void drawAreaPreviewPlaceholder(Canvas canvas, RectF area) {
        int areaIdx = previewArea;
        String name = areaIdx >= 0 && areaIdx < AREA_NAMES.length ? AREA_NAMES[areaIdx] : "?";

        float titleSize = PixelFont.pixelSizeForHeight(area.height() * 0.09f);
        PixelFont.drawText(canvas, name, area.centerX(), area.centerY() - PixelFont.glyphHeight(titleSize) * 1.9f,
                titleSize, COL_ACCENT, Paint.Align.CENTER);

        if (previewDestAreaOriginRow >= 0 && previewDestAreaOriginRow < EXIT_ARROWS.length) {
            int fromArea = (int) EXIT_ARROWS[previewDestAreaOriginRow][0];
            String fromName = fromArea >= 0 && fromArea < AREA_NAMES.length ? AREA_NAMES[fromArea] : "?";
            String subLabel = "VIA DOOR FROM " + fromName;
            float subSize = PixelFont.pixelSizeForHeight(area.height() * 0.04f);
            PixelFont.drawText(canvas, subLabel, area.centerX(), area.centerY() - PixelFont.glyphHeight(titleSize) * 0.5f,
                    subSize, COL_DIM_GRAY, Paint.Align.CENTER);
        }

        String msg1 = "WALK THERE TO SEE";
        String msg2 = "THIS AREA'S MAP";
        float msgSize = PixelFont.pixelSizeForHeight(area.height() * 0.05f);
        PixelFont.drawText(canvas, msg1, area.centerX(), area.centerY() + PixelFont.glyphHeight(msgSize),
                msgSize, COL_DIM_GRAY, Paint.Align.CENTER);
        PixelFont.drawText(canvas, msg2, area.centerX(), area.centerY() + PixelFont.glyphHeight(msgSize) * 2.3f,
                msgSize, COL_DIM_GRAY, Paint.Align.CENTER);
    }

    // BACK button while previewArea != null, top-left of the map area.
    private void drawBackButton(Canvas canvas, RectF area) {
        float btnH = area.height() * 0.12f;
        float btnW = btnH * 2.6f;
        backButtonRect.set(area.left + area.width() * 0.03f, area.top + area.height() * 0.03f,
                area.left + area.width() * 0.03f + btnW, area.top + area.height() * 0.03f + btnH);
        drawPixelBox(canvas, backButtonRect, COL_PANEL_BG, COL_ACCENT, true);
        float textSize = backButtonRect.height() * 0.4f;
        PixelFont.drawText(canvas, "< BACK", backButtonRect.centerX(), backButtonRect.centerY() - textSize / 2f,
                PixelFont.pixelSizeForHeight(textSize), COL_ACCENT, Paint.Align.CENTER);
    }

    // ---- map pins ----
    //
    // Pins are scoped by ROM path only, not by save slot (no confirmed
    // address yet for which of the 3 save slots is active in this fork).
    // So pins from one save slot still show up after switching slots on
    // the same ROM. Fine for now.
    //
    // Also scoped by area (0-6) instead of one flat list, since the room
    // view only ever shows the current area's pins.
    private String currentPinPrefsKey = null;

    private String pinPrefsKeyFor(String romPath) {
        String romKey = romPath == null ? "unknown" : romPath.replaceAll("[^A-Za-z0-9]", "_");
        return "zmMapPins_" + romKey;
    }

    // Called once per onDraw tick, cheap since it's just a String compare
    // against the cached content path most ticks.
    private void ensurePinsMatchCurrentRom() {
        String key = pinPrefsKeyFor(activity.nativeGetContentPath());
        if (!key.equals(currentPinPrefsKey)) {
            currentPinPrefsKey = key;
            loadPins();
        }
    }

    // Format: "area,x100,y100;area,x100,y100;...", cell position * 100,
    // truncated to an int, round-trips without float parsing. Separate
    // SharedPreferences file so Zero Mission and Super Metroid pins don't collide.
    private void loadPins() {
        SharedPreferences prefs = getContext().getSharedPreferences("secondscreen_zm", Context.MODE_PRIVATE);
        String s = prefs.getString(currentPinPrefsKey, "");
        pinCount = 0;
        for (String entry : s.split(";")) {
            if (entry.isEmpty() || pinCount >= MAX_PINS) continue;
            String[] parts = entry.split(",");
            if (parts.length != 3) continue;
            try {
                int a = Integer.parseInt(parts[0]);
                // Guard against a hand-edited/corrupted prefs value.
                if (a < 0 || a >= WORLD_AREA_COUNT) continue;
                pinArea[pinCount] = a;
                pinX[pinCount] = Integer.parseInt(parts[1]) / 100f;
                pinY[pinCount] = Integer.parseInt(parts[2]) / 100f;
                pinCount++;
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void savePins() {
        if (currentPinPrefsKey == null) return; // ROM context not determined yet
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pinCount; i++) {
            if (i > 0) sb.append(';');
            sb.append(pinArea[i]).append(',').append(Math.round(pinX[i] * 100)).append(',').append(Math.round(pinY[i] * 100));
        }
        getContext().getSharedPreferences("secondscreen_zm", Context.MODE_PRIVATE)
                .edit().putString(currentPinPrefsKey, sb.toString()).apply();
    }

    // Adds a pin at the given position, or removes the nearest existing pin
    // in that area if one is within hitRadiusCells. Long-press toggles.
    private void togglePin(int area, float cellX, float cellY, float hitRadiusCells) {
        if (currentPinPrefsKey == null) return; // ROM context not determined yet
        int nearest = -1;
        float nearestDistSq = hitRadiusCells * hitRadiusCells;
        for (int i = 0; i < pinCount; i++) {
            if (pinArea[i] != area) continue;
            float dx = pinX[i] - cellX, dy = pinY[i] - cellY;
            float distSq = dx * dx + dy * dy;
            if (distSq <= nearestDistSq) {
                nearestDistSq = distSq;
                nearest = i;
            }
        }
        if (nearest != -1) {
            // Swap-remove, pin order doesn't matter.
            pinCount--;
            pinArea[nearest] = pinArea[pinCount];
            pinX[nearest] = pinX[pinCount];
            pinY[nearest] = pinY[pinCount];
        } else if (pinCount < MAX_PINS) {
            pinArea[pinCount] = area;
            pinX[pinCount] = cellX;
            pinY[pinCount] = cellY;
            pinCount++;
        }
        savePins();
        invalidate();
    }

    // Converts a long-press's screen coords into a minimap-cell position
    // and toggles a pin there. No-op while previewing another area.
    private void handleLongPress(float x, float y) {
        if (currentTab != Tab.MAP || previewArea != null) return;
        if (roomMapCellSize <= 0f) return;
        float cellX = roomMapCropCx + (x - roomMapDestLeft) / roomMapCellSize;
        float cellY = roomMapCropCy + (y - roomMapDestTop) / roomMapCellSize;
        byte[] areaData = activity.nativeReadCoreMemoryMapped(OFF_AREA, 1);
        if (areaData == null || areaData.length < 1) return;
        int area = areaData[0] & 0xFF;
        // area is a raw memory value, guard against garbage before indexing with it.
        if (area < 0 || area >= WORLD_AREA_COUNT) return;
        togglePin(area, cellX, cellY, PIN_HIT_RADIUS_CELLS);
    }

    private void drawPins(Canvas canvas, int area, float destLeft, float destTop, float cropCx, float cropCy, float scale) {
        for (int i = 0; i < pinCount; i++) {
            if (pinArea[i] != area) continue;
            float px = destLeft + (pinX[i] - cropCx) * TILE_PX * scale;
            float py = destTop + (pinY[i] - cropCy) * TILE_PX * scale;
            drawPinMarker(canvas, px, py, TILE_PX * scale);
        }
    }

    // Pole base is anchored at (cx, cy), the actual pinned position, flag
    // hangs above/right of it.
    private void drawPinMarker(Canvas canvas, float cx, float cy, float cellSize) {
        float poleH = cellSize * 1.8f;
        float flagW = cellSize * 1.3f, flagH = cellSize * 0.9f;

        pinPaint.setStyle(Paint.Style.STROKE);
        pinPaint.setColor(Color.BLACK);
        pinPaint.setStrokeWidth(Math.max(2f, cellSize * 0.18f));
        canvas.drawLine(cx, cy, cx, cy - poleH, pinPaint);

        Path flag = new Path();
        flag.moveTo(cx, cy - poleH);
        flag.lineTo(cx + flagW, cy - poleH + flagH * 0.35f);
        flag.lineTo(cx, cy - poleH + flagH);
        flag.close();

        pinPaint.setStyle(Paint.Style.FILL);
        pinPaint.setColor(COL_ACCENT);
        canvas.drawPath(flag, pinPaint);
        pinPaint.setStyle(Paint.Style.STROKE);
        pinPaint.setStrokeWidth(Math.max(1.5f, cellSize * 0.1f));
        pinPaint.setColor(Color.BLACK);
        canvas.drawPath(flag, pinPaint);

        pinPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, Math.max(2f, cellSize * 0.14f), pinPaint);
    }

    private static final int MAX_PINS = 64;
    private final int[] pinArea = new int[MAX_PINS];
    private final float[] pinX = new float[MAX_PINS];
    private final float[] pinY = new float[MAX_PINS];
    private int pinCount = 0;
    private static final float PIN_HIT_RADIUS_CELLS = 1.1f;
    private final Paint pinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ---- tab bar + zoom controls ----

    // 3 equal-width tab buttons filling the footer strip.
    private void layoutTabButtons(RectF bar) {
        int tabCount = tabButtonRects.length;
        float tabGap = bar.width() * 0.012f;
        float tabW = (bar.width() - tabGap * (tabCount - 1)) / tabCount;
        for (int i = 0; i < tabCount; i++) {
            float tx0 = bar.left + i * (tabW + tabGap);
            tabButtonRects[i].set(tx0, bar.top, tx0 + tabW, bar.bottom);
        }
    }

    // Active tab gets a highlighted background plus accent border.
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

    // 3 equal-width buttons: reset camera (left), zoom out, zoom in (right).
    private void layoutZoomButtons(RectF bar) {
        float btnGap = bar.width() * 0.02f;
        float btnW = (bar.width() - btnGap * 2) / 3f;
        float bx = bar.left;
        resetCameraBtn.set(bx, bar.top, bx + btnW, bar.bottom);
        bx += btnW + btnGap;
        zoomOutBtn.set(bx, bar.top, bx + btnW, bar.bottom);
        bx += btnW + btnGap;
        zoomInBtn.set(bx, bar.top, bx + btnW, bar.bottom);
    }

    // Small bordered box, shared by the tab bar, zoom buttons, and
    // placeholder tab panels.
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

    // Reset camera (crosshair) plus zoom out/in buttons, +/- line icons.
    private void drawZoomButtons(Canvas canvas) {
        drawPixelBox(canvas, resetCameraBtn, COL_PANEL_BG, COL_BORDER_DARK, true);
        paint.setColor(COL_ACCENT);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, Math.min(resetCameraBtn.width(), resetCameraBtn.height()) * 0.08f));
        float rcx = resetCameraBtn.centerX(), rcy = resetCameraBtn.centerY();
        float rHalf = Math.min(resetCameraBtn.width(), resetCameraBtn.height()) * 0.26f;
        float rGap = rHalf * 0.4f;
        // Crosshair icon: ring with tick marks poking past it.
        canvas.drawCircle(rcx, rcy, rHalf, paint);
        canvas.drawLine(rcx - rHalf - rGap, rcy, rcx - rHalf + rGap, rcy, paint);
        canvas.drawLine(rcx + rHalf - rGap, rcy, rcx + rHalf + rGap, rcy, paint);
        canvas.drawLine(rcx, rcy - rHalf - rGap, rcx, rcy - rHalf + rGap, paint);
        canvas.drawLine(rcx, rcy + rHalf - rGap, rcx, rcy + rHalf + rGap, paint);

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

    // ITEMS/SETUP placeholder, real content is later work.
    private void drawPlaceholderTab(Canvas canvas, RectF area, String tabName) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COL_PANEL_BG);
        canvas.drawRoundRect(area, area.height() * 0.02f, area.height() * 0.02f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, area.height() * 0.01f));
        paint.setColor(COL_BORDER_DARK);
        canvas.drawRoundRect(area, area.height() * 0.02f, area.height() * 0.02f, paint);

        String msg = tabName + " - COMING SOON";
        float pixelSize = PixelFont.pixelSizeForHeight(area.height() * 0.06f);
        PixelFont.drawText(canvas, msg, area.centerX(), area.centerY() - PixelFont.glyphHeight(pixelSize) / 2f,
                pixelSize, COL_DIM_GRAY, Paint.Align.CENTER);
    }

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

    // Where the map area currently is, for onTouchEvent's hit-testing.
    private final RectF mapTapRect = new RectF();

    // Drag-to-pan state, lets ACTION_UP tell a tap apart from a drag.
    private static final float TAP_SLOP_PX = 12f;
    private float dragLastX, dragLastY, dragTotalMoved;

    // Manual long-press detection, no GestureDetector on this view.
    private float longPressX, longPressY;
    private boolean longPressFired = false;
    private final Runnable longPressRunnable = new Runnable() {
        @Override public void run() {
            longPressFired = true;
            handleLongPress(longPressX, longPressY);
        }
    };

    // Constructed in the constructor (needs context), fed from onTouchEvent.
    private final ScaleGestureDetector scaleDetector;

    // Pinch-zoom feeds through mapTapRect first, ACTION_DOWN arms a
    // long-press timer (unless the press starts on an exit arrow),
    // ACTION_MOVE either cancels it and pans the room view, or ACTION_UP
    // treats it as a tap and hit-tests exit arrows / BACK button / tab
    // buttons / zoom buttons in that order.
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        byte[] statsBlock = activity.nativeReadCoreMemoryMapped(STATS_BLOCK_OFFSET, STATS_BLOCK_LENGTH);
        if (statsBlock == null || statsBlock.length < STATS_BLOCK_LENGTH)
            return true; // no game loaded, nothing to tap

        if (currentTab == Tab.MAP && previewArea == null && mapTapRect.contains(event.getX(), event.getY())) {
            // Pinch-zoom only applies to the live room view, not the preview placeholder.
            scaleDetector.onTouchEvent(event);
        }

        float x = event.getX(), y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragLastX = x;
                dragLastY = y;
                dragTotalMoved = 0f;
                // Don't arm long-press over an exit arrow, tapping an arrow
                // shouldn't also queue a pin-drop. Suppressed while previewing too.
                longPressFired = false;
                if (currentTab == Tab.MAP && previewArea == null && mapTapRect.contains(x, y)
                        && findExitArrowAt(x, y) < 0) {
                    longPressX = x;
                    longPressY = y;
                    uiHandler.postDelayed(longPressRunnable, android.view.ViewConfiguration.getLongPressTimeout());
                }
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = x - dragLastX, dy = y - dragLastY;
                dragTotalMoved += Math.abs(dx) + Math.abs(dy);
                // Real movement cancels a pending long-press, a panning
                // finger shouldn't also drop a pin where it started.
                if (dragTotalMoved > TAP_SLOP_PX) {
                    uiHandler.removeCallbacks(longPressRunnable);
                }
                // Pan only on the MAP tab's map area, past the tap
                // threshold, and not mid-pinch (scaleDetector handles that).
                if (currentTab == Tab.MAP && previewArea == null && mapTapRect.contains(dragLastX, dragLastY)
                        && dragTotalMoved > TAP_SLOP_PX && !scaleDetector.isInProgress()) {
                    roomPanOffsetX -= dx * roomViewCellsPerPixel;
                    roomPanOffsetY -= dy * roomViewCellsPerPixel;
                    invalidate();
                }
                dragLastX = x;
                dragLastY = y;
                return true;
            }

            case MotionEvent.ACTION_UP:
                uiHandler.removeCallbacks(longPressRunnable);
                if (longPressFired) return true; // already handled as a pin toggle
                break; // real tap, not a drag

            default:
                uiHandler.removeCallbacks(longPressRunnable);
                return true;
        }

        if (dragTotalMoved > TAP_SLOP_PX)
            return true; // ended a drag, not a tap

        // While previewing, only the BACK button is tappable.
        if (currentTab == Tab.MAP && previewArea != null) {
            if (backButtonRect.contains(x, y)) {
                previewArea = null;
                previewDestAreaOriginRow = -1;
                invalidate();
            }
            return true;
        }

        // Exit-arrow taps take priority over everything else on the map.
        if (currentTab == Tab.MAP) {
            int arrowRow = findExitArrowAt(x, y);
            if (arrowRow >= 0) {
                enterAreaPreview(arrowRow);
                return true;
            }
        }

        for (int i = 0; i < tabButtonRects.length; i++) {
            if (!tabButtonRects[i].contains(x, y)) continue;
            currentTab = Tab.values()[i];
            invalidate();
            return true;
        }

        if (currentTab == Tab.MAP) {
            if (resetCameraBtn.contains(x, y)) {
                roomPanOffsetX = 0f;
                roomPanOffsetY = 0f;
                roomZoomFactor = DEFAULT_ZOOM;
                invalidate();
                return true;
            }
            if (zoomInBtn.contains(x, y) || zoomOutBtn.contains(x, y)) {
                boolean zoomIn = zoomInBtn.contains(x, y);
                roomZoomFactor = zoomIn
                        ? Math.min(MAX_ZOOM, roomZoomFactor * ZOOM_BUTTON_STEP)
                        : Math.max(MIN_ZOOM, roomZoomFactor / ZOOM_BUTTON_STEP);
                invalidate();
                return true;
            }
        }
        return true;
    }
}
