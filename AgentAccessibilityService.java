package com.roohu.phoneagent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AgentAccessibilityService extends AccessibilityService {
    public static volatile AgentAccessibilityService instance;
    private static final String[] BLOCK = {"bank", "paypal", "wallet", "paytm", "phonepe", "easypaisa", "jazzcash", "paisa"};
    private static final String[] RISKY = {"send", "pay", "buy", "purchase", "delete", "remove", "install", "uninstall", "confirm", "transfer", "order", "submit", "post", "call"};
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<String> log = new ArrayList<>();
    private volatile boolean running = false;
    private volatile boolean stop = false;
    private volatile String lastScreen = "[]";
    private Thread worker;
    private View stopView;
    private WindowManager wm;

    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public boolean onUnbind(Intent i) {
        instance = null;
        return super.onUnbind(i);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) { }

    @Override
    public void onInterrupt() { }

    private synchronized void addLog(String s) {
        log.add(s);
        if (log.size() > 200) log.remove(0);
    }

    public synchronized String getStatusJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("running", running);
            o.put("log", new JSONArray(log));
            o.put("screen", new JSONArray(lastScreen));
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public void stopNow() {
        stop = true;
        Thread w = worker;
        if (w != null) w.interrupt();
    }

    private void walk(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int d) {
        if (n == null || d > 40 || out.size() > 400) return;
        if (n.isVisibleToUser()) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), out, d + 1);
    }

    private List<AccessibilityNodeInfo> nodes() {
        List<AccessibilityNodeInfo> l = new ArrayList<>();
        walk(getRootInActiveWindow(), l, 0);
        return l;
    }

    private String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        return t == null ? "" : t.toString();
    }

    private boolean blocked() {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r == null) return false;
        String p = String.valueOf(r.getPackageName()).toLowerCase(Locale.ROOT);
        for (String b : BLOCK) if (p.contains(b)) return true;
        for (AccessibilityNodeInfo n : nodes()) if (n.isPassword()) return true;
        return false;
    }

    private boolean risky(String t) {
        for (String w : t.toLowerCase(Locale.ROOT).split("[^a-z]+"))
            for (String r : RISKY) if (w.equals(r)) return true;
        return false;
    }

    private int readScreen() {
        JSONArray a = new JSONArray();
        try {
            for (AccessibilityNodeInfo n : nodes()) {
                String t = label(n);
                if (t.isEmpty() || a.length() >= 80) continue;
                JSONObject o = new JSONObject();
                o.put("text", t);
                o.put("click", n.isClickable());
                o.put("edit", n.isEditable());
                a.put(o);
            }
        } catch (Exception e) { }
        lastScreen = a.toString();
        return a.length();
    }

    private boolean tap(String want) {
        String w = want.toLowerCase(Locale.ROOT).trim();
        AccessibilityNodeInfo hit = null;
        for (AccessibilityNodeInfo n : nodes()) {
            String t = label(n).toLowerCase(Locale.ROOT);
            if (t.equals(w)) { hit = n; break; }
            if (hit == null && !t.isEmpty() && t.contains(w)) hit = n;
        }
        if (hit == null) return false;
        for (AccessibilityNodeInfo c = hit; c != null; c = c.getParent())
            if (c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        Rect r = new Rect();
        hit.getBoundsInScreen(r);
        Path p = new Path();
        p.moveTo(r.centerX(), r.centerY());
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build();
        return dispatchGesture(g, null, null);
    }

    private boolean type(String s) {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r == null) return false;
        AccessibilityNodeInfo f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null) {
            for (AccessibilityNodeInfo n : nodes()) {
                if (n.isEditable()) { f = n; n.performAction(AccessibilityNodeInfo.ACTION_CLICK); break; }
            }
        }
        if (f == null) return false;
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, s);
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    private boolean scroll(boolean down) {
        for (AccessibilityNodeInfo n : nodes()) {
            if (n.isScrollable()) {
                return n.performAction(down ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            }
        }
        return false;
    }

    private boolean openApp(String name) {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        String w = name.toLowerCase(Locale.ROOT).trim();
        String pkg = null;
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String l = ri.loadLabel(pm).toString().toLowerCase(Locale.ROOT);
            if (l.equals(w)) { pkg = ri.activityInfo.packageName; break; }
            if (pkg == null && l.contains(w)) pkg = ri.activityInfo.packageName;
        }
        if (pkg == null) return false;
        Intent i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        return true;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private WindowManager.LayoutParams lp(int gravity, int y) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        p.gravity = gravity;
        p.y = y;
        return p;
    }

    private void showStop() {
        main.post(() -> {
            Button b = new Button(this);
            b.setText("STOP");
            b.setTextColor(Color.WHITE);
            b.setBackgroundColor(Color.RED);
            b.setOnClickListener(v -> stopNow());
            stopView = b;
            wm.addView(b, lp(Gravity.TOP | Gravity.CENTER_HORIZONTAL, dp(40)));
        });
    }

    private void hideStop() {
        main.post(() -> {
            try { if (stopView != null) wm.removeView(stopView); } catch (Exception e) { }
            stopView = null;
        });
    }

    private boolean confirm(String what) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean(false);
        final View[] box = new View[1];
        main.post(() -> {
            LinearLayout l = new LinearLayout(this);
            l.setOrientation(LinearLayout.VERTICAL);
            l.setBackgroundColor(Color.rgb(24, 24, 36));
            l.setPadding(dp(20), dp(20), dp(20), dp(20));
            TextView t = new TextView(this);
            t.setTextColor(Color.WHITE);
            t.setTextSize(18);
            t.setText("Allow this action?\n\n" + what);
            Button y = new Button(this);
            y.setText("Yes, do it");
            y.setOnClickListener(v -> { ok.set(true); latch.countDown(); });
            Button n = new Button(this);
            n.setText("No");
            n.setOnClickListener(v -> latch.countDown());
            l.addView(t);
            l.addView(y);
            l.addView(n);
            box[0] = l;
            wm.addView(l, lp(Gravity.CENTER, 0));
        });
        try { latch.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { }
        final View v0 = box[0];
        main.post(() -> { try { if (v0 != null) wm.removeView(v0); } catch (Exception e) { } });
        return ok.get() && !stop;
    }

    private String step(String a, String t) throws InterruptedException {
        switch (a) {
            case "open":
                for (String b : BLOCK) if (t.toLowerCase(Locale.ROOT).contains(b)) return "FAIL: money apps are blocked";
                return openApp(t) ? "ok" : "FAIL: app not found";
            case "tap": return tap(t) ? "ok" : "FAIL: not found on screen";
            case "type": return type(t) ? "ok" : "FAIL: no text box";
            case "scroll": return scroll(!t.equals("up")) ? "ok" : "FAIL: nothing to scroll";
            case "back": return performGlobalAction(GLOBAL_ACTION_BACK) ? "ok" : "FAIL";
            case "home": return performGlobalAction(GLOBAL_ACTION_HOME) ? "ok" : "FAIL";
            case "wait":
                int s = Math.min(10, Math.max(1, Integer.parseInt(t.isEmpty() ? "2" : t)));
                Thread.sleep(s * 1000L);
                return "ok";
            case "read": return "ok, saw " + readScreen() + " items (see Screen tab)";
            default: return "FAIL: unknown step";
        }
    }

    public synchronized boolean runPlan(String json) {
        if (running) return false;
        JSONArray parsed = null;
        try { parsed = new JSONArray(json); } catch (Exception e) { return false; }
        final JSONArray steps = parsed;
        running = true;
        stop = false;
        log.clear();
        showStop();
        worker = new Thread(() -> {
            try {
                for (int i = 0; i < steps.length() && i < 20 && !stop; i++) {
                    JSONObject s = steps.getJSONObject(i);
                    String a = s.optString("a");
                    String t = s.optString("t");
                    addLog((i + 1) + ". " + a + " " + t);
                    if (!a.equals("wait") && !a.equals("home") && blocked()) {
                        addLog("STOPPED: password or money app on screen.");
                        break;
                    }
                    if (a.equals("tap") && risky(t) && !confirm("tap \"" + t + "\"")) {
                        addLog("Not allowed. Stopped.");
                        break;
                    }
                    String r = step(a, t);
                    addLog("   " + r);
                    if (r.startsWith("FAIL")) break;
                    Thread.sleep(1500);
                }
            } catch (Exception e) { }
            addLog(stop ? "Stopped." : "Finished.");
            running = false;
            hideStop();
        });
        worker.start();
        return true;
    }
}
