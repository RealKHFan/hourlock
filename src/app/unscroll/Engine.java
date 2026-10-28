package app.unscroll;

/**
 * All the rules logic of Hourlock, in plain Java with no Android imports,
 * so it can be unit-tested on a normal computer.
 *
 * Modes, from loosest to strictest:
 *   OFF   - only counts minutes
 *   WARN  - never blocks; after the daily threshold a popup appears, and
 *           another one every 2 minutes you stay in that session
 *   LIMIT - free use until the daily limit, then blocked
 *   BLOCK - always blocked
 *
 * A blocked app can only be unlocked from inside Hourlock, after a wait you
 * choose per app (15 s by default). An unlock gives a few minutes of IN-APP
 * time: it only runs down while the app is on screen.
 * Leftover unlock time, usage and unlock counts reset at midnight.
 */
public final class Engine {
    private Engine() {}

    // The numbers are also the strictness rank, so "a < b" means "a is looser".
    public static final int MODE_OFF = 0;
    public static final int MODE_WARN = 1;
    public static final int MODE_LIMIT = 2;
    public static final int MODE_BLOCK = 3;

    public static final long MIN = 60_000L;

    /** Wait before a looser rule is applied. */
    public static final long LOOSEN_WAIT_MS = 180_000L;
    /** In Warn mode: one more popup every 2 minutes once over the threshold. */
    public static final long WARN_REPEAT_MS = 2 * MIN;
    /** Leaving an app for longer than this starts a new session. */
    public static final long SESSION_GAP_MS = 60_000L;
    /** More unlocks than this in one day turn the app red. */
    public static final int RED_AFTER_UNLOCKS = 2;
    /** Heads-up toast before an unlock runs out. */
    public static final long UNLOCK_ENDING_WARN_MS = 30_000L;
    /** Heads-up toast before a daily limit runs out. */
    public static final long LIMIT_ENDING_WARN_MS = 60_000L;

    public static final int DEFAULT_LIMIT_MIN = 30;
    public static final int DEFAULT_WARN_MIN = 30;
    public static final int DEFAULT_UNLOCK_MIN = 5;
    /** Default wait on the unlock screen before "Unlock" works. */
    public static final int DEFAULT_UNLOCK_WAIT_SEC = 15;
    public static final int MIN_UNLOCK_WAIT_SEC = 5;
    public static final int MAX_UNLOCK_WAIT_SEC = 3600;

    /** What the user configured for one app. */
    public static final class Rule {
        public String pkg;
        public String label;
        public int mode = MODE_OFF;
        public int limitMin = DEFAULT_LIMIT_MIN;
        public int warnMin = DEFAULT_WARN_MIN;
        public int unlockMin = DEFAULT_UNLOCK_MIN;
        /** Seconds you must wait on the unlock screen before "Unlock" works. */
        public int unlockWaitSec = DEFAULT_UNLOCK_WAIT_SEC;

        public Rule() {}

        public Rule(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }

        public Rule copy() {
            Rule r = new Rule(pkg, label);
            r.mode = mode;
            r.limitMin = limitMin;
            r.warnMin = warnMin;
            r.unlockMin = unlockMin;
            r.unlockWaitSec = unlockWaitSec;
            return r;
        }

        long limitMs() { return limitMin * MIN; }
        long warnMs() { return warnMin * MIN; }
        long unlockMs() { return unlockMin * MIN; }
        public long unlockWaitMs() { return unlockWaitSec * 1000L; }
    }

    /** Today's counters for one app. Saved to disk. */
    public static final class State {
        /** The day (days since 1970) these counters belong to. */
        public long day;
        /** Time the app was on screen today. */
        public long usedMs;
        /** Unlocked in-app time that is still left. */
        public long unlockLeftMs;
        /** Unlocks used today. */
        public int unlocks;
    }

    /** One continuous visit to an app. Only kept in memory. */
    public static final class Session {
        /** Time spent in this session while over the Warn threshold. */
        public long overMs;
        /** Warn popups shown in this session. */
        public int popups;
        /** Which unlock (by count) already got its "30 s left" toast. */
        public int unlockWarnedFor = -1;
        /** Whether the "1 min left today" toast was shown. */
        public boolean limitWarned;
    }

    /** Resets the counters when a new day has started. */
    public static void rollover(State s, long today) {
        if (s.day != today) {
            s.day = today;
            s.usedMs = 0;
            s.unlockLeftMs = 0;
            s.unlocks = 0;
        }
    }

    /** True if the app must not be on screen right now. */
    public static boolean isBlocked(Rule r, State s) {
        switch (r.mode) {
            case MODE_BLOCK:
                return s.unlockLeftMs <= 0;
            case MODE_LIMIT:
                return s.usedMs >= r.limitMs() && s.unlockLeftMs <= 0;
            default:
                return false;
        }
    }

    /** True if the app is currently running on unlock time. */
    public static boolean inUnlock(Rule r, State s) {
        if (s.unlockLeftMs <= 0) return false;
        if (r.mode == MODE_BLOCK) return true;
        return r.mode == MODE_LIMIT && s.usedMs >= r.limitMs();
    }

    /** How much more on-screen time is allowed before the app gets blocked. */
    public static long msUntilBlocked(Rule r, State s) {
        switch (r.mode) {
            case MODE_BLOCK:
                return Math.max(0, s.unlockLeftMs);
            case MODE_LIMIT:
                return Math.max(0, r.limitMs() - s.usedMs) + Math.max(0, s.unlockLeftMs);
            default:
                return Long.MAX_VALUE;
        }
    }

    /**
     * Books {@code delta} ms of on-screen time. In Limit mode the free daily
     * allowance is used first and only then the unlock time.
     */
    public static void addUsage(Rule r, State s, Session ss, long delta) {
        if (delta <= 0) return;
        long before = s.usedMs;
        if (r.mode == MODE_BLOCK) {
            s.unlockLeftMs = Math.max(0, s.unlockLeftMs - delta);
        } else if (r.mode == MODE_LIMIT) {
            long free = Math.max(0, r.limitMs() - before);
            long fromUnlock = Math.max(0, delta - free);
            if (fromUnlock > 0) s.unlockLeftMs = Math.max(0, s.unlockLeftMs - fromUnlock);
        }
        s.usedMs = before + delta;
        if (ss != null && r.mode == MODE_WARN) {
            long threshold = r.warnMs();
            if (s.usedMs > threshold) ss.overMs += s.usedMs - Math.max(before, threshold);
        }
    }

    /**
     * Gives one unlock. Only possible while the app is blocked.
     * Returns false if there was nothing to unlock.
     */
    public static boolean grantUnlock(Rule r, State s) {
        if (!isBlocked(r, s)) return false;
        s.unlockLeftMs = r.unlockMs();
        s.unlocks++;
        return true;
    }

    /** More than two unlocks today: the app is shown in red. */
    public static boolean isRed(State s) {
        return s.unlocks > RED_AFTER_UNLOCKS;
    }

    /** True if the next unlock would be the one that turns the app red. */
    public static boolean nextUnlockTurnsRed(State s) {
        return s.unlocks == RED_AFTER_UNLOCKS;
    }

    /** Warn mode: should a new popup appear now? */
    public static boolean popupDue(Rule r, State s, Session ss) {
        if (r.mode != MODE_WARN || ss == null) return false;
        if (s.usedMs < r.warnMs()) return false;
        return ss.overMs >= ss.popups * WARN_REPEAT_MS;
    }

    /**
     * Call after showing a popup. Uses the elapsed time rather than +1 so a
     * late check never fires several popups at once.
     */
    public static void markPopupShown(Session ss) {
        ss.popups = (int) (ss.overMs / WARN_REPEAT_MS) + 1;
    }

    /** On-screen time until the next Warn popup is due. */
    public static long msUntilNextPopup(Rule r, State s, Session ss) {
        if (r.mode != MODE_WARN || ss == null) return Long.MAX_VALUE;
        if (s.usedMs < r.warnMs()) return r.warnMs() - s.usedMs;
        return Math.max(0, ss.popups * WARN_REPEAT_MS - ss.overMs);
    }

    /**
     * True if changing {@code old} into {@code neu} makes things looser and
     * therefore needs the 3-minute wait. {@code neu == null} means "remove the
     * app from the list".
     */
    public static boolean isLoosening(Rule old, Rule neu) {
        if (old == null) return false;                       // adding is never looser
        if (neu == null) return old.mode != MODE_OFF;        // removing a restriction
        if (neu.mode < old.mode) return true;                // less strict mode
        if (neu.mode == old.mode) {
            if (neu.mode == MODE_LIMIT && neu.limitMin > old.limitMin) return true;
            if (neu.mode == MODE_WARN && neu.warnMin > old.warnMin) return true;
        }
        // Warn 30 -> Limit 1440 would give more free minutes and drop the popups.
        if (old.mode == MODE_WARN && neu.mode == MODE_LIMIT && neu.limitMin > old.warnMin) return true;
        boolean oldHadUnlocks = old.mode == MODE_LIMIT || old.mode == MODE_BLOCK;
        boolean newHasUnlocks = neu.mode == MODE_LIMIT || neu.mode == MODE_BLOCK;
        if (!oldHadUnlocks || !newHasUnlocks) return false;
        return neu.unlockMin > old.unlockMin            // longer unlocks
                || neu.unlockWaitSec < old.unlockWaitSec; // shorter wait before unlocking
    }
}
