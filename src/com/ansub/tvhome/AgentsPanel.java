package com.ansub.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

// A glance at the agents boxdeck sees (https://github.com/wicolian/boxdeck), one block per machine:
//
//   $ boxdeck status
//   box      ● 2 working   ◐ 3 waiting
//            claude 5h ████████░░ 78% left · $4.12 today
//
// "waiting" is idle + done: an agent that has stopped and wants you. It is the one thing worth
// seeing from the sofa, so it is the only figure in the warning colour. Off until a boxdeck URL and a
// read-only token are saved:
//   am start -n com.ansub.tvhome/.MainActivity --es boxdeck_url http://100.x.y.z:8100 --es boxdeck_token bdr_...
// (--es boxdeck_url none  turns it off). Polls every 20 seconds while Home is in front, nothing else.
final class AgentsPanel extends LinearLayout {
    static final String URL_KEY = "boxdeck_url", TOKEN_KEY = "boxdeck_token";
    private static final int POLL_MS = 20000, TIMEOUT_MS = 6000;
    private static final int WAITING = 0xFFFFC447;

    private final SharedPreferences prefs;
    private final int fg, fgSoft, fgDim, textPx;
    private final Typeface regular, bold;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView head, body;
    private boolean running;
    private Thread worker;

    AgentsPanel(Context c, SharedPreferences prefs, int fg, int fgSoft, int fgDim, int textPx,
                Typeface regular, Typeface bold) {
        super(c);
        this.prefs = prefs;
        this.fg = fg;
        this.fgSoft = fgSoft;
        this.fgDim = fgDim;
        this.textPx = textPx;
        this.regular = regular;
        this.bold = bold;
        setOrientation(VERTICAL);
        setGravity(Gravity.END);
        head = line(fgDim);
        head.setText("$ boxdeck status");
        body = line(fgSoft);
        body.setLineSpacing(textPx * 0.25f, 1f);
        addView(head);
        LayoutParams lp = new LayoutParams(-2, -2);
        lp.topMargin = textPx / 2;
        addView(body, lp);
    }

    static boolean configured(SharedPreferences p) {
        return p.getString(URL_KEY, null) != null && p.getString(TOKEN_KEY, null) != null;
    }

    private TextView line(int color) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx);
        t.setTextColor(color);
        t.setTypeface(regular);
        t.setGravity(Gravity.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    void start() {
        if (running) return;
        running = true;
        worker = new Thread(() -> {
            while (running) {
                CharSequence text = fetch();
                main.post(() -> { if (running) body.setText(text); });
                try { Thread.sleep(POLL_MS); } catch (InterruptedException e) { return; }
            }
        }, "boxdeck");
        worker.setDaemon(true);
        worker.start();
    }

    void stop() {
        running = false;
        if (worker != null) worker.interrupt();
        worker = null;
    }

    // Builds the panel text, or says plainly what is wrong and what to check.
    private CharSequence fetch() {
        String base = prefs.getString(URL_KEY, ""), token = prefs.getString(TOKEN_KEY, "");
        try {
            JSONArray boxes = new JSONArray(get(base + "/api/boxes", token));
            JSONArray agents = new JSONArray(get(base + "/api/agents", token));
            SpannableStringBuilder out = new SpannableStringBuilder();
            for (int i = 0; i < boxes.length(); i++) {
                JSONObject b = boxes.getJSONObject(i);
                if (out.length() > 0) out.append("\n\n");
                appendBox(out, b, b.optBoolean("local") ? agents : null);
            }
            if (out.length() == 0) append(out, "no machines reported", fgDim);
            return out;
        } catch (HttpStatus e) {
            return status(e.code == 401 || e.code == 403 ? "token refused (" + e.code + ") - mint a read-only one"
                    : "boxdeck answered " + e.code);
        } catch (java.net.SocketTimeoutException | java.net.ConnectException e) {
            return status("box unreachable - is Tailscale on?");
        } catch (Exception e) {
            return status("offline: " + e.getClass().getSimpleName());
        }
    }

    private CharSequence status(String s) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        append(out, s, fgDim);
        return out;
    }

    private void appendBox(SpannableStringBuilder out, JSONObject b, JSONArray agents) {
        String name = b.optString("name", "box");
        if (!b.optBoolean("ok", true)) {
            append(out, name + "  ", fg);
            append(out, "down", WAITING);
            return;
        }
        int working = 0, waiting = 0, other = 0;
        if (agents != null) for (int i = 0; i < agents.length(); i++) {
            String s = agents.optJSONObject(i) == null ? "" : agents.optJSONObject(i).optString("agent_status", "");
            if (s.equals("working")) working++;
            else if (s.equals("idle") || s.equals("done")) waiting++;
            else other++;
        } else {
            other = b.optInt("agents", 0);
        }
        append(out, name + "  ", fg);
        if (working > 0) append(out, "● " + working + " working", fg);
        if (waiting > 0) append(out, (working > 0 ? "   " : "") + "◐ " + waiting + " waiting", WAITING);
        if (other > 0) append(out, (working + waiting > 0 ? "   " : "") + other + " other", fgDim);
        if (working + waiting + other == 0) append(out, "no agents", fgDim);

        JSONObject usage = b.optJSONObject("usage");
        JSONObject quota = usage == null ? null : usage.optJSONObject("quota");
        JSONObject claude = quota == null ? null : quota.optJSONObject("claude");
        JSONObject five = claude == null ? null : claude.optJSONObject("fiveHour");
        double cost = 0;
        JSONObject today = usage == null ? null : usage.optJSONObject("today");
        if (today != null) for (String p : new String[] {"claude", "codex"}) {
            JSONObject t = today.optJSONObject(p);
            if (t != null) cost += t.optDouble("costUsd", 0);
        }
        out.append("\n");
        if (five != null && five.has("pct")) {
            int used = (int) Math.round(Math.max(0, Math.min(100, five.optDouble("pct", 0))));
            int filled = Math.round(used / 10f);
            StringBuilder bar = new StringBuilder();
            for (int i = 0; i < 10; i++) bar.append(i < filled ? '█' : '░');
            append(out, "claude 5h ", fgDim);
            append(out, bar.toString(), used >= 80 ? WAITING : fg);
            append(out, " " + (100 - used) + "% left", fgSoft);
        } else {
            append(out, "claude 5h -", fgDim);
        }
        JSONObject week = claude == null ? null : claude.optJSONObject("sevenDay");
        if (week != null && week.has("pct")) {
            int used = (int) Math.round(Math.max(0, Math.min(100, week.optDouble("pct", 0))));
            append(out, " · 7d ", fgDim);
            append(out, (100 - used) + "% left", used >= 80 ? WAITING : fgSoft);
        }
        // boxdeck prices tokens at list price, so this is an estimate, not a bill.
        append(out, String.format(java.util.Locale.ROOT, "\n~$%.0f today (list price)", cost), fgDim);
    }

    private static void append(SpannableStringBuilder out, String s, int color) {
        int start = out.length();
        out.append(s);
        out.setSpan(new ForegroundColorSpan(color), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static final class HttpStatus extends Exception {
        final int code;
        HttpStatus(int code) { this.code = code; }
    }

    private static String get(String url, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new HttpStatus(code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
                return buf.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
