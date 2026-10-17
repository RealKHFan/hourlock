package app.unscroll;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * One screen, two jobs:
 *
 * 1. Blocked notice (opened by the service when you open a blocked app):
 *    only says it's blocked and has a Close button. No timer, no unlock.
 *    To unlock you have to open Hourlock yourself.
 *
 * 2. Unlock screen (opened from the "Unlock" button inside Hourlock):
 *    the Unlock button only works after the app's wait (15 s by default).
 *    The wait starts over if you leave this screen.
 */
public class BlockActivity extends Activity {
    static final String EXTRA_PKG = "pkg";
    /** true = unlock screen (from Hourlock), false = blocked notice (from the service). */
    static final String EXTRA_FROM_APP = "fromApp";

    private static final int BG_NORMAL = 0xFF101615;
    private static final int BG_RED = 0xFF4A0F12;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Store store;
    private String pkg;
    private boolean unlockScreen;
    private long waitEndsAt;

    private View root;
    private ImageView icon;
    private TextView title, reason, stats, redNote, hint;
    private ProgressBar progress;
    private Button main, back;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            render();
            if (waitEndsAt > SystemClock.elapsedRealtime()) handler.postDelayed(this, 100);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_block);
        store = Store.get(this);
        root = findViewById(R.id.root);
        icon = findViewById(R.id.icon);
        title = findViewById(R.id.title);
        reason = findViewById(R.id.reason);
        stats = findViewById(R.id.stats);
        redNote = findViewById(R.id.red_note);
        hint = findViewById(R.id.hint);
        progress = findViewById(R.id.progress);
        main = findViewById(R.id.unlock);
        back = findViewById(R.id.close);

        main.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (unlockScreen) onUnlock(); else leave();
            }
        });
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { leave(); }
        });
        readIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readIntent(intent);
        start();
    }

    private void readIntent(Intent i) {
        pkg = i.getStringExtra(EXTRA_PKG);
        unlockScreen = i.getBooleanExtra(EXTRA_FROM_APP, false);
        if (pkg == null || store.rule(pkg) == null) {
            finish();
            return;
        }
        icon.setImageDrawable(Fmt.icon(this, pkg));
        if (unlockScreen) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    /** Unlock screen: (re)start the wait. Blocked notice: just draw it. */
    private void start() {
        handler.removeCallbacks(tick);
        Engine.Rule r = store.rule(pkg);
        if (r == null) {
            finish();
            return;
        }
        waitEndsAt = unlockScreen ? SystemClock.elapsedRealtime() + r.unlockWaitMs() : 0;
        handler.post(tick);
    }

    private void render() {
        Engine.Rule r = store.rule(pkg);
        if (r == null) {
            finish();
            return;
        }
        Engine.State s = store.state(pkg);
        String label = Fmt.label(this, r);
        boolean blocked = Engine.isBlocked(r, s);
        boolean red = Engine.isRed(s);

        int bg = red ? BG_RED : BG_NORMAL;
        root.setBackgroundColor(bg);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);

        if (!blocked) {
            title.setText(label + " isn't blocked right now");
            reason.setText(Engine.inUnlock(r, s)
                    ? Fmt.clock(s.unlockLeftMs) + " of unlocked time left."
                    : Fmt.modeName(r) + ".");
        } else {
            title.setText(unlockScreen ? "Unlock " + label + "?" : label + " is blocked");
            reason.setText(r.mode == Engine.MODE_LIMIT
                    ? "You've used today's " + r.limitMin + " min (" + Fmt.mins(s.usedMs) + ")."
                    : "You set it to always blocked.");
        }

        String unlockWord = s.unlocks == 1 ? "unlock" : "unlocks";
        stats.setText(s.unlocks + " " + unlockWord + " today  ·  " + Fmt.mins(s.usedMs) + " used");

        if (red) {
            redNote.setVisibility(View.VISIBLE);
            redNote.setText("You've unlocked " + label + " " + s.unlocks + " times today ("
                    + (s.unlocks * r.unlockMin) + " min extra). That's not good for you.");
        } else if (unlockScreen && blocked && Engine.nextUnlockTurnsRed(s)) {
            redNote.setVisibility(View.VISIBLE);
            redNote.setText("Heads up: this would be unlock #" + (s.unlocks + 1)
                    + " today, and " + label + " turns red.");
        } else {
            redNote.setVisibility(View.GONE);
        }

        if (!unlockScreen) {
            renderNotice();
        } else {
            renderUnlock(r, label, blocked);
        }
    }

    /** Blocked notice: one big Close button and nothing else to tap. */
    private void renderNotice() {
        hint.setVisibility(View.VISIBLE);
        hint.setText("Really want it? Open Hourlock yourself and tap Unlock there.");
        progress.setVisibility(View.GONE);
        main.setEnabled(true);
        main.setAlpha(1f);
        main.setText("Close");
        back.setVisibility(View.GONE);
    }

    private void renderUnlock(Engine.Rule r, String label, boolean blocked) {
        hint.setVisibility(View.GONE);
        back.setVisibility(View.VISIBLE);
        back.setText("Back");
        long left = waitEndsAt - SystemClock.elapsedRealtime();
        if (!blocked) {
            progress.setVisibility(View.INVISIBLE);
            main.setEnabled(true);
            main.setAlpha(1f);
            main.setText("Open " + label);
        } else if (left > 0) {
            long total = r.unlockWaitMs();
            progress.setVisibility(View.VISIBLE);
            progress.setProgress((int) (1000 * (total - left) / total));
            main.setEnabled(false);
            main.setAlpha(0.45f);
            main.setText(total >= 60_000
                    ? "Wait " + Fmt.clock(left) + " to unlock"
                    : "Wait " + ((left + 999) / 1000) + " s to unlock");
        } else {
            progress.setVisibility(View.VISIBLE);
            progress.setProgress(1000);
            main.setEnabled(true);
            main.setAlpha(1f);
            main.setText("Unlock " + r.unlockMin + " min of " + label);
        }
    }

    private void onUnlock() {
        Engine.Rule r = store.rule(pkg);
        if (r == null) return;
        if (Engine.isBlocked(r, store.state(pkg))) {
            if (waitEndsAt > SystemClock.elapsedRealtime()) return; // still waiting
            store.grantUnlock(pkg);
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
        }
        finish();
    }

    /** Notice: go to the home screen. Unlock screen: back to Hourlock. */
    private void leave() {
        if (!unlockScreen) {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
        }
        finish();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        leave(); // never fall back into the blocked app
    }
}
