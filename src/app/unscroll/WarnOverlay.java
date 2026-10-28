package app.unscroll;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * The Warn-mode popups. They are drawn by the accessibility service on top of
 * the app (no extra permission needed). Each new popup is stacked a little
 * lower than the last and gets redder; leaving the app removes them all.
 */
final class WarnOverlay {
    private static final int[] COLORS = {
            0xFFB45309, // amber
            0xFFC2410C, // orange
            0xFFB91C1C, // red
            0xFF8F1414, // dark red
    };
    /** How many popups get their own, lower spot. */
    private static final int STEPS = 8;

    private final BlockerService svc;
    private final WindowManager wm;
    private final HashMap<String, ArrayList<View>> shown = new HashMap<>();

    WarnOverlay(BlockerService svc) {
        this.svc = svc;
        this.wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
    }

    void show(String pkg, String label, long usedMs, int warnMin, int number) {
        ArrayList<View> list = shown.get(pkg);
        if (list == null) {
            list = new ArrayList<>();
            shown.put(pkg, list);
        }
        View card = buildCard(pkg, label, usedMs, warnMin, number);

        int screenW = svc.getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(screenW - dp(40), dp(420));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        // From the 9th popup on they share the lowest spot: replace that card instead of hiding more under it.
        if (list.size() > STEPS) remove(list.remove(list.size() - 1));
        int step = Math.min(list.size(), STEPS);
        lp.y = dp(-90 + step * 28);   // each popup a bit lower: they visibly pile up

        try {
            wm.addView(card, lp);
            list.add(card);
            card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } catch (RuntimeException e) {
            // window could not be added (service shutting down); ignore
        }
    }

    void clear(String pkg) {
        ArrayList<View> list = shown.remove(pkg);
        if (list == null) return;
        for (View v : list) remove(v);
    }

    void removeAll() {
        for (ArrayList<View> list : shown.values()) {
            for (View v : list) remove(v);
        }
        shown.clear();
    }

    private void remove(View v) {
        try {
            wm.removeView(v);
        } catch (RuntimeException ignored) {
            // already gone
        }
    }

    private View buildCard(final String pkg, String label, long usedMs, int warnMin, int number) {
        int color = COLORS[Math.min(number - 1, COLORS.length - 1)];
        long overMin = Math.max(0, usedMs / Engine.MIN - warnMin);

        final LinearLayout card = new LinearLayout(svc);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(18), dp(22), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(22));
        bg.setStroke(dp(2), 0x40FFFFFF);
        card.setBackground(bg);
        card.setElevation(dp(10));

        TextView badge = text("⚠  Popup #" + number + " this session", 13, true);
        badge.setAlpha(0.85f);
        card.addView(badge);

        TextView title = text(number == 1 ? "That's a lot of " + label : "Still on " + label + "?", 21, true);
        title.setPadding(0, dp(6), 0, 0);
        card.addView(title);

        String body;
        if (number == 1) {
            body = Fmt.mins(usedMs) + " today. You wanted to stop at " + warnMin + " min.\n"
                    + "Another popup will join this one every 2 minutes you stay.";
        } else {
            body = Fmt.mins(usedMs) + " today, " + overMin + " min over your " + warnMin + " min.\n"
                    + "These keep piling up until you leave.";
        }
        TextView msg = text(body, 15, false);
        msg.setPadding(0, dp(8), 0, dp(14));
        msg.setLineSpacing(0, 1.15f);
        card.addView(msg);

        LinearLayout buttons = new LinearLayout(svc);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);

        TextView ok = button("OK", false, color);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ArrayList<View> list = shown.get(pkg);
                if (list != null) list.remove(card);
                remove(card);
            }
        });
        TextView close = button("Close " + label, true, color);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                clear(pkg);
                svc.goHome();
            }
        });
        buttons.addView(ok);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gap.setMarginStart(dp(8));
        buttons.addView(close, gap);
        card.addView(buttons);
        return card;
    }

    private TextView text(String s, float sp, boolean bold) {
        TextView t = new TextView(svc);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView button(String s, boolean filled, int color) {
        TextView b = text(s, 15, true);
        b.setPadding(dp(18), dp(10), dp(18), dp(10));
        b.setMinHeight(dp(48));
        b.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(22));
        if (filled) {
            d.setColor(Color.WHITE);
            b.setTextColor(color);
        } else {
            d.setColor(Color.TRANSPARENT);
            d.setStroke(dp(1), 0x99FFFFFF);
        }
        b.setBackground(d);
        b.setClickable(true);
        return b;
    }

    private int dp(float v) {
        return Fmt.dp(svc, v);
    }
}
