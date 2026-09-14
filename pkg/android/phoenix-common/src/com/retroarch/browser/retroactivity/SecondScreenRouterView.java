package com.retroarch.browser.retroactivity;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Picks SuperMetroidSecondScreenView (SNES, .smc/.sfc content) or
 * ZeroMissionSecondScreenView (GBA, everything else) by the loaded
 * content's file extension, re-checked on a poll loop rather than once at
 * construction time.
 *
 * Real bug this fixes: SuperMetroidSecondScreenPresentation used to pick the
 * view once in its own onCreate(), but onCreate() runs from
 * RetroActivityFuture.onStart() (showSecondScreenIfPresent), which fires
 * long before any core/content is actually loaded - nativeGetContentPath()
 * at that point returns null (or a stale path from a previous session), so
 * the extension check always fell through to the GBA view regardless of
 * which game was actually running. Confirmed on-device: Super Metroid's
 * second screen showed the Zero Mission view's idle logo the entire
 * session. Every other content-path read in this codebase
 * (SuperMetroidSecondScreenView's own nativeGetContentPath call sites)
 * already re-checks per-frame/per-poll instead of caching a one-time
 * decision at construction time - this router now matches that pattern.
 *
 * A plain FrameLayout hosting exactly one child at a time (the real
 * SuperMetroidSecondScreenView or ZeroMissionSecondScreenView), swapped
 * whenever the loaded content's type changes - e.g. quitting back to
 * RetroArch's own menu and loading a different game without restarting the
 * whole Activity/Presentation.
 */
public class SecondScreenRouterView extends FrameLayout {
    private static final long POLL_INTERVAL_MS = 1000;

    private final RetroActivityCommon activity;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private View currentChild;
    private boolean currentChildIsSnes;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            ensureChildMatchesContent();
            uiHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    public SecondScreenRouterView(Context context, RetroActivityCommon activity) {
        super(context);
        this.activity = activity;
    }

    private boolean isSnesContent() {
        String contentPath = activity.nativeGetContentPath();
        String lower = contentPath == null ? "" : contentPath.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".smc") || lower.endsWith(".sfc");
    }

    private void ensureChildMatchesContent() {
        boolean wantSnes = isSnesContent();
        if (currentChild != null && wantSnes == currentChildIsSnes) return;

        if (currentChild != null) removeView(currentChild);
        currentChild = wantSnes
                ? new SuperMetroidSecondScreenView(getContext(), activity)
                : new ZeroMissionSecondScreenView(getContext(), activity);
        currentChildIsSnes = wantSnes;
        addView(currentChild, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ensureChildMatchesContent();
        uiHandler.post(pollRunnable);
    }

    @Override
    protected void onDetachedFromWindow() {
        uiHandler.removeCallbacks(pollRunnable);
        super.onDetachedFromWindow();
    }
}
