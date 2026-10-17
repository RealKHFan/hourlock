package app.unscroll;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The part that does the actual work. Android runs it in the background once
 * it is switched on under Settings > Accessibility.
 *
 * It only looks at WHICH apps have a window on screen (their package name).
 * It never reads text or anything else inside those apps.
 *
 * Every time the windows change, and every few seconds while a watched app is
 * on screen, it:
 *   1. books the on-screen time since the last check,
 *   2. sends you home + shows the block screen if an app is blocked,
 *   3. stacks a Warn popup when one is due,
 *   4. shows "30 s left" / "1 min left" heads-ups.
 */
public class BlockerService extends AccessibilityService {

    /** Set while the service is running (main thread only). */
    static BlockerService running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final HashSet<String> visible = new HashSet<>();
    private final HashMap<String, Engine.Session> sessions = new HashMap<>();
    private final HashMap<String, Long> lastSeen = new HashMap<>();

    private Store store;
    private WarnOverlay overlay;
    private long lastAccount;
    private long sessionDay;
    private boolean userActive = true;
    private boolean scanPending;
    private long scanDueAt;
    private boolean receiverOn;
    private String lastBlockedPkg;
    private long lastBlockAt;
    /** An app window had no readable root (mid-animation): look again soon. */
    private boolean windowNotReady;
    private int notReadyRetries;

    private final Runnable scanRunnable = new Runnable() {
        @Override public void run() { scan(); }
    };

    private final Runnable storeListener = new Runnable() {
        @Override public void run() { scheduleScan(0); }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { scheduleScan(0); }
    };

    @Override
    protected void onServiceConnected() {
        running = this;
        store = Store.get(this);
        overlay = new WarnOverlay(this);
        lastAccount = SystemClock.elapsedRealtime();
        sessionDay = Store.today();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(screenReceiver, f);
        }
        receiverOn = true;
        store.addListener(storeListener);
        scheduleScan(0);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        int t = e.getEventType();
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || t == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            scheduleScan(120); // small delay: lets the window list settle
        }
    }

    @Override
    public void onInterrupt() {}

    @Override
    public boolean onUnbind(Intent intent) {
        shutdown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        if (running != this) return;
        account(SystemClock.elapsedRealtime());
        visible.clear();
        handler.removeCallbacksAndMessages(null);
        if (receiverOn) {
            try { unregisterReceiver(screenReceiver); } catch (IllegalArgumentException ignored) {}
            receiverOn = false;
        }
        if (store != null) {
            store.removeListener(storeListener);
            store.saveStates();
        }
        if (overlay != null) overlay.removeAll();
        running = null;
    }

    /** Runs a scan after {@code delayMs}, but never later than one already planned. */
    private void scheduleScan(long delayMs) {
        long at = SystemClock.elapsedRealtime() + Math.max(0, delayMs);
        if (scanPending && scanDueAt <= at) return;
        handler.removeCallbacks(scanRunnable);
        handler.postDelayed(scanRunnable, Math.max(0, delayMs));
        scanPending = true;
        scanDueAt = at;
    }

    private void scan() {
        scanPending = false;
        if (running != this) return;
        long now = SystemClock.elapsedRealtime();
        account(now); // time since last check belongs to the previous set of apps

        userActive = isUserActive();
        windowNotReady = false;
        Set<String> nowVisible = userActive ? visibleWatchedApps() : Collections.<String>emptySet();

        long today = Store.today();
        if (today != sessionDay) { // midnight: counters reset, start fresh sessions
            sessionDay = today;
            sessions.clear();
        }
        for (String p : visible) {
            if (!nowVisible.contains(p)) {
                lastSeen.put(p, now);
                overlay.clear(p);
            }
        }
        for (String p : nowVisible) {
            if (!visible.contains(p)) {
                Long seen = lastSeen.get(p);
                boolean longGone = seen == null || now - seen > Engine.SESSION_GAP_MS;
                if (longGone || !sessions.containsKey(p)) sessions.put(p, new Engine.Session());
            }
        }
        visible.clear();
        visible.addAll(nowVisible);

        enforce();
        scheduleNextCheck();
        if (windowNotReady && notReadyRetries < 10) {
            notReadyRetries++;
            scheduleScan(400);
        } else if (!windowNotReady) {
            notReadyRetries = 0;
        }
    }

    /** Books on-screen time for every watched app that was visible. */
    private void account(long now) {
        long delta = now - lastAccount;
        lastAccount = now;
        if (delta <= 0 || visible.isEmpty() || store == null) return;
        delta = Math.min(delta, 5 * Engine.MIN); // safety cap
        for (String p : visible) {
            Engine.Rule r = store.rule(p);
            if (r == null) continue;
            Engine.addUsage(r, store.state(p), session(p), delta);
        }
        store.saveStates();
    }

    private void enforce() {
        for (String p : new ArrayList<>(visible)) {
            Engine.Rule r = store.rule(p);
            if (r == null) continue;
            Engine.State s = store.state(p);
            Engine.Session ss = session(p);
            String label = Fmt.label(this, r);

            if (Engine.isBlocked(r, s)) {
                block(p);
                continue;
            }
            if (Engine.popupDue(r, s, ss)) {
                Engine.markPopupShown(ss);
                overlay.show(p, label, s.usedMs, r.warnMin, ss.popups);
            }
            if (Engine.inUnlock(r, s) && s.unlockLeftMs <= Engine.UNLOCK_ENDING_WARN_MS
                    && ss.unlockWarnedFor != s.unlocks) {
                ss.unlockWarnedFor = s.unlocks;
                toast(label + ": " + Fmt.clock(s.unlockLeftMs) + " of unlock left");
            }
            if (r.mode == Engine.MODE_LIMIT && !ss.limitWarned) {
                long left = r.limitMin * Engine.MIN - s.usedMs;
                if (left > 0 && left <= Engine.LIMIT_ENDING_WARN_MS) {
                    ss.limitWarned = true;
                    toast(label + ": about 1 minute left today");
                }
            }
        }
    }

    /** Sends the user home and opens the block screen for {@code pkg}. */
    private void block(final String pkg) {
        long now = SystemClock.elapsedRealtime();
        if (pkg.equals(lastBlockedPkg) && now - lastBlockAt < 1500) return; // already on it
        lastBlockedPkg = pkg;
        lastBlockAt = now;
        overlay.clear(pkg);
        performGlobalAction(GLOBAL_ACTION_HOME);
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                Intent i = new Intent(BlockerService.this, BlockActivity.class);
                i.putExtra(BlockActivity.EXTRA_PKG, pkg);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(i);
            }
        }, 250);
    }

    /** Plans the next check: the earliest moment something can change. */
    private void scheduleNextCheck() {
        if (visible.isEmpty()) return; // window events will wake us up
        long next = 15_000;
        for (String p : visible) {
            Engine.Rule r = store.rule(p);
            if (r == null) continue;
            Engine.State s = store.state(p);
            if (Engine.isBlocked(r, s)) {
                next = Math.min(next, 700); // still on screen after being sent home: try again
                continue;
            }
            next = Math.min(next, Engine.msUntilBlocked(r, s));
            next = Math.min(next, Engine.msUntilNextPopup(r, s, session(p)));
            if (Engine.inUnlock(r, s) && s.unlockLeftMs > Engine.UNLOCK_ENDING_WARN_MS) {
                next = Math.min(next, s.unlockLeftMs - Engine.UNLOCK_ENDING_WARN_MS);
            }
            if (r.mode == Engine.MODE_LIMIT) {
                long left = r.limitMin * Engine.MIN - s.usedMs;
                if (left > Engine.LIMIT_ENDING_WARN_MS) {
                    next = Math.min(next, left - Engine.LIMIT_ENDING_WARN_MS);
                }
            }
        }
        scheduleScan(Math.max(250, next));
    }

    /** Package names of watched apps that have a normal app window on screen. */
    private Set<String> visibleWatchedApps() {
        HashSet<String> out = new HashSet<>();
        List<AccessibilityWindowInfo> windows;
        try {
            windows = getWindows();
        } catch (RuntimeException e) {
            windows = Collections.emptyList();
        }
        boolean sawAppWindow = false;
        windowNotReady = false;
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            if (w.isInPictureInPictureMode()) continue; // can't be closed by going home
            sawAppWindow = true;
            if (!addIfWatched(out, w.getRoot())) windowNotReady = true;
        }
        if (!sawAppWindow && !addIfWatched(out, getRootInActiveWindow())) windowNotReady = true;
        return out;
    }

    /** Returns false if there was no root to look at. */
    @SuppressWarnings("deprecation")
    private boolean addIfWatched(Set<String> out, AccessibilityNodeInfo root) {
        if (root == null) return false;
        CharSequence pkg = root.getPackageName();
        if (pkg != null && store.rule(pkg.toString()) != null) out.add(pkg.toString());
        if (Build.VERSION.SDK_INT < 33) root.recycle();
        return true;
    }

    private boolean isUserActive() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        boolean on = pm == null || pm.isInteractive();
        boolean locked = km != null && km.isKeyguardLocked();
        return on && !locked;
    }

    private Engine.Session session(String pkg) {
        Engine.Session ss = sessions.get(pkg);
        if (ss == null) {
            ss = new Engine.Session();
            sessions.put(pkg, ss);
        }
        return ss;
    }

    private void toast(String text) {
        Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG).show();
    }

    /** Called by the Warn popup's "Close app" button. */
    void goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME);
    }
}
