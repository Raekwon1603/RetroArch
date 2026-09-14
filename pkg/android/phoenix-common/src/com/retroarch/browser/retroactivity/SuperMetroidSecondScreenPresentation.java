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
 * Hosts SecondScreenRouterView on the Thor's second physical panel, same
 * pattern as super_metroid-android's own working
 * SecondScreenPresentation.java (that project's real, ported-from app).
 * Non-focusable so touches on this panel never steal input focus from the
 * emulated game running through RetroArch on the main panel.
 *
 * The router itself (see SecondScreenRouterView) picks between
 * SuperMetroidSecondScreenView and ZeroMissionSecondScreenView by
 * re-checking the loaded content's file extension on a poll loop - content
 * isn't necessarily loaded yet by the time this Presentation is created
 * (onCreate runs from RetroActivityFuture.onStart(), before the emulator
 * core has loaded anything), so that choice can't be made just once here.
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

        SecondScreenRouterView router = new SecondScreenRouterView(getContext(), activity);
        setContentView(router, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }
}
