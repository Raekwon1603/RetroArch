package com.retroarch.browser.retroactivity;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

/**
 * Hosts the right second-screen View on the Thor's second physical panel,
 * same pattern as super_metroid-android's own working
 * SecondScreenPresentation.java (that project's real, ported-from app).
 * Non-focusable so touches on this panel never steal input focus from the
 * emulated game running through RetroArch on the main panel.
 *
 * Picks SuperMetroidSecondScreenView (SNES, .smc/.sfc content) or
 * ZeroMissionSecondScreenView (GBA, everything else) by the loaded
 * content's file extension - same detection
 * SuperMetroidSecondScreenView.isSnesContent() uses internally to no-op
 * its own WRAM reads for non-SNES content, kept in sync with that check
 * rather than introducing a second, possibly-diverging way to tell the two
 * apart. Neither view's constructor touches core memory, so it's safe to
 * decide this once up front rather than re-checking every frame.
 */
public class SuperMetroidSecondScreenPresentation extends Presentation {
    private final RetroActivityCommon activity;

    public SuperMetroidSecondScreenPresentation(Context outerContext, Display display, RetroActivityCommon activity) {
        super(outerContext, display);
        this.activity = activity;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setCancelable(false);

        String contentPath = activity.nativeGetContentPath();
        String lower = contentPath == null ? "" : contentPath.toLowerCase(java.util.Locale.ROOT);
        View view = (lower.endsWith(".smc") || lower.endsWith(".sfc"))
                ? new SuperMetroidSecondScreenView(getContext(), activity)
                : new ZeroMissionSecondScreenView(getContext(), activity);
        setContentView(view, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }
}
