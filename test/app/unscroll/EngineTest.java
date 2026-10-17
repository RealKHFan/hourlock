package app.unscroll;

import static app.unscroll.Engine.*;

/** Plain-Java tests for Engine. Run: java app.unscroll.EngineTest */
public final class EngineTest {
    private static int passed, failed;

    private static void check(boolean ok, String name) {
        if (ok) { passed++; } else { failed++; System.out.println("FAIL: " + name); }
    }

    private static void eq(long expected, long actual, String name) {
        check(expected == actual, name + " (expected " + expected + ", got " + actual + ")");
    }

    private static Rule rule(int mode) {
        Rule r = new Rule("com.example", "Example");
        r.mode = mode;
        return r;
    }

    private static State fresh() {
        State s = new State();
        s.day = 100;
        return s;
    }

    public static void main(String[] args) {
        alwaysBlocked();
        unlockCountsOnlyInAppTime();
        dailyLimit();
        limitStraddle();
        midnightReset();
        redAfterTwoUnlocks();
        warnPopups();
        warnSessionStartsOverThreshold();
        warnNoBurstAfterLateCheck();
        warnNeverBlocks();
        offNeverBlocks();
        loosening();
        noUnlockWhenNotBlocked();
        unlockWaitDefaults();

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void alwaysBlocked() {
        Rule r = rule(MODE_BLOCK);
        State s = fresh();
        check(isBlocked(r, s), "block: blocked at start");
        check(grantUnlock(r, s), "block: unlock granted");
        check(!isBlocked(r, s), "block: open after unlock");
        eq(5 * MIN, s.unlockLeftMs, "block: 5 min unlock");
        eq(1, s.unlocks, "block: one unlock counted");
        addUsage(r, s, new Session(), 4 * MIN);
        check(!isBlocked(r, s), "block: still open after 4 min");
        eq(1 * MIN, msUntilBlocked(r, s), "block: 1 min left");
        addUsage(r, s, new Session(), 1 * MIN);
        check(isBlocked(r, s), "block: blocked again after 5 min");
    }

    static void unlockCountsOnlyInAppTime() {
        Rule r = rule(MODE_BLOCK);
        State s = fresh();
        grantUnlock(r, s);
        addUsage(r, s, null, 2 * MIN);
        // ...user leaves the app for an hour: no addUsage calls happen...
        eq(3 * MIN, s.unlockLeftMs, "unlock: leftover kept while away");
        check(inUnlock(r, s), "unlock: still in unlock");
    }

    static void dailyLimit() {
        Rule r = rule(MODE_LIMIT);
        r.limitMin = 30;
        State s = fresh();
        addUsage(r, s, null, 29 * MIN);
        check(!isBlocked(r, s), "limit: open at 29 of 30");
        eq(1 * MIN, msUntilBlocked(r, s), "limit: 1 min left");
        check(!inUnlock(r, s), "limit: not on unlock time yet");
        addUsage(r, s, null, 1 * MIN);
        check(isBlocked(r, s), "limit: blocked at 30 of 30");
        check(grantUnlock(r, s), "limit: unlock after limit");
        check(inUnlock(r, s), "limit: running on unlock time");
        eq(5 * MIN, msUntilBlocked(r, s), "limit: 5 min of unlock");
        addUsage(r, s, null, 5 * MIN);
        check(isBlocked(r, s), "limit: blocked again after unlock");
        eq(35 * MIN, s.usedMs, "limit: usage keeps counting");
    }

    static void limitStraddle() {
        // One accounting step that crosses the limit only eats the part past it from the unlock.
        Rule r = rule(MODE_LIMIT);
        r.limitMin = 30;
        State s = fresh();
        s.usedMs = 31 * MIN;
        grantUnlock(r, s);
        addUsage(r, s, null, 2 * MIN);
        eq(3 * MIN, s.unlockLeftMs, "straddle: unlock reduced by in-app time");

        State t = fresh();
        t.usedMs = 29 * MIN;
        t.unlockLeftMs = 5 * MIN; // e.g. limit was raised after an unlock
        addUsage(r, t, null, 3 * MIN);
        eq(3 * MIN, t.unlockLeftMs, "straddle: only 2 min past limit came from unlock");
    }

    static void midnightReset() {
        State s = fresh();
        s.usedMs = 40 * MIN;
        s.unlocks = 3;
        s.unlockLeftMs = 2 * MIN;
        rollover(s, 100);
        eq(40 * MIN, s.usedMs, "rollover: same day keeps usage");
        rollover(s, 101);
        eq(0, s.usedMs, "rollover: usage reset");
        eq(0, s.unlocks, "rollover: unlocks reset");
        eq(0, s.unlockLeftMs, "rollover: leftover unlock expires");
        eq(101, s.day, "rollover: new day stored");
    }

    static void redAfterTwoUnlocks() {
        Rule r = rule(MODE_BLOCK);
        State s = fresh();
        for (int i = 1; i <= 3; i++) {
            check(isBlocked(r, s), "red: blocked before unlock " + i);
            if (i == 3) check(nextUnlockTurnsRed(s), "red: warned before 3rd unlock");
            else check(!nextUnlockTurnsRed(s), "red: no warning before unlock " + i);
            grantUnlock(r, s);
            check(isRed(s) == (i >= 3), "red: after unlock " + i);
            addUsage(r, s, null, 5 * MIN);
        }
        eq(15 * MIN, s.usedMs, "red: three unlocks = 15 min extra");
    }

    static void warnPopups() {
        Rule r = rule(MODE_WARN);
        r.warnMin = 30;
        State s = fresh();
        Session ss = new Session();
        addUsage(r, s, ss, 29 * MIN);
        check(!popupDue(r, s, ss), "warn: no popup under 30 min");
        eq(1 * MIN, msUntilNextPopup(r, s, ss), "warn: first popup in 1 min");
        addUsage(r, s, ss, 1 * MIN);
        check(popupDue(r, s, ss), "warn: popup #1 at 30 min");
        markPopupShown(ss);
        eq(1, ss.popups, "warn: one popup shown");
        addUsage(r, s, ss, 1 * MIN);
        check(!popupDue(r, s, ss), "warn: nothing 1 min later");
        eq(1 * MIN, msUntilNextPopup(r, s, ss), "warn: next in 1 min");
        addUsage(r, s, ss, 1 * MIN);
        check(popupDue(r, s, ss), "warn: popup #2 after 2 more min");
        markPopupShown(ss);
        addUsage(r, s, ss, 2 * MIN);
        check(popupDue(r, s, ss), "warn: popup #3 after 2 more min");
        markPopupShown(ss);
        eq(3, ss.popups, "warn: three popups stacked");
    }

    static void warnSessionStartsOverThreshold() {
        Rule r = rule(MODE_WARN);
        State s = fresh();
        s.usedMs = 45 * MIN; // already over today, user opens the app again
        Session ss = new Session();
        check(popupDue(r, s, ss), "warn: popup right away in a new session when already over");
    }

    static void warnNoBurstAfterLateCheck() {
        Rule r = rule(MODE_WARN);
        State s = fresh();
        Session ss = new Session();
        addUsage(r, s, ss, 30 * MIN);
        markPopupShown(ss);
        addUsage(r, s, ss, 7 * MIN); // a check came very late
        check(popupDue(r, s, ss), "warn: late check shows a popup");
        markPopupShown(ss);
        check(!popupDue(r, s, ss), "warn: but only one, not a burst");
        eq(1 * MIN, msUntilNextPopup(r, s, ss), "warn: next stays on the 2-min grid");
    }

    static void warnNeverBlocks() {
        Rule r = rule(MODE_WARN);
        State s = fresh();
        addUsage(r, s, new Session(), 500 * MIN);
        check(!isBlocked(r, s), "warn: never blocks");
        check(!grantUnlock(r, s), "warn: no unlock needed");
        eq(Long.MAX_VALUE, msUntilBlocked(r, s), "warn: no block time");
    }

    static void offNeverBlocks() {
        Rule r = rule(MODE_OFF);
        State s = fresh();
        Session ss = new Session();
        addUsage(r, s, ss, 500 * MIN);
        check(!isBlocked(r, s), "off: never blocks");
        check(!popupDue(r, s, ss), "off: never warns");
        eq(500 * MIN, s.usedMs, "off: still counts minutes");
    }

    static void loosening() {
        int[] modes = {MODE_OFF, MODE_WARN, MODE_LIMIT, MODE_BLOCK};
        for (int a : modes) {
            for (int b : modes) {
                boolean expected = b < a;
                check(isLoosening(rule(a), rule(b)) == expected, "loosen: mode " + a + " -> " + b);
            }
        }
        Rule lim = rule(MODE_LIMIT);
        lim.limitMin = 30;
        Rule lim20 = lim.copy(); lim20.limitMin = 20;
        Rule lim45 = lim.copy(); lim45.limitMin = 45;
        check(!isLoosening(lim, lim20), "loosen: lower limit is instant");
        check(isLoosening(lim, lim45), "loosen: higher limit waits");

        Rule warn = rule(MODE_WARN);
        Rule warn60 = warn.copy(); warn60.warnMin = 60;
        Rule warn15 = warn.copy(); warn15.warnMin = 15;
        check(isLoosening(warn, warn60), "loosen: later warning waits");
        check(!isLoosening(warn, warn15), "loosen: earlier warning is instant");

        Rule blk = rule(MODE_BLOCK);
        Rule blk10 = blk.copy(); blk10.unlockMin = 10;
        Rule blk3 = blk.copy(); blk3.unlockMin = 3;
        check(isLoosening(blk, blk10), "loosen: longer unlock waits");
        check(!isLoosening(blk, blk3), "loosen: shorter unlock is instant");

        Rule blkWait60 = blk.copy(); blkWait60.unlockWaitSec = 60;
        Rule blkWait5 = blk.copy(); blkWait5.unlockWaitSec = 5;
        check(!isLoosening(blk, blkWait60), "loosen: longer unlock wait is instant");
        check(isLoosening(blk, blkWait5), "loosen: shorter unlock wait waits");
        check(isLoosening(blkWait60, blk), "loosen: 60 s -> 15 s wait waits");
        Rule limWait5 = lim.copy(); limWait5.unlockWaitSec = 5;
        check(isLoosening(lim, limWait5), "loosen: shorter wait on a limit waits");

        Rule off = rule(MODE_OFF);
        Rule blkFromOff = rule(MODE_BLOCK); blkFromOff.unlockMin = 10;
        check(!isLoosening(off, blkFromOff), "loosen: off -> blocked is instant even with long unlock");
        Rule blkFastFromOff = rule(MODE_BLOCK); blkFastFromOff.unlockWaitSec = 5;
        check(!isLoosening(off, blkFastFromOff), "loosen: off -> blocked is instant even with short wait");

        Rule warnBig = rule(MODE_WARN); warnBig.warnMin = 30;
        Rule limBig = rule(MODE_LIMIT); limBig.limitMin = 120;
        check(!isLoosening(warnBig, limBig), "loosen: warn -> limit is stricter");

        check(isLoosening(lim, null), "loosen: removing a limited app waits");
        check(!isLoosening(off, null), "loosen: removing an unrestricted app is instant");
        check(!isLoosening(null, blk), "loosen: adding is instant");
        check(!isLoosening(lim, lim.copy()), "loosen: no change is instant");
    }

    static void unlockWaitDefaults() {
        Rule r = rule(MODE_BLOCK);
        eq(15, r.unlockWaitSec, "wait: default 15 s");
        eq(15_000, r.unlockWaitMs(), "wait: in ms");
        r.unlockWaitSec = 300;
        eq(300, r.copy().unlockWaitSec, "wait: kept by copy");
    }

    static void noUnlockWhenNotBlocked() {
        Rule r = rule(MODE_LIMIT);
        State s = fresh();
        s.usedMs = 10 * MIN;
        check(!grantUnlock(r, s), "unlock: refused while still within limit");
        eq(0, s.unlocks, "unlock: nothing counted");
    }
}
