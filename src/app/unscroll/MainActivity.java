package app.unscroll;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The main screen: status, your apps and their rules, adding apps. */
public class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Store store;

    private TextView summary, statusTitle, statusBody, empty;
    private View statusCard, statusButtons;
    private LinearLayout list;
    private final ArrayList<String> shownOrder = new ArrayList<>();
    private final HashMap<String, Row> rows = new HashMap<>();
    private AlertDialog waitDialog;

    /** Views of one app row. */
    private static final class Row {
        View root;
        ImageView icon;
        TextView name, mode, detail, redNote;
        ProgressBar bar;
        Button unlock;
    }

    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    private final Runnable storeListener = new Runnable() {
        @Override public void run() { refresh(); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        store = Store.get(this);
        summary = findViewById(R.id.summary);
        statusCard = findViewById(R.id.status_card);
        statusTitle = findViewById(R.id.status_title);
        statusBody = findViewById(R.id.status_body);
        statusButtons = findViewById(R.id.status_buttons);
        list = findViewById(R.id.app_list);
        empty = findViewById(R.id.empty);

        findViewById(R.id.btn_a11y).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        findViewById(R.id.btn_info).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        });
        findViewById(R.id.btn_add).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPicker(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        store.addListener(storeListener);
        handler.removeCallbacks(refresher);
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        store.removeListener(storeListener);
        handler.removeCallbacks(refresher);
        if (waitDialog != null) {
            waitDialog.dismiss(); // leaving the screen restarts the 3-minute wait
            toast("Wait cancelled because you left Hourlock");
        }
    }

    // ------------------------------------------------------------------ list

    private void refresh() {
        List<Engine.Rule> rules = store.rules();
        ArrayList<String> order = new ArrayList<>();
        for (Engine.Rule r : rules) order.add(r.pkg);
        if (!order.equals(shownOrder)) rebuildRows(rules);

        long total = 0;
        for (Engine.Rule r : rules) {
            total += store.state(r.pkg).usedMs;
            Row row = rows.get(r.pkg);
            if (row != null) bindRow(row, r);
        }
        summary.setText("Today in your watched apps: " + Fmt.mins(total));
        empty.setVisibility(rules.isEmpty() ? View.VISIBLE : View.GONE);
        refreshStatus(rules.size());
    }

    private void rebuildRows(List<Engine.Rule> rules) {
        list.removeAllViews();
        rows.clear();
        shownOrder.clear();
        for (final Engine.Rule r : rules) {
            Row row = new Row();
            row.root = getLayoutInflater().inflate(R.layout.item_app, list, false);
            row.icon = row.root.findViewById(R.id.icon);
            row.name = row.root.findViewById(R.id.name);
            row.mode = row.root.findViewById(R.id.mode);
            row.detail = row.root.findViewById(R.id.detail);
            row.redNote = row.root.findViewById(R.id.red_note);
            row.bar = row.root.findViewById(R.id.bar);
            row.unlock = row.root.findViewById(R.id.unlock);
            row.icon.setImageDrawable(Fmt.icon(this, r.pkg));
            final String pkg = r.pkg;
            row.root.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Engine.Rule now = store.rule(pkg);
                    if (now != null) showEdit(now, false);
                }
            });
            row.unlock.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Intent i = new Intent(MainActivity.this, BlockActivity.class);
                    i.putExtra(BlockActivity.EXTRA_PKG, pkg);
                    i.putExtra(BlockActivity.EXTRA_FROM_APP, true);
                    startActivity(i);
                }
            });
            list.addView(row.root);
            rows.put(r.pkg, row);
            shownOrder.add(r.pkg);
        }
    }

    private void bindRow(Row row, Engine.Rule r) {
        Engine.State s = store.state(r.pkg);
        boolean installed = Fmt.installed(this, r.pkg);
        boolean blocked = Engine.isBlocked(r, s);
        boolean unlocked = Engine.inUnlock(r, s);
        boolean red = Engine.isRed(s);
        String used = Fmt.mins(s.usedMs);

        row.name.setText(installed ? Fmt.label(this, r) : r.label + " (not installed)");
        row.mode.setText(Fmt.modeName(r));

        String detail;
        int barColor = getColor(R.color.accent);
        int barMax = 0;
        switch (r.mode) {
            case Engine.MODE_WARN:
                barMax = r.warnMin;
                if (s.usedMs >= r.warnMin * Engine.MIN) {
                    detail = "Today " + used + " · over by " + Fmt.mins(s.usedMs - r.warnMin * Engine.MIN);
                    barColor = getColor(R.color.amber);
                } else {
                    detail = "Today " + used + " of " + r.warnMin + " min";
                }
                break;
            case Engine.MODE_LIMIT:
                barMax = r.limitMin;
                if (unlocked) {
                    detail = "Unlocked · " + Fmt.clock(s.unlockLeftMs) + " of in-app time left";
                    barColor = getColor(R.color.amber);
                } else if (blocked) {
                    detail = "Limit reached · " + used + " today";
                    barColor = getColor(R.color.red);
                } else {
                    detail = used + " of " + r.limitMin + " min · "
                            + Fmt.mins(r.limitMin * Engine.MIN - s.usedMs) + " left";
                }
                break;
            case Engine.MODE_BLOCK:
                detail = unlocked
                        ? "Unlocked · " + Fmt.clock(s.unlockLeftMs) + " of in-app time left"
                        : "Blocked" + (s.unlocks > 0 ? " · " + used + " today via unlocks" : "");
                break;
            default:
                detail = "Today " + used;
        }
        if (s.unlocks > 0 && !red) {
            detail += " · " + s.unlocks + (s.unlocks == 1 ? " unlock" : " unlocks");
        }
        row.detail.setText(detail);

        if (barMax > 0) {
            row.bar.setVisibility(View.VISIBLE);
            row.bar.setMax(1000);
            row.bar.setProgress((int) Math.min(1000, s.usedMs * 1000 / (barMax * Engine.MIN)));
            row.bar.setProgressTintList(ColorStateList.valueOf(red ? getColor(R.color.red) : barColor));
        } else {
            row.bar.setVisibility(View.GONE);
        }

        // More than two unlocks today: the whole row turns red.
        row.root.setBackgroundResource(red ? R.drawable.card_red : R.drawable.card);
        row.name.setTextColor(getColor(red ? R.color.red : R.color.text));
        if (red) {
            row.redNote.setVisibility(View.VISIBLE);
            row.redNote.setText(s.unlocks + " unlocks today (+" + (s.unlocks * r.unlockMin)
                    + " min). That's too many. Maybe call it a day?");
        } else {
            row.redNote.setVisibility(View.GONE);
        }

        row.unlock.setVisibility(blocked && installed ? View.VISIBLE : View.GONE);
    }

    private void refreshStatus(int count) {
        boolean enabled = isServiceEnabled();
        boolean running = BlockerService.running != null;
        if (enabled && running) {
            statusCard.setBackgroundResource(R.drawable.card);
            statusTitle.setText("✓  Blocking is on");
            statusTitle.setTextColor(getColor(R.color.accent));
            statusBody.setText("Watching " + count + (count == 1 ? " app" : " apps")
                    + ". Hourlock only checks which app is on screen. It never reads what you see or type.");
            statusButtons.setVisibility(View.GONE);
        } else {
            statusCard.setBackgroundResource(R.drawable.card_red);
            statusTitle.setTextColor(getColor(R.color.red));
            if (enabled) {
                statusTitle.setText("Blocking is switched on but not running");
                statusBody.setText("Open Accessibility, turn Hourlock off and on again.");
            } else {
                statusTitle.setText("Blocking is off");
                statusBody.setText("1. Tap Accessibility, then Hourlock, and try the switch. "
                        + "If it says \"Restricted setting\", come back here.\n"
                        + "2. Tap App info, then ⋮ (top right), then \"Allow restricted settings\".\n"
                        + "3. Go to Accessibility, then Hourlock, and turn it on.");
            }
            statusButtons.setVisibility(View.VISIBLE);
        }
    }

    private boolean isServiceEnabled() {
        String s = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (s == null) return false;
        ComponentName me = new ComponentName(this, BlockerService.class);
        for (String part : s.split(":")) {
            if (me.equals(ComponentName.unflattenFromString(part))) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ editing

    private static int radioFor(int mode) {
        switch (mode) {
            case Engine.MODE_WARN: return R.id.m_warn;
            case Engine.MODE_LIMIT: return R.id.m_limit;
            case Engine.MODE_BLOCK: return R.id.m_block;
            default: return R.id.m_off;
        }
    }

    private static int modeFor(int radioId) {
        if (radioId == R.id.m_warn) return Engine.MODE_WARN;
        if (radioId == R.id.m_limit) return Engine.MODE_LIMIT;
        if (radioId == R.id.m_block) return Engine.MODE_BLOCK;
        return Engine.MODE_OFF;
    }

    private static String helpFor(int mode) {
        switch (mode) {
            case Engine.MODE_WARN:
                return "Never blocks. When you pass the daily threshold a popup appears, "
                        + "and another one stacks on top every 2 minutes you stay.";
            case Engine.MODE_LIMIT:
                return "Free use until the daily limit, then blocked. To unlock, open Hourlock, "
                        + "tap Unlock and sit through the wait below. Each unlock gives the minutes below, "
                        + "counted only while the app is open. More than 2 unlocks a day turns the app red.";
            case Engine.MODE_BLOCK:
                return "Always blocked. To unlock, open Hourlock, tap Unlock and sit through the wait below. "
                        + "Each unlock gives the minutes below, counted only while the app is open. "
                        + "More than 2 unlocks a day turns the app red.";
            default:
                return "Only counts your minutes. No warnings, no blocking.";
        }
    }

    private void showEdit(final Engine.Rule current, final boolean isNew) {
        final View v = getLayoutInflater().inflate(R.layout.dialog_edit, null);
        final RadioGroup modes = v.findViewById(R.id.modes);
        final EditText warn = v.findViewById(R.id.warn_min);
        final EditText limit = v.findViewById(R.id.limit_min);
        final EditText unlock = v.findViewById(R.id.unlock_min);
        final EditText wait = v.findViewById(R.id.wait_sec);
        final View rowWarn = v.findViewById(R.id.row_warn);
        final View rowLimit = v.findViewById(R.id.row_limit);
        final View rowUnlock = v.findViewById(R.id.row_unlock);
        final View rowWait = v.findViewById(R.id.row_wait);
        final TextView waitHint = v.findViewById(R.id.wait_hint);
        final TextView help = v.findViewById(R.id.mode_help);

        warn.setText(String.valueOf(current.warnMin));
        limit.setText(String.valueOf(current.limitMin));
        unlock.setText(String.valueOf(current.unlockMin));
        wait.setText(String.valueOf(current.unlockWaitSec));
        modes.check(radioFor(current.mode));

        final Runnable sync = new Runnable() {
            @Override public void run() {
                int m = modeFor(modes.getCheckedRadioButtonId());
                boolean unlocks = m >= Engine.MODE_LIMIT;
                rowWarn.setVisibility(m == Engine.MODE_WARN ? View.VISIBLE : View.GONE);
                rowLimit.setVisibility(m == Engine.MODE_LIMIT ? View.VISIBLE : View.GONE);
                rowUnlock.setVisibility(unlocks ? View.VISIBLE : View.GONE);
                rowWait.setVisibility(unlocks ? View.VISIBLE : View.GONE);
                waitHint.setVisibility(unlocks ? View.VISIBLE : View.GONE);
                help.setText(helpFor(m));
            }
        };
        final Runnable syncWaitHint = new Runnable() {
            @Override public void run() {
                try {
                    int sec = Integer.parseInt(wait.getText().toString().trim());
                    waitHint.setText(sec >= 60 ? "= " + Fmt.waitText(sec) : "");
                } catch (NumberFormatException e) {
                    waitHint.setText("");
                }
            }
        };
        wait.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { syncWaitHint.run(); }
        });
        syncWaitHint.run();
        modes.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup g, int id) { sync.run(); }
        });
        sync.run();

        final String label = isNew ? current.label : Fmt.label(this, current);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(label)
                .setView(v)
                .setPositiveButton(isNew ? "Add" : "Save", null)
                .setNegativeButton("Cancel", null);
        if (!isNew) b.setNeutralButton("Remove", null);
        final AlertDialog d = b.create();
        d.show();

        d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View x) {
                Engine.Rule nr = current.copy();
                nr.mode = modeFor(modes.getCheckedRadioButtonId());
                if (nr.mode == Engine.MODE_WARN) {
                    Integer w = parse(warn, 1, 1440);
                    if (w == null) return;
                    nr.warnMin = w;
                }
                if (nr.mode == Engine.MODE_LIMIT) {
                    Integer l = parse(limit, 1, 1440);
                    if (l == null) return;
                    nr.limitMin = l;
                }
                if (nr.mode >= Engine.MODE_LIMIT) {
                    Integer u = parse(unlock, 1, 60);
                    if (u == null) return;
                    Integer ws = parse(wait, Engine.MIN_UNLOCK_WAIT_SEC, Engine.MAX_UNLOCK_WAIT_SEC);
                    if (ws == null) return;
                    nr.unlockMin = u;
                    nr.unlockWaitSec = ws;
                }
                d.dismiss();
                applyChange(current, nr, isNew, label);
            }
        });
        if (!isNew) {
            d.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) {
                    d.dismiss();
                    removeApp(current, label);
                }
            });
        }
    }

    private Integer parse(EditText e, int lo, int hi) {
        try {
            int v = Integer.parseInt(e.getText().toString().trim());
            if (v >= lo && v <= hi) return v;
        } catch (NumberFormatException ignored) {
            // fall through
        }
        e.setError("Enter " + lo + " to " + hi);
        e.requestFocus();
        return null;
    }

    private void applyChange(Engine.Rule old, final Engine.Rule nr, boolean isNew, final String label) {
        if (isNew) {
            store.putRule(nr);
            toast(label + " added");
            return;
        }
        if (!Engine.isLoosening(old, nr)) {
            store.putRule(nr);
            toast("Saved");
            return;
        }
        waitThenRun("Loosen " + label + "?",
                "From: " + Fmt.ruleSummary(old) + "\nTo: " + Fmt.ruleSummary(nr),
                new Runnable() {
                    @Override public void run() {
                        store.putRule(nr);
                        toast(label + " loosened");
                    }
                });
    }

    private void removeApp(final Engine.Rule r, final String label) {
        if (!Engine.isLoosening(r, null)) {
            store.removeRule(r.pkg);
            toast(label + " removed");
            return;
        }
        waitThenRun("Remove " + label + "?",
                "This removes all limits from " + label + " (" + Fmt.ruleSummary(r) + ").",
                new Runnable() {
                    @Override public void run() {
                        store.removeRule(r.pkg);
                        toast(label + " removed");
                    }
                });
    }

    /**
     * Shows a countdown dialog; {@code action} can only run after the full
     * 3 minutes. Leaving the screen (onPause) closes it, so the wait restarts.
     */
    private void waitThenRun(String title, final String what, final Runnable action) {
        final long end = SystemClock.elapsedRealtime() + Engine.LOOSEN_WAIT_MS;
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(what)
                .setPositiveButton("Apply", null)
                .setNegativeButton("Never mind", null)
                .create();
        d.setCanceledOnTouchOutside(false);
        d.show();
        final Button ok = d.getButton(DialogInterface.BUTTON_POSITIVE);
        ok.setEnabled(false);

        final Runnable tick = new Runnable() {
            @Override public void run() {
                long left = end - SystemClock.elapsedRealtime();
                if (left > 0) {
                    d.setMessage(what + "\n\nLooser rules need a 3-minute wait. Keep this open. "
                            + "Leaving Hourlock restarts the wait.\n\n" + Fmt.clock(left) + " left");
                    ok.setText("Wait " + Fmt.clock(left));
                    handler.postDelayed(this, 250);
                } else {
                    d.setMessage(what + "\n\nYou waited 3 minutes. Still sure?");
                    ok.setText("Apply");
                    ok.setEnabled(true);
                }
            }
        };
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (SystemClock.elapsedRealtime() < end) return;
                d.dismiss();
                action.run();
            }
        });
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface di) {
                handler.removeCallbacks(tick);
                if (waitDialog == d) waitDialog = null;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        waitDialog = d;
        handler.post(tick);
    }

    // ------------------------------------------------------------------ adding apps

    private static final class Pick {
        final String pkg, label;
        final ResolveInfo info;
        Pick(String pkg, String label, ResolveInfo info) {
            this.pkg = pkg; this.label = label; this.info = info;
        }
    }

    private void showPicker() {
        PackageManager pm = getPackageManager();
        Set<String> skip = new HashSet<>();
        skip.add(getPackageName());
        skip.add("com.android.settings");   // never lock yourself out of Settings
        skip.add("com.android.systemui");
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : Fmt.activities(pm, home)) skip.add(ri.activityInfo.packageName);
        for (Engine.Rule r : store.rules()) skip.add(r.pkg);

        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        HashMap<String, Pick> byPkg = new HashMap<>();
        for (ResolveInfo ri : Fmt.activities(pm, launcher)) {
            String p = ri.activityInfo.packageName;
            if (skip.contains(p) || byPkg.containsKey(p)) continue;
            byPkg.put(p, new Pick(p, ri.loadLabel(pm).toString(), ri));
        }
        final ArrayList<Pick> all = new ArrayList<>(byPkg.values());
        Collections.sort(all, new Comparator<Pick>() {
            @Override public int compare(Pick a, Pick b) { return a.label.compareToIgnoreCase(b.label); }
        });

        View v = getLayoutInflater().inflate(R.layout.dialog_pick, null);
        EditText search = v.findViewById(R.id.search);
        ListView lv = v.findViewById(R.id.pick_list);
        final PickAdapter adapter = new PickAdapter(all);
        lv.setAdapter(adapter);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { adapter.filter(s.toString()); }
        });

        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Add an app")
                .setView(v)
                .setNegativeButton("Cancel", null)
                .create();
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                Pick p = adapter.getItem(pos);
                d.dismiss();
                Engine.Rule r = new Engine.Rule(p.pkg, p.label);
                r.mode = Engine.MODE_LIMIT;
                showEdit(r, true);
            }
        });
        d.show();
    }

    private final class PickAdapter extends BaseAdapter {
        private final ArrayList<Pick> all;
        private final ArrayList<Pick> shown;
        private final HashMap<String, Drawable> icons = new HashMap<>();

        PickAdapter(ArrayList<Pick> all) {
            this.all = all;
            this.shown = new ArrayList<>(all);
        }

        void filter(String q) {
            String needle = q.trim().toLowerCase(Locale.ROOT);
            shown.clear();
            for (Pick p : all) {
                if (needle.isEmpty() || p.label.toLowerCase(Locale.ROOT).contains(needle)
                        || p.pkg.contains(needle)) {
                    shown.add(p);
                }
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return shown.size(); }
        @Override public Pick getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            View v = convert != null ? convert
                    : getLayoutInflater().inflate(R.layout.item_pick, parent, false);
            Pick p = shown.get(i);
            Drawable icon = icons.get(p.pkg);
            if (icon == null) {
                icon = p.info.loadIcon(getPackageManager());
                icons.put(p.pkg, icon);
            }
            ((ImageView) v.findViewById(R.id.pick_icon)).setImageDrawable(icon);
            ((TextView) v.findViewById(R.id.pick_label)).setText(p.label);
            ((TextView) v.findViewById(R.id.pick_pkg)).setText(p.pkg);
            return v;
        }
    }

    // ------------------------------------------------------------------ misc

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (RuntimeException e) {
            toast("Couldn't open that settings page");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
