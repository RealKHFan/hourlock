package app.unscroll;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Keeps the rules and today's counters, saved in the app's private storage.
 * Used from the main thread only (service and screens live in one process).
 */
final class Store {
    /** Suggested on first start, if installed. Added as "Not restricted". */
    static final String[] SUGGESTED = {
            "com.instagram.android",     // Instagram
            "com.zhiliaoapp.musically",  // TikTok
            "com.ss.android.ugc.trill",  // TikTok (other regions)
            "com.discord",               // Discord
    };

    private static Store instance;

    static synchronized Store get(Context c) {
        if (instance == null) instance = new Store(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final LinkedHashMap<String, Engine.Rule> rules = new LinkedHashMap<>();
    private final HashMap<String, Engine.State> states = new HashMap<>();
    private final ArrayList<Runnable> listeners = new ArrayList<>();

    private Store(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("unscroll", Context.MODE_PRIVATE);
        load();
        seedOnFirstRun();
    }

    static long today() {
        return LocalDate.now().toEpochDay();
    }

    // ---- reading ----

    List<Engine.Rule> rules() {
        return new ArrayList<>(rules.values());
    }

    Engine.Rule rule(String pkg) {
        return pkg == null ? null : rules.get(pkg);
    }

    /** Today's counters for an app (reset automatically after midnight). */
    Engine.State state(String pkg) {
        Engine.State s = states.get(pkg);
        if (s == null) {
            s = new Engine.State();
            s.day = today();
            states.put(pkg, s);
        }
        Engine.rollover(s, today());
        return s;
    }

    // ---- changing ----

    /** Adds or replaces a rule. The caller decides whether a wait was needed. */
    void putRule(Engine.Rule r) {
        rules.put(r.pkg, r.copy());
        saveRules();
        changed();
    }

    void removeRule(String pkg) {
        rules.remove(pkg);
        states.remove(pkg);
        saveRules();
        saveStates();
        changed();
    }

    /** Unlocks a blocked app. Returns false if it wasn't blocked. */
    boolean grantUnlock(String pkg) {
        Engine.Rule r = rule(pkg);
        if (r == null) return false;
        boolean ok = Engine.grantUnlock(r, state(pkg));
        if (ok) {
            saveStates();
            changed();
        }
        return ok;
    }

    void addListener(Runnable l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Runnable l) {
        listeners.remove(l);
    }

    private void changed() {
        for (Runnable l : new ArrayList<>(listeners)) l.run();
    }

    // ---- saving / loading ----

    void saveRules() {
        JSONArray a = new JSONArray();
        try {
            for (Engine.Rule r : rules.values()) {
                JSONObject o = new JSONObject();
                o.put("pkg", r.pkg);
                o.put("label", r.label);
                o.put("mode", r.mode);
                o.put("limit", r.limitMin);
                o.put("warn", r.warnMin);
                o.put("unlock", r.unlockMin);
                o.put("wait", r.unlockWaitSec);
                a.put(o);
            }
        } catch (JSONException e) {
            return; // cannot happen with these value types
        }
        prefs.edit().putString("rules", a.toString()).apply();
    }

    void saveStates() {
        JSONObject all = new JSONObject();
        try {
            for (String pkg : states.keySet()) {
                Engine.State s = states.get(pkg);
                JSONObject o = new JSONObject();
                o.put("day", s.day);
                o.put("used", s.usedMs);
                o.put("left", s.unlockLeftMs);
                o.put("unlocks", s.unlocks);
                all.put(pkg, o);
            }
        } catch (JSONException e) {
            return;
        }
        prefs.edit().putString("states", all.toString()).apply();
    }

    private void load() {
        try {
            JSONArray a = new JSONArray(prefs.getString("rules", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Engine.Rule r = new Engine.Rule(o.getString("pkg"), o.optString("label", o.getString("pkg")));
                r.mode = clamp(o.optInt("mode", Engine.MODE_OFF), Engine.MODE_OFF, Engine.MODE_BLOCK);
                r.limitMin = clamp(o.optInt("limit", Engine.DEFAULT_LIMIT_MIN), 1, 1440);
                r.warnMin = clamp(o.optInt("warn", Engine.DEFAULT_WARN_MIN), 1, 1440);
                r.unlockMin = clamp(o.optInt("unlock", Engine.DEFAULT_UNLOCK_MIN), 1, 60);
                r.unlockWaitSec = clamp(o.optInt("wait", Engine.DEFAULT_UNLOCK_WAIT_SEC),
                        Engine.MIN_UNLOCK_WAIT_SEC, Engine.MAX_UNLOCK_WAIT_SEC);
                rules.put(r.pkg, r);
            }
        } catch (JSONException e) {
            rules.clear();
        }
        try {
            JSONObject all = new JSONObject(prefs.getString("states", "{}"));
            Iterator<String> keys = all.keys();
            while (keys.hasNext()) {
                String pkg = keys.next();
                JSONObject o = all.getJSONObject(pkg);
                Engine.State s = new Engine.State();
                s.day = o.optLong("day", today());
                s.usedMs = Math.max(0, o.optLong("used", 0));
                s.unlockLeftMs = Math.max(0, o.optLong("left", 0));
                s.unlocks = Math.max(0, o.optInt("unlocks", 0));
                states.put(pkg, s);
            }
        } catch (JSONException e) {
            states.clear();
        }
    }

    private void seedOnFirstRun() {
        if (prefs.getBoolean("seeded", false)) return;
        PackageManager pm = ctx.getPackageManager();
        for (String pkg : SUGGESTED) {
            try {
                ApplicationInfo ai = Fmt.appInfo(pm, pkg);
                if (!rules.containsKey(pkg)) {
                    rules.put(pkg, new Engine.Rule(pkg, pm.getApplicationLabel(ai).toString()));
                }
            } catch (PackageManager.NameNotFoundException notInstalled) {
                // skip
            }
        }
        saveRules();
        prefs.edit().putBoolean("seeded", true).apply();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
