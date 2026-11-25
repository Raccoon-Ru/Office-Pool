package com.officepool;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;

import org.json.*;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int GREEN = 0xffB7F46A;
    private static final int ACCENT_TEXT = 0xff192722;
    private static final String APPEARANCE = "appearance";
    private static final String[] APPEARANCE_MODES = {"System", "Light", "Dark"};
    private int INK, MUTED, BG, CARD, LINE, featuredPanel;
    private boolean darkAppearance;
    private static final String DEFAULT_SERVER_URL = BuildConfig.CUE_CLUB_SERVER_URL;
    private static final String FORMER_SERVER_URL = "https://acumen-ruan2024.tail49ffe5.ts.net";
    private static final String ANDROID_UPDATE_PATH = "/updates/android";
    private static final long UPDATE_RETRY_MS = 60_000;
    private static final long UPDATE_CHECK_INTERVAL_MS = 15 * 60_000;
    static final String WORK_DOMAIN = "@acumensoft.net";
    static final String PREFERENCES_NAME = "MainActivity";
    static final int PICK_PHOTO = 4102;
    private static final int MAX_PHOTO_MB = 12;
    private static final long MAX_PHOTO_BYTES = MAX_PHOTO_MB * 1024L * 1024L;
    private static final String PHOTO_LIMIT_MESSAGE =
            "Choose a photo of " + MAX_PHOTO_MB + " MB or less.";
    static final int NOTIFICATION_PERMISSION_REQUEST = 5201;
    LinearLayout root, content, nav;
    ScrollView bodyScroll;
    TextView statusChip;
    private TextView matchAlertsStatus;
    private TextView poolTableStatus;
    private JSONArray playingNow = new JSONArray();
    private boolean hasTableStatus;
    private String viewedProfileUid = "";
    private int profileReturnPage = 2;
    int page = 0;

    private enum ResultFilter {
        ALL,
        LEAGUE,
        ONE_V_ONE
    }

    private ResultFilter resultFilter = ResultFilter.ALL;
    JSONArray matches = new JSONArray(), games = new JSONArray(), ratingHistory = new JSONArray();
    ArrayList<String> players = new ArrayList<>();
    ArrayList<OfficeMember> members = new ArrayList<>();
    JSONObject seasonData = new JSONObject();
    long draftLeagueDeadline;
    String draftLeagueName = "Office League";
    SharedPreferences prefs;
    String serverUrl = "", uid = "", sessionName = "";
    private volatile String token = "";
    volatile boolean online = false, syncBusy = false;
    boolean createAccount = false, isAdmin = false, showAdminPanel = false;
    final Handler handler = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService avatarIo = Executors.newFixedThreadPool(2);
    private long nextUpdateCheck;
    private int offeredUpdate;
    Runnable poll;
    private final Set<String> openedMatchTimers = new HashSet<>();
    private String timerGameId = "";
    private TextView matchTimerText;
    private volatile long serverClockOffsetMs;
    private volatile boolean hasServerClock;
    private boolean foreground;
    private final Runnable timerTick = this::updateMatchTimer;

    @Override
    public void onCreate(Bundle b) {
        prefs = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE);
        int appearance = appearanceMode();
        boolean systemDark =
                (getResources().getConfiguration().uiMode
                                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                        == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        darkAppearance = appearance == 2 || (appearance == 0 && systemDark);
        if (darkAppearance) setTheme(R.style.AppThemeDark);
        INK = tone(0xff192722, 0xffF1F3EF);
        MUTED = tone(0xff58685F, 0xffB7C2BA);
        BG = tone(0xffF5F6F2, 0xff25282B);
        CARD = tone(Color.WHITE, 0xff32363A);
        LINE = tone(0xffE8EBE5, 0xff50575B);
        featuredPanel = tone(0xff192722, 0xff384541);
        super.onCreate(b);
        setSystemBars();
        load();
        if (b != null) {
            page = b.getInt("page");
            viewedProfileUid = b.getString("viewedProfileUid", "");
            profileReturnPage = b.getInt("profileReturnPage", 2);
            showAdminPanel = b.getBoolean("showAdminPanel");
            timerGameId = b.getString("timerGameId", "");
            ArrayList<String> opened = b.getStringArrayList("openedMatchTimers");
            if (opened != null) openedMatchTimers.addAll(opened);
            resultFilter = ResultFilter.values()[b.getInt("resultFilter")];
            draftLeagueName = b.getString("draftLeagueName", draftLeagueName);
            draftLeagueDeadline = b.getLong("draftLeagueDeadline");
            createAccount = b.getBoolean("createAccount");
        }
        openMatchNotifications(getIntent());
        show();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("page", page);
        state.putString("viewedProfileUid", viewedProfileUid);
        state.putInt("profileReturnPage", profileReturnPage);
        state.putBoolean("showAdminPanel", showAdminPanel);
        state.putString("timerGameId", timerGameId);
        state.putStringArrayList("openedMatchTimers", new ArrayList<>(openedMatchTimers));
        state.putInt("resultFilter", resultFilter.ordinal());
        state.putString("draftLeagueName", draftLeagueName);
        state.putLong("draftLeagueDeadline", draftLeagueDeadline);
        state.putBoolean("createAccount", createAccount);
    }

    private int tone(int light, int dark) {
        return darkAppearance ? dark : light;
    }

    private int appearanceMode() {
        return Math.max(0, Math.min(2, prefs.getInt(APPEARANCE, 2)));
    }

    private void showAppearanceSettings() {
        new AlertDialog.Builder(this)
                .setTitle("Appearance")
                .setSingleChoiceItems(
                        APPEARANCE_MODES,
                        appearanceMode(),
                        (dialog, choice) -> {
                            dialog.dismiss();
                            if (appearanceMode() == choice) return;
                            prefs.edit().putInt(APPEARANCE, choice).apply();
                            recreate();
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (openMatchNotifications(intent)) {
            show();
            syncNow();
        }
    }

    private boolean openMatchNotifications(Intent intent) {
        if (intent == null || !intent.hasExtra(MatchInboxService.LEAGUE_NOTIFICATION)) return false;
        showAdminPanel = false;
        page = intent.getBooleanExtra(MatchInboxService.LEAGUE_NOTIFICATION, false) ? 0 : 1;
        intent.removeExtra(MatchInboxService.LEAGUE_NOTIFICATION);
        return true;
    }

    void setSystemBars() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        int flags =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (!darkAppearance) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
    }

    void load() {
        serverUrl = prefs.getString("serverUrl", DEFAULT_SERVER_URL);
        String configured = serverUrl.trim().replaceAll("/+$", "");
        if (configured.isEmpty() || configured.equalsIgnoreCase(FORMER_SERVER_URL)) {
            serverUrl = DEFAULT_SERVER_URL;
        }
        token = prefs.getString("token", "");
        uid = prefs.getString("uid", "");
        sessionName = prefs.getString("sessionName", "");
        isAdmin = prefs.getBoolean("isAdmin", false);
        try {
            JSONArray storedMatches = new JSONArray(prefs.getString("matches", "[]"));
            for (int i = 0; i < storedMatches.length(); i++) {
                JSONObject match = storedMatches.optJSONObject(i);
                if (match != null && match.optBoolean("synced", true)) matches.put(match);
            }
        } catch (Exception e) {
            matches = new JSONArray();
        }
        try {
            games = new JSONArray(prefs.getString("games", "[]"));
        } catch (Exception e) {
            games = new JSONArray();
        }
        try {
            ratingHistory = new JSONArray(prefs.getString("ratingHistory", "[]"));
        } catch (JSONException ignored) {
            ratingHistory = new JSONArray();
        }
        try {
            seasonData = new JSONObject(prefs.getString("season", "{}"));
        } catch (Exception e) {
            seasonData = new JSONObject();
        }
        try {
            JSONArray a =
                    new JSONArray(
                            prefs.getString(
                                    "players",
                                    "[\"You\",\"Alex Morgan\",\"Jamie Lee\",\"Sam Patel\"]"));
            for (int i = 0; i < a.length(); i++) players.add(a.getString(i));
        } catch (Exception ignored) {
        }
        try {
            JSONArray a = new JSONArray(prefs.getString("members", "[]"));
            for (int i = 0; i < a.length(); i++)
                members.add(OfficeMember.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
    }

    void save() {
        JSONArray m = new JSONArray();
        for (String p : players) m.put(p);
        JSONArray p = new JSONArray();
        for (OfficeMember x : members) p.put(x.toJson());
        prefs.edit()
                .putString("serverUrl", serverUrl)
                .putString("token", token)
                .putString("uid", uid)
                .putString("sessionName", sessionName)
                .putBoolean("isAdmin", isAdmin)
                .putString("matches", matches.toString())
                .putString("games", games.toString())
                .putString("season", seasonData.toString())
                .putString("ratingHistory", ratingHistory.toString())
                .putString("players", m.toString())
                .putString("members", p.toString())
                .apply();
    }

    int dp(float n) {
        return (int) (n * getResources().getDisplayMetrics().density + .5f);
    }

    GradientDrawable shape(int c, int r) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(c);
        d.setCornerRadius(dp(r));
        return d;
    }

    TextView text(String s, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    void pad(View v, int l, int t, int r, int b) {
        v.setPadding(dp(l), dp(t), dp(r), dp(b));
    }

    LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
    }

    TextView button(String label) {
        TextView v = text(label, 14, ACCENT_TEXT, true);
        v.setGravity(Gravity.CENTER);
        v.setBackground(shape(GREEN, 14));
        pad(v, 18, 13, 18, 13);
        return v;
    }

    LinearLayout newRoot() {
        LinearLayout r = vertical();
        r.setBackgroundColor(BG);
        r.setOnApplyWindowInsetsListener(
                (v, insets) -> {
                    int l, t, rr, b;
                    if (Build.VERSION.SDK_INT >= 30) {
                        android.graphics.Insets i =
                                insets.getInsets(
                                        android.view.WindowInsets.Type.systemBars()
                                                | android.view.WindowInsets.Type.ime());
                        l = i.left;
                        t = i.top;
                        rr = i.right;
                        b = i.bottom;
                    } else {
                        l = insets.getSystemWindowInsetLeft();
                        t = insets.getSystemWindowInsetTop();
                        rr = insets.getSystemWindowInsetRight();
                        b = insets.getSystemWindowInsetBottom();
                    }
                    v.setPadding(l, t, rr, b);
                    return insets;
                });
        return r;
    }

    void addHeader() {
        LinearLayout h = horizontal();
        pad(h, 22, 12, 20, 10);
        TextView brand = text("●  CUE CLUB", 13, INK, true);
        brand.setLetterSpacing(.1f);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(brand, new LinearLayout.LayoutParams(0, dp(38), 1));
        if (!token.isEmpty()
                && page == 4
                && viewedProfileUid.isEmpty()
                && !showAdminPanel
                && timerGameId.isEmpty()) {
            TextView settings = text("Settings", 12, INK, true);
            settings.setGravity(Gravity.CENTER);
            pad(settings, 8, 0, 8, 0);
            settings.setOnClickListener(v -> showSettings());
            h.addView(settings, lp(-2, 48));
        }
        if (!token.isEmpty() && page == 0 && !showAdminPanel && timerGameId.isEmpty()) {
            OfficeMember member = memberByUid(uid);
            String name = member == null ? currentName() : member.name;
            LinearLayout profile = horizontal();
            pad(profile, 6, 0, 2, 0);
            TextView label = text(name, 12, INK, true);
            label.setMaxWidth(dp(100));
            label.setSingleLine();
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams labelPosition = lp(-2, -2);
            labelPosition.rightMargin = dp(7);
            profile.addView(label, labelPosition);
            profile.addView(avatar(name, member), lp(34, 34));
            profile.setContentDescription("My profile, " + name);
            profile.setOnClickListener(v -> openMyProfile());
            h.addView(profile, lp(-2, 48));
        }
        root.addView(h);
    }

    void show() {
        handler.removeCallbacks(timerTick);
        matchTimerText = null;
        matchAlertsStatus = null;
        poolTableStatus = null;
        if (token.isEmpty()) {
            timerGameId = "";
            openedMatchTimers.clear();
            showAuth();
            return;
        }
        JSONObject timed = findGame(timerGameId);
        if (timed != null
                && isOwnStartedGame(timed)
                && !timed.optString("status").equals("declined")) {
            showMatchTimer(timed, false);
            return;
        }
        timerGameId = "";
        for (int i = 0; i < games.length(); i++) {
            JSONObject game = games.optJSONObject(i);
            if (isOwnStartedGame(game)
                    && isTimingMatch(game.optString("status"))
                    && !openedMatchTimers.contains(game.optString("id"))) {
                openMatchTimer(game);
                return;
            }
        }
        root = newRoot();
        setContentView(root);
        addHeader();
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        bodyScroll = s;
        content = vertical();
        pad(content, 20, 10, 20, 24);
        s.addView(content);
        root.addView(s, new LinearLayout.LayoutParams(-1, 0, 1));
        if (showAdminPanel) adminPage();
        else render();
        nav = horizontal();
        nav.setBackgroundColor(CARD);
        nav.setElevation(dp(12));
        String[] labels = {"Home", "Results", "Players", "Table", "Profile"},
                symbols = {"⌂", "◷", "♙", "≡", "▥"};
        for (int i = 0; i < labels.length; i++) {
            final int n = i;
            LinearLayout item = vertical();
            item.setGravity(Gravity.CENTER);
            TextView icon = text(symbols[i], 21, !showAdminPanel && i == page ? INK : MUTED, true);
            icon.setGravity(Gravity.CENTER);
            item.addView(icon, new LinearLayout.LayoutParams(-1, -2));
            TextView label =
                    text(
                            labels[i],
                            10,
                            !showAdminPanel && i == page ? INK : MUTED,
                            !showAdminPanel && i == page);
            label.setGravity(Gravity.CENTER);
            item.addView(label, new LinearLayout.LayoutParams(-1, -2));
            pad(item, 0, 8, 0, 7);
            item.setOnClickListener(
                    v -> {
                        page = n;
                        viewedProfileUid = "";
                        showAdminPanel = false;
                        show();
                        if (n == 0) beginPolling();
                    });
            nav.addView(item, new LinearLayout.LayoutParams(0, dp(58), 1));
        }
        root.addView(nav, new LinearLayout.LayoutParams(-1, dp(58)));
    }

    private boolean isOwnLeagueGame(JSONObject game) {
        return isOwnStartedGame(game) && !game.optString("tournamentMatchId").isEmpty();
    }

    private boolean isOwnStartedGame(JSONObject game) {
        return game != null
                && !game.optString("startedAt").isEmpty()
                && (uid.equals(game.optString("playerAUid"))
                        || uid.equals(game.optString("playerBUid")));
    }

    private boolean isTimingMatch(String status) {
        return status.equals("active")
                || status.equals("awaiting_confirmation")
                || status.equals("disputed");
    }

    private void openMatchTimer(JSONObject game) {
        timerGameId = game.optString("id");
        boolean animate =
                openedMatchTimers.add(timerGameId)
                        && game.optString("tournamentMatchId").isEmpty()
                        && "active".equals(game.optString("status"));
        showMatchTimer(game, animate);
    }

    private void showMatchTimer(JSONObject game, boolean animate) {
        root = newRoot();
        setContentView(root);
        addHeader();
        ScrollView scroll = new ScrollView(this);
        content = vertical();
        pad(content, 20, 16, 20, 24);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        String state = game.optString("status");
        boolean finished = state.equals("confirmed");
        boolean league = !game.optString("tournamentMatchId").isEmpty();
        title(
                league ? "OFFICE LEAGUE" : "1V1",
                finished ? "Match complete" : league ? "League match" : "1v1 match",
                null);
        LinearLayout matchup = horizontal();
        matchup.setGravity(Gravity.CENTER);
        LinearLayout left = timerPlayer(game, "A"), right = timerPlayer(game, "B");
        matchup.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        TextView versus = text("VS", 36, tone(0xff3D7134, GREEN), true);
        versus.setGravity(Gravity.CENTER);
        matchup.addView(versus, lp(72, -2));
        matchup.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(matchup, lp(-1, -2));
        LinearLayout clock = card();
        clock.setGravity(Gravity.CENTER_HORIZONTAL);
        clock.setBackground(shape(featuredPanel, 22));
        clock.addView(text(finished ? "MATCH DURATION" : "ELAPSED TIME", 12, 0xffC3D5C5, true));
        matchTimerText = text("", 48, GREEN, true);
        matchTimerText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        matchTimerText.setGravity(Gravity.CENTER);
        clock.addView(matchTimerText, lp(-1, -2));
        updateMatchTimer();
        if (animate
                && (Build.VERSION.SDK_INT < 26
                        || android.animation.ValueAnimator.areAnimatorsEnabled())) {
            animateMatchIntro(left, right, versus, clock);
        }

        String reportField =
                uid.equals(game.optString("playerAUid")) ? "reportAWinnerUid" : "reportBWinnerUid";
        boolean reported = !game.optString(reportField).isEmpty();
        if (finished) {
            content.addView(text("Winner · " + game.optString("winnerName"), 18, INK, true));
        } else if (state.equals("disputed")) {
            content.addView(text("The league admin is reviewing the result.", 14, MUTED, false));
        } else if (reported) {
            content.addView(
                    text("Waiting for your opponent to confirm the winner.", 14, MUTED, false));
        } else {
            TextView result =
                    button(
                            state.equals("awaiting_confirmation")
                                    ? "Confirm winner"
                                    : "Report winner");
            content.addView(result, lp(-1, -2));
            result.setOnClickListener(v -> reportWinnerDialog(game));
        }

        TextView back = button(league ? "Back to bracket" : "Back to results");
        LinearLayout.LayoutParams backLayout = lp(-1, -2);
        backLayout.topMargin = dp(12);
        content.addView(back, backLayout);
        back.setOnClickListener(v -> leaveMatchTimer());
    }

    private LinearLayout timerPlayer(JSONObject game, String side) {
        String name = game.optString("player" + side + "Name");
        OfficeMember member = memberByUid(game.optString("player" + side + "Uid"));
        LinearLayout player = vertical();
        player.setGravity(Gravity.CENTER);
        player.addView(avatar(name, member), lp(68, 68));
        TextView label = text(name, 14, INK, true);
        label.setGravity(Gravity.CENTER);
        pad(label, 4, 6, 4, 0);
        player.addView(label, lp(-1, -2));
        return player;
    }

    private void animateMatchIntro(View left, View right, View versus, View clock) {
        left.setTranslationX(-dp(32));
        right.setTranslationX(dp(32));
        left.setAlpha(0);
        right.setAlpha(0);
        versus.setAlpha(0);
        versus.setScaleX(.75f);
        versus.setScaleY(.75f);
        clock.setAlpha(0);
        left.animate().translationX(0).alpha(1).setDuration(330).start();
        right.animate().translationX(0).alpha(1).setDuration(330).start();
        versus.animate().scaleX(1).scaleY(1).alpha(1).setStartDelay(140).setDuration(250).start();
        clock.animate().alpha(1).setStartDelay(380).setDuration(230).start();
    }

    private void updateMatchTimer() {
        handler.removeCallbacks(timerTick);
        JSONObject game = findGame(timerGameId);
        if (matchTimerText == null || game == null) return;
        boolean running = isTimingMatch(game.optString("status"));
        String finished = game.optString("finishedAt");
        matchTimerText.setText(
                running || !finished.isEmpty()
                        ? elapsed(game.optString("startedAt"), finished)
                        : "—");
        if (foreground && running) handler.postDelayed(timerTick, 250);
    }

    private void leaveMatchTimer() {
        JSONObject game = findGame(timerGameId);
        page = game != null && game.optString("tournamentMatchId").isEmpty() ? 1 : 0;
        timerGameId = "";
        showAdminPanel = false;
        show();
    }

    @Override
    public void onBackPressed() {
        if (!timerGameId.isEmpty()) leaveMatchTimer();
        else if (!viewedProfileUid.isEmpty()) closeCoworkerProfile();
        else super.onBackPressed();
    }

    void showAuth() {
        root = newRoot();
        setContentView(root);
        addHeader();
        ScrollView s = new ScrollView(this);
        s.setFillViewport(true);
        s.setSmoothScrollingEnabled(true);
        content = vertical();
        pad(content, 22, 12, 22, 28);
        s.addView(content);
        root.addView(s, new LinearLayout.LayoutParams(-1, 0, 1));
        title(
                "PRIVATE OFFICE LEAGUE",
                createAccount ? "Create your profile" : "Welcome to Cue Club",
                createAccount
                        ? "Use your Acumensoft work email to join the shared scorebook."
                        : "Sign in to see the shared players and scores.");
        TextView label = lineLabel("PC SERVER ADDRESS");
        content.addView(label);
        EditText address = input("https://your-pc.your-tailnet.ts.net", false);
        address.setText(serverUrl);
        LinearLayout.LayoutParams p = lp(-1, 54);
        p.topMargin = dp(7);
        p.bottomMargin = dp(8);
        content.addView(address, p);
        TextView note =
                text(
                        "Your PC hosts the league. This shows Offline whenever that server can’t be"
                                + " reached.",
                        12,
                        MUTED,
                        false);
        content.addView(note, lp(-1, -2));
        LinearLayout form = vertical();
        pad(form, 18, 16, 18, 16);
        form.setBackground(shape(CARD, 20));
        LinearLayout.LayoutParams fp = lp(-1, -2);
        fp.topMargin = dp(18);
        content.addView(form, fp);
        if (createAccount) {
            form.addView(lineLabel("DISPLAY NAME"));
            EditText nm = input("Name shown to the office", false);
            form.addView(nm, lp(-1, 52));
            form.addView(spacer(8));
            form.addView(lineLabel("OFFICE EMAIL"));
            EditText em = input("name@acumensoft.net", true);
            form.addView(em, lp(-1, 52));
            form.addView(spacer(8));
            form.addView(lineLabel("OFFICE JOIN CODE"));
            EditText code = input("Ask the server owner for the code", false);
            form.addView(code, lp(-1, 52));
            form.addView(spacer(8));
            form.addView(lineLabel("PASSWORD"));
            EditText pw = input("At least 8 characters", true);
            pw.setInputType(129);
            form.addView(pw, lp(-1, 52));
            TextView join = button("Create office profile");
            LinearLayout.LayoutParams bp = lp(-1, -2);
            bp.topMargin = dp(14);
            form.addView(join, bp);
            join.setOnClickListener(
                    v -> {
                        serverUrl = address.getText().toString().trim();
                        register(
                                em.getText().toString().trim(),
                                pw.getText().toString(),
                                nm.getText().toString().trim(),
                                code.getText().toString().trim());
                    });
        } else {
            form.addView(lineLabel("OFFICE EMAIL"));
            EditText em = input("name@acumensoft.net", true);
            form.addView(em, lp(-1, 52));
            form.addView(spacer(8));
            form.addView(lineLabel("PASSWORD"));
            EditText pw = input("Your password", true);
            pw.setInputType(129);
            form.addView(pw, lp(-1, 52));
            TextView sign = button("Sign in");
            LinearLayout.LayoutParams bp = lp(-1, -2);
            bp.topMargin = dp(14);
            form.addView(sign, bp);
            sign.setOnClickListener(
                    v -> {
                        serverUrl = address.getText().toString().trim();
                        login(em.getText().toString().trim(), pw.getText().toString());
                    });
        }
        TextView swap =
                text(
                        createAccount
                                ? "Already have a profile?  Sign in"
                                : "New to the office league?  Create a profile",
                        13,
                        INK,
                        true);
        swap.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sw = lp(-1, 52);
        sw.topMargin = dp(12);
        content.addView(swap, sw);
        swap.setOnClickListener(
                v -> {
                    serverUrl = address.getText().toString().trim();
                    createAccount = !createAccount;
                    showAuth();
                });
        TextView steps =
                text(
                        "Use your office join code to create a profile. Your PC hosts the shared"
                                + " league.",
                        12,
                        MUTED,
                        false);
        LinearLayout.LayoutParams st = lp(-1, -2);
        st.topMargin = dp(10);
        content.addView(steps, st);
    }

    View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(lp(1, h));
        return v;
    }

    EditText input(String hint, boolean email) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextSize(14);
        e.setHint(hint);
        e.setTextColor(INK);
        e.setHintTextColor(MUTED);
        if (email) e.setInputType(33);
        return e;
    }

    void login(String email, String password) {
        if (!validServer()) {
            toast("Enter the PC's HTTPS Tailscale address first.");
            return;
        }
        if (!email.toLowerCase(Locale.ROOT).endsWith(WORK_DOMAIN)) {
            toast("Use your @acumensoft.net work email.");
            return;
        }
        if (password.isEmpty()) {
            toast("Enter your password.");
            return;
        }
        String base = serverUrl;
        io.execute(
                () -> {
                    try {
                        OfficeApi api = new OfficeApi(base);
                        api.get("/health", null);
                        JSONObject result =
                                api.post(
                                        "/auth/login",
                                        null,
                                        new JSONObject()
                                                .put("email", email)
                                                .put("password", password));
                        onAuthSuccess(base, result);
                    } catch (Exception e) {
                        runOnUiThread(() -> toast(errorText(e)));
                    }
                });
    }

    void register(String email, String password, String name, String joinCode) {
        if (!validServer()) {
            toast("Enter the PC's HTTPS Tailscale address first.");
            return;
        }
        if (!email.toLowerCase(Locale.ROOT).endsWith(WORK_DOMAIN)) {
            toast("Use your @acumensoft.net work email.");
            return;
        }
        if (name.trim().isEmpty() || password.length() < 8 || joinCode.trim().isEmpty()) {
            toast("Enter your name, join code, and a password with at least 8 characters.");
            return;
        }
        String base = serverUrl;
        io.execute(
                () -> {
                    try {
                        OfficeApi api = new OfficeApi(base);
                        api.get("/health", null);
                        JSONObject result =
                                api.post(
                                        "/auth/register",
                                        null,
                                        new JSONObject()
                                                .put("email", email)
                                                .put("password", password)
                                                .put("displayName", name)
                                                .put("joinCode", joinCode));
                        onAuthSuccess(base, result);
                    } catch (Exception e) {
                        runOnUiThread(() -> toast(errorText(e)));
                    }
                });
    }

    boolean validServer() {
        try {
            URL u = new URL(serverUrl.trim());
            String host = u.getHost();
            return host != null
                    && !host.isEmpty()
                    && ("https".equalsIgnoreCase(u.getProtocol())
                            || ("http".equalsIgnoreCase(u.getProtocol())
                                    && (host.equals("127.0.0.1")
                                            || host.equalsIgnoreCase("localhost"))));
        } catch (Exception e) {
            return false;
        }
    }

    void onAuthSuccess(String base, JSONObject r) {
        token = r.optString("token");
        uid = r.optJSONObject("member").optString("uid");
        sessionName = r.optJSONObject("member").optString("displayName");
        isAdmin = r.optJSONObject("member").optBoolean("isAdmin", false);
        serverUrl = base;
        OfficeMember self = OfficeMember.fromJson(r.optJSONObject("member"));
        if (memberByUid(uid) == null) members.add(self);
        save();
        online = true;
        page = 0;
        runOnUiThread(
                () -> {
                    show();
                    syncNow();
                    startInboxService();
                });
    }

    String errorText(Exception e) {
        String m = e.getMessage();
        if (e instanceof OfficeApi.ApiException && ((OfficeApi.ApiException) e).code == 404)
            return "PC server address was reached, but its API was not found.";
        return m == null || m.isEmpty() ? "Could not reach the PC server." : m;
    }

    private void openMyProfile() {
        viewedProfileUid = "";
        page = 4;
        showAdminPanel = false;
        timerGameId = "";
        show();
    }

    private void openCoworkerProfile(OfficeMember member) {
        if (member.uid.equals(uid)) {
            openMyProfile();
            return;
        }
        if (viewedProfileUid.isEmpty()) profileReturnPage = page;
        viewedProfileUid = member.uid;
        page = 4;
        showAdminPanel = false;
        show();
    }

    private void closeCoworkerProfile() {
        viewedProfileUid = "";
        page = profileReturnPage;
        show();
    }

    private void linkProfilePhoto(View photo, OfficeMember member) {
        if (member == null) return;
        photo.setContentDescription("View profile, " + member.name);
        photo.setOnClickListener(v -> openCoworkerProfile(member));
    }

    private void showSettings() {
        LinearLayout menu = vertical();
        pad(menu, 20, 8, 20, 8);
        statusChip = text("", 13, INK, true);
        pad(statusChip, 12, 10, 12, 10);
        menu.addView(statusChip, lp(-1, -2));
        updateStatus();
        AlertDialog dialog =
                new AlertDialog.Builder(this)
                        .setTitle("Settings")
                        .setView(menu)
                        .setNegativeButton("Close", null)
                        .create();
        if (!token.isEmpty()) {
            addHomeMatchButton(
                    menu,
                    "My profile",
                    () -> {
                        dialog.dismiss();
                        openMyProfile();
                    });
        }
        addHomeMatchButton(
                menu,
                "Theme · " + APPEARANCE_MODES[appearanceMode()],
                () -> {
                    dialog.dismiss();
                    showAppearanceSettings();
                });
        if (!token.isEmpty()) {
            addHomeMatchButton(
                    menu,
                    "PC server address",
                    () -> {
                        dialog.dismiss();
                        serverSettings();
                    });
            addHomeMatchButton(
                    menu,
                    "Sign out",
                    () -> {
                        dialog.dismiss();
                        new AlertDialog.Builder(this)
                                .setTitle("Sign out?")
                                .setMessage("You can sign in again with your office account.")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Sign out", (confirm, which) -> logout())
                                .show();
                    });
        }
        dialog.setOnDismissListener(dismissed -> statusChip = null);
        dialog.show();
    }

    void serverSettings() {
        if (token.isEmpty()) return;
        EditText address = input("https://your-pc.your-tailnet.ts.net", false);
        address.setText(serverUrl);
        new AlertDialog.Builder(this)
                .setTitle("PC server address")
                .setMessage("Use the HTTPS URL shown by Tailscale Serve.")
                .setView(address)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(
                        "Save and reconnect",
                        (d, w) -> {
                            serverUrl = address.getText().toString().trim();
                            save();
                            syncNow();
                        })
                .show();
    }

    void logout() {
        if (prefs.getBoolean("pendingName", false) || prefs.getBoolean("pendingPhoto", false)) {
            toast("Sync queued profile changes before signing out.");
            return;
        }
        for (int i = 0; i < games.length(); i++) {
            JSONObject game = games.optJSONObject(i);
            if (game != null
                    && ("queued".equals(game.optString("status"))
                            || !game.optString("pendingAction").isEmpty())) {
                toast("Sync queued match changes before signing out.");
                return;
            }
        }
        String oldToken = token, base = serverUrl;
        token = uid = sessionName = "";
        isAdmin = showAdminPanel = false;
        page = 0;
        matches = new JSONArray();
        games = new JSONArray();
        seasonData = new JSONObject();
        ratingHistory = new JSONArray();
        viewedProfileUid = "";
        playingNow = new JSONArray();
        hasTableStatus = false;
        players.clear();
        members.clear();
        stopService(new Intent(this, MatchInboxService.class));
        save();
        show();
        io.execute(
                () -> {
                    try {
                        new OfficeApi(base).post("/auth/logout", oldToken, new JSONObject());
                    } catch (Exception ignored) {
                        // This phone has already signed out.
                    }
                });
    }

    void render() {
        switch (page) {
            case 0:
                home();
                break;
            case 1:
                matchesPage();
                break;
            case 2:
                playersPage();
                break;
            case 3:
                tablePage();
                break;
            default:
                statsPage();
        }
    }

    void title(String e, String h, String s) {
        TextView a = text(e.toUpperCase(Locale.ROOT), 10, MUTED, true);
        a.setLetterSpacing(.13f);
        content.addView(a);
        TextView b = text(h, 27, INK, true);
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.topMargin = dp(5);
        content.addView(b, p);
        TextView c = text(s, 13, MUTED, false);
        LinearLayout.LayoutParams q = lp(-1, -2);
        q.topMargin = dp(5);
        q.bottomMargin = dp(20);
        content.addView(c, q);
    }

    LinearLayout card() {
        return card(content);
    }

    LinearLayout card(LinearLayout parent) {
        LinearLayout c = vertical();
        pad(c, 18, 17, 18, 17);
        c.setBackground(shape(CARD, 20));
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.bottomMargin = dp(12);
        parent.addView(c, p);
        return c;
    }

    TextView lineLabel(String s) {
        TextView v = text(s, 11, MUTED, true);
        v.setLetterSpacing(.06f);
        return v;
    }

    void home() {
        String day =
                new SimpleDateFormat("EEEE, 'OFFICE LEAGUE'", Locale.getDefault())
                        .format(new Date())
                        .toUpperCase(Locale.getDefault());
        title(day, "Your cue, your call.", "A little friendly competition goes a long way.");
        renderPoolTable();
        renderMyNextMatch();
        renderHomeInvitations();
        TextView challenge = button("Challenge a player to 1v1");
        challenge.setOnClickListener(v -> addMatch());
        LinearLayout.LayoutParams challengeLayout = lp(-1, -2);
        challengeLayout.bottomMargin = dp(10);
        content.addView(challenge, challengeLayout);
        renderMatchAlertsStatus();
        if (isAdmin) {
            TextView admin = button("Manage office league");
            admin.setOnClickListener(
                    v -> {
                        showAdminPanel = true;
                        show();
                    });
            LinearLayout.LayoutParams ap = lp(-1, -2);
            ap.bottomMargin = dp(14);
            content.addView(admin, ap);
        }
        renderPublicSeason();
        if (members.isEmpty()) setupCard();
        LinearLayout hero = vertical();
        pad(hero, 20, 20, 20, 20);
        hero.setBackground(shape(featuredPanel, 23));
        LinearLayout r = horizontal();
        r.addView(
                text("YOUR SEASON", 11, 0xffB8C7BE, true), new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(
                text(
                        online ? "●  LIVE SYNC" : "●  SAVED HERE",
                        10,
                        online ? GREEN : 0xffffc570,
                        true));
        hero.addView(r);
        TextView n = text("" + wins(currentName()), 54, Color.WHITE, true);
        LinearLayout.LayoutParams np = lp(-1, -2);
        np.topMargin = dp(14);
        hero.addView(n, np);
        hero.addView(text("WINS ON THE BOARD", 10, 0xffB8C7BE, true));
        LinearLayout stats = horizontal();
        stats.addView(
                miniStat("LOSSES", "" + losses(currentName())),
                new LinearLayout.LayoutParams(0, -2, 1));
        stats.addView(
                miniStat("WIN RATE", rate(currentName())), new LinearLayout.LayoutParams(0, -2, 1));
        stats.addView(
                miniStat("PLAYED", "" + (wins(currentName()) + losses(currentName()))),
                new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams st = lp(-1, -2);
        st.topMargin = dp(20);
        hero.addView(stats, st);
        LinearLayout.LayoutParams hp = lp(-1, -2);
        hp.bottomMargin = dp(14);
        content.addView(hero, hp);
        sectionHeading(
                "Recent matches",
                "See all",
                () -> {
                    page = 1;
                    show();
                });
        if (matches.length() == 0)
            emptyCard("No games played yet", "Challenge a coworker to play.");
        else
            for (int i = 0; i < Math.min(3, matches.length()); i++)
                matchCard(matches.optJSONObject(i));
        LinearLayout c = card();
        c.setBackground(shape(tone(0xffEAF4DF, 0xff37483C), 18));
        c.addView(lineLabel("THE TABLE TALK"));
        TextView t =
                text(
                        "Every game counts. Keep the rivalry friendly and the chalk close.",
                        14,
                        INK,
                        true);
        LinearLayout.LayoutParams tp = lp(-1, -2);
        tp.topMargin = dp(8);
        c.addView(t, tp);
    }

    void setupCard() {
        LinearLayout c = card();
        c.setBackground(shape(tone(0xfffff3dc, 0xff484239), 18));
        c.addView(lineLabel("PC-HOSTED LEAGUE"));
        TextView t =
                text(
                        "Your profile and results are saved on this phone until the office PC is"
                                + " connected. They will sync when the server is reachable.",
                        13,
                        INK,
                        false);
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.topMargin = dp(8);
        c.addView(t, p);
    }

    LinearLayout miniStat(String l, String n) {
        LinearLayout x = vertical();
        x.addView(text(n, 20, Color.WHITE, true));
        TextView t = text(l, 9, 0xffB8C7BE, true);
        t.setMaxLines(1);
        LinearLayout.LayoutParams p = lp(-2, -2);
        p.topMargin = dp(4);
        x.addView(t, p);
        return x;
    }

    String rate(String p) {
        int n = wins(p) + losses(p);
        return n == 0 ? "—" : Math.round(wins(p) * 100f / n) + "%";
    }

    void sectionHeading(String a, String b, Runnable run) {
        LinearLayout r = horizontal();
        r.addView(text(a, 17, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView t = text(b + "  →", 12, MUTED, true);
        r.addView(t);
        t.setOnClickListener(v -> run.run());
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.topMargin = dp(22);
        p.bottomMargin = dp(12);
        content.addView(r, p);
    }

    void emptyCard(String a, String b) {
        LinearLayout c = card();
        c.setGravity(Gravity.CENTER);
        c.addView(text(a, 16, INK, true));
        TextView t = text(b, 12, MUTED, false);
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.topMargin = dp(5);
        c.addView(t, p);
    }

    String currentName() {
        return sessionName.isEmpty() ? "You" : sessionName;
    }

    OfficeMember memberByName(String name) {
        for (OfficeMember m : members) if (m.name.equals(name)) return m;
        return null;
    }

    OfficeMember memberByUid(String id) {
        for (OfficeMember m : members) if (m.uid.equals(id)) return m;
        return null;
    }

    int wins(String name) {
        int n = 0;
        OfficeMember target = memberByName(name);
        String targetId =
                (name.equals(currentName()) && !uid.isEmpty())
                        ? uid
                        : (target == null ? "" : target.uid);
        for (int i = 0; i < matches.length(); i++) {
            JSONObject m = matches.optJSONObject(i);
            if (m == null) continue;
            if (!targetId.isEmpty()) {
                if (targetId.equals(m.optString("winnerUid"))) n++;
            } else {
                boolean aw = m.optBoolean("win", true);
                if (aw && name.equals(m.optString("a")) || !aw && name.equals(m.optString("b")))
                    n++;
            }
        }
        return n;
    }

    int losses(String name) {
        int n = 0;
        OfficeMember target = memberByName(name);
        String targetId =
                (name.equals(currentName()) && !uid.isEmpty())
                        ? uid
                        : (target == null ? "" : target.uid);
        for (int i = 0; i < matches.length(); i++) {
            JSONObject m = matches.optJSONObject(i);
            if (m == null) continue;
            if (!targetId.isEmpty()) {
                boolean player =
                        targetId.equals(m.optString("playerAUid"))
                                || targetId.equals(m.optString("playerBUid"));
                if (player && !targetId.equals(m.optString("winnerUid"))) n++;
            } else {
                boolean aw = m.optBoolean("win", true);
                if (!aw && name.equals(m.optString("a")) || aw && name.equals(m.optString("b")))
                    n++;
            }
        }
        return n;
    }

    void matchCard(JSONObject m) {
        if (m == null) return;
        LinearLayout c = card();
        LinearLayout r = horizontal();
        String a = m.optString("a"), b = m.optString("b");
        r.addView(text(a + "   vs   " + b, 14, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        String chip = m.optBoolean("win", true) ? "WIN" : "LOSS";
        if (!uid.isEmpty()) {
            String winner = m.optString("winnerUid");
            if (uid.equals(m.optString("playerAUid")) || uid.equals(m.optString("playerBUid")))
                chip = uid.equals(winner) ? "WIN" : "LOSS";
            else chip = "WON: " + (winner.equals(m.optString("playerAUid")) ? a : b);
        }
        TextView res =
                text(
                        chip,
                        10,
                        chip.startsWith("WIN")
                                ? tone(0xff3D7134, 0xffC1EAAE)
                                : tone(0xff8B4639, 0xffFFD0C6),
                        true);
        pad(res, 10, 6, 10, 6);
        res.setBackground(
                shape(
                        chip.startsWith("WIN")
                                ? tone(0xffEAF4DF, 0xff37483C)
                                : tone(0xffF8E9E5, 0xff503D3C),
                        9));
        r.addView(res);
        c.addView(r);
        TextView d =
                text(
                        (m.optBoolean("isLeagueMatch") ? "League · " : "1v1 · ")
                                + m.optString("date", "Today"),
                        11,
                        MUTED,
                        false);
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.topMargin = dp(9);
        c.addView(d, p);
        long seconds = m.optLong("durationSeconds", -1);
        if (seconds >= 0) c.addView(text("Duration · " + formatDuration(seconds), 12, INK, true));
    }

    void matchesPage() {
        title(
                "OFFICE SCOREBOOK",
                "Results",
                online
                        ? "Confirmed matches from across the office."
                        : "Offline · showing results from your last sync.");

        renderOpenGames();

        LinearLayout filters = horizontal();
        ResultFilter[] options = {ResultFilter.ALL, ResultFilter.LEAGUE, ResultFilter.ONE_V_ONE};
        String[] labels = {"All results", "League", "1v1"};
        for (int i = 0; i < options.length; i++) {
            ResultFilter option = options[i];
            boolean selected = resultFilter == option;
            TextView filter = text(labels[i], 13, selected ? Color.WHITE : INK, true);
            filter.setGravity(Gravity.CENTER);
            filter.setBackground(
                    shape(selected ? featuredPanel : tone(0xffE8EDE6, 0xff41474A), 14));
            filter.setSelected(selected);
            LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(0, dp(44), 1);
            if (i > 0) placement.leftMargin = dp(6);
            filters.addView(filter, placement);
            filter.setOnClickListener(
                    v -> {
                        if (resultFilter == option) return;
                        resultFilter = option;
                        show();
                    });
        }
        content.addView(filters, lp(-1, -2));

        ArrayList<JSONObject> results = new ArrayList<>();
        for (int i = 0; i < matches.length(); i++) {
            JSONObject match = matches.optJSONObject(i);
            if (match == null || !match.optBoolean("synced", true)) continue;
            boolean league = match.optBoolean("isLeagueMatch");
            if (resultFilter == ResultFilter.ALL
                    || league == (resultFilter == ResultFilter.LEAGUE)) {
                results.add(match);
            }
        }
        LinearLayout.LayoutParams section = lp(-1, -2);
        section.topMargin = dp(16);
        section.bottomMargin = dp(8);
        content.addView(
                lineLabel(
                        results.size() == 1
                                ? "1 CONFIRMED RESULT"
                                : results.size() + " CONFIRMED RESULTS"),
                section);
        if (results.isEmpty()) {
            emptyCard("No results yet", "Confirmed matches will appear here.");
        } else {
            for (JSONObject match : results) sharedResultCard(match);
        }
        TextView add = button("Challenge a player to 1v1");
        add.setOnClickListener(v -> addMatch());
        content.addView(add, lp(-1, -2));
    }

    private void sharedResultCard(JSONObject match) {
        LinearLayout card = card();
        LinearLayout meta = horizontal();
        boolean league = match.optBoolean("isLeagueMatch");
        TextView type = text(league ? "LEAGUE" : "1V1", 10, tone(0xff315944, 0xffCBEDD0), true);
        pad(type, 8, 4, 8, 4);
        type.setBackground(
                shape(league ? tone(0xffE0EFD9, 0xff415744) : tone(0xffEDF0EC, 0xff444C4B), 8));
        meta.addView(type);
        TextView date = text(resultDate(match), 11, MUTED, false);
        date.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        meta.addView(date, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(meta, lp(-1, -2));

        String a = match.optString("a"), b = match.optString("b");
        String winnerUid = match.optString("winnerUid");
        boolean aWon =
                winnerUid.isEmpty()
                        ? match.optBoolean("win")
                        : winnerUid.equals(match.optString("playerAUid"));
        TextView players = text(a + "  vs  " + b, 16, INK, true);
        LinearLayout.LayoutParams playerLayout = lp(-1, -2);
        playerLayout.topMargin = dp(9);
        card.addView(players, playerLayout);
        LinearLayout footer = horizontal();
        footer.addView(
                text("Winner · " + (aWon ? a : b), 12, tone(0xff285625, 0xffC5E7AF), true),
                new LinearLayout.LayoutParams(0, -2, 1));
        long seconds = match.optLong("durationSeconds", -1);
        if (seconds >= 0) {
            TextView duration = text(formatDuration(seconds), 12, INK, true);
            duration.setContentDescription("Duration " + formatDuration(seconds));
            pad(duration, 8, 0, 0, 0);
            footer.addView(duration);
        }
        LinearLayout.LayoutParams footerLayout = lp(-1, -2);
        footerLayout.topMargin = dp(5);
        card.addView(footer, footerLayout);
        int change = match.optInt("ratingChange", -1);
        if (change >= 0) {
            card.addView(
                    text("Elo · winner +" + change + "  /  opponent −" + change, 12, MUTED, false));
        }
    }

    private String resultDate(JSONObject match) {
        try {
            return java.text.DateFormat.getDateTimeInstance(
                            java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                    .format(new Date(parseServerTime(match.optString("createdAt"))));
        } catch (java.text.ParseException e) {
            return match.optString("date");
        }
    }

    private void renderPoolTable() {
        LinearLayout table = card();
        table.addView(lineLabel("POOL TABLE"));
        poolTableStatus = text("", 19, INK, true);
        table.addView(poolTableStatus, lp(-1, -2));
        updatePoolTableStatus();
        if (!online || !hasTableStatus) return;
        for (int i = 0; i < playingNow.length(); i++) {
            JSONObject game = playingNow.optJSONObject(i);
            if (game == null) continue;
            LinearLayout players = horizontal();
            for (String side : new String[] {"A", "B"}) {
                String name = game.optString("player" + side + "Name");
                OfficeMember player = memberByUid(game.optString("player" + side + "Uid"));
                FrameLayout photo = avatar(name, player);
                linkProfilePhoto(photo, player);
                players.addView(photo, lp(40, 40));
                TextView label = text(name, 13, INK, true);
                pad(label, 6, 0, 6, 0);
                players.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            }
            LinearLayout.LayoutParams position = lp(-1, -2);
            position.topMargin = dp(10);
            table.addView(players, position);
        }
    }

    private void updatePoolTableStatus() {
        if (poolTableStatus == null) return;
        if (!online || !hasTableStatus) {
            poolTableStatus.setText("Availability unknown");
            poolTableStatus.setTextColor(MUTED);
        } else if (playingNow.length() == 0) {
            poolTableStatus.setText("Available");
            poolTableStatus.setTextColor(tone(0xff285625, 0xffC5E7AF));
        } else {
            poolTableStatus.setText("In use · Playing now");
            poolTableStatus.setTextColor(tone(0xff8B4639, 0xffFFD0C6));
        }
    }

    private void renderMyNextMatch() {
        JSONObject tournament = seasonData.optJSONObject("tournament");
        if (tournament == null || !"active".equals(tournament.optString("status"))) return;
        JSONArray bracket = tournament.optJSONArray("matches");
        if (bracket == null) return;
        for (int i = 0; i < bracket.length(); i++) {
            JSONObject match = bracket.optJSONObject(i);
            if (match == null) continue;
            String a = match.optString("playerAUid"), b = match.optString("playerBUid");
            if (a.isEmpty() || b.isEmpty() || (!uid.equals(a) && !uid.equals(b))) continue;
            String state = match.optString("status");
            if (!state.equals("pending") && !state.equals("in_progress")) continue;
            boolean opponentIsB = uid.equals(a);
            String opponentId = opponentIsB ? b : a;
            String opponentName = bracketPlayer(match, opponentIsB ? "playerBName" : "playerAName");
            JSONObject game = findGame(match.optString("gameId"));

            LinearLayout panel = card();
            panel.addView(lineLabel("MY NEXT LEAGUE MATCH"));
            LinearLayout opponent = horizontal();
            OfficeMember opponentMember = memberByUid(opponentId);
            FrameLayout photo = avatar(opponentName, opponentMember);
            linkProfilePhoto(photo, opponentMember);
            opponent.addView(photo, lp(48, 48));
            LinearLayout details = vertical();
            pad(details, 12, 0, 0, 0);
            details.addView(text(opponentName, 17, INK, true));
            details.addView(text(bracketStatus(match, "active"), 12, MUTED, false));
            opponent.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams opponentLayout = lp(-1, -2);
            opponentLayout.topMargin = dp(8);
            panel.addView(opponent, opponentLayout);
            if (state.equals("pending")) {
                addHomeMatchButton(
                        panel, "Play match", () -> confirmLeagueInvite(match, opponentName));
            } else if (game != null
                    && "invited".equals(game.optString("status"))
                    && uid.equals(game.optString("playerBUid"))) {
                addInviteActions(panel, game.optString("id"), () -> {});
            } else if (isOwnLeagueGame(game) && isTimingMatch(game.optString("status"))) {
                String reportKey =
                        uid.equals(game.optString("playerAUid"))
                                ? "reportAWinnerUid"
                                : "reportBWinnerUid";
                boolean needsConfirmation =
                        "awaiting_confirmation".equals(game.optString("status"))
                                && game.optString(reportKey).isEmpty();
                addHomeMatchButton(
                        panel,
                        needsConfirmation ? "Confirm winner" : "Open match timer",
                        () -> {
                            if (needsConfirmation) reportWinnerDialog(game);
                            else openMatchTimer(game);
                        });
            }
            return;
        }
    }

    private void addHomeMatchButton(LinearLayout panel, String label, Runnable action) {
        TextView control = button(label);
        LinearLayout.LayoutParams placement = lp(-1, -2);
        placement.topMargin = dp(10);
        panel.addView(control, placement);
        control.setOnClickListener(v -> action.run());
    }

    private void renderHomeInvitations() {
        boolean headingAdded = false;
        for (int i = 0; i < games.length(); i++) {
            JSONObject game = games.optJSONObject(i);
            if (game == null
                    || !"invited".equals(game.optString("status"))
                    || !uid.equals(game.optString("playerBUid"))
                    || !game.optString("tournamentMatchId").isEmpty()) continue;
            if (!headingAdded) {
                content.addView(lineLabel("INCOMING 1V1 INVITATIONS"));
                headingAdded = true;
            }
            gameCard(content, game);
        }
    }

    private void renderMatchAlertsStatus() {
        matchAlertsStatus = text("", 12, INK, true);
        matchAlertsStatus.setGravity(Gravity.CENTER_VERTICAL);
        matchAlertsStatus.setMinimumHeight(dp(48));
        matchAlertsStatus.setOnClickListener(v -> openMatchAlertSettings());
        content.addView(matchAlertsStatus, lp(-1, -2));
        updateMatchAlertsStatus();
    }

    private void updateMatchAlertsStatus() {
        if (matchAlertsStatus == null) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        boolean allowed =
                manager != null
                        && (Build.VERSION.SDK_INT < 24 || manager.areNotificationsEnabled());
        if (allowed && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = manager.getNotificationChannel(MatchInboxService.CHANNEL);
            allowed =
                    channel == null
                            || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        matchAlertsStatus.setText(
                allowed
                        ? "Match alerts enabled · Settings"
                        : "Match alerts off · Enable in settings");
    }

    private void openMatchAlertSettings() {
        Intent settings;
        if (Build.VERSION.SDK_INT >= 26) {
            settings =
                    new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(
                                    android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
        } else {
            settings =
                    new Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null));
        }
        startActivity(settings);
    }

    void renderPublicSeason() {
        if (!seasonData.has("id")) return;
        JSONObject tournament = seasonData.optJSONObject("tournament");
        if (tournament == null) return;

        LinearLayout summary = card();
        summary.addView(lineLabel("OFFICE LEAGUE"));
        summary.addView(text(seasonData.optString("name", "Office League"), 20, INK, true));
        String state = tournament.optString("status");
        long deadline = seasonData.optLong("deadlineMs");
        String detail =
                state.equals("completed")
                        ? "Champion · " + tournament.optString("championName")
                        : state.equals("cancelled")
                                ? "League closed"
                                : deadline > 0
                                        ? "Ends " + leagueDate(deadline - 1)
                                        : "Single elimination · winners advance";
        summary.addView(text(detail, 13, MUTED, false));

        JSONArray matches = tournament.optJSONArray("matches");
        if (matches == null || matches.length() == 0) return;
        int rounds = tournament.optInt("roundCount", 1);
        int firstRoundSlots = 0;
        for (int i = 0; i < matches.length(); i++) {
            JSONObject match = matches.optJSONObject(i);
            if (match != null && match.optInt("round") == 1) firstRoundSlots++;
        }

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(rounds > 1);
        FrameLayout tree = new FrameLayout(this);
        int column = dp(206);
        int gap = dp(24);
        int row = dp(146);
        int matchHeight = dp(132);
        int header = dp(36);
        int width = rounds * column + (rounds - 1) * gap;
        int height = header + firstRoundSlots * row;
        Paint connectorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        connectorPaint.setColor(tone(0xff8DAC97, 0xff89AB91));
        connectorPaint.setStrokeWidth(dp(2));
        View connectors =
                new View(this) {
                    @Override
                    protected void onDraw(Canvas canvas) {
                        for (int round = 1; round < rounds; round++) {
                            int slots = firstRoundSlotsFor(rounds, round);
                            for (int slot = 0; slot < slots; slot++) {
                                float x = (round - 1) * (column + gap) + column;
                                float y = bracketCenter(header, row, round, slot);
                                float nextY = bracketCenter(header, row, round + 1, slot / 2);
                                canvas.drawLine(x, y, x + gap / 2f, y, connectorPaint);
                                canvas.drawLine(
                                        x + gap / 2f, y, x + gap / 2f, nextY, connectorPaint);
                                canvas.drawLine(
                                        x + gap / 2f, nextY, x + gap, nextY, connectorPaint);
                            }
                        }
                    }
                };
        tree.addView(connectors, new FrameLayout.LayoutParams(width, height));
        for (int round = 1; round <= rounds; round++) {
            FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(column, header);
            position.leftMargin = (round - 1) * (column + gap);
            TextView heading =
                    text(round == rounds ? "FINAL" : "ROUND " + round, 11, Color.WHITE, true);
            heading.setLetterSpacing(.08f);
            heading.setBackground(shape(featuredPanel, 10));
            heading.setGravity(Gravity.CENTER);
            position.height = dp(28);
            tree.addView(heading, position);
        }
        for (int i = 0; i < matches.length(); i++) {
            JSONObject match = matches.optJSONObject(i);
            if (match == null) continue;
            int round = match.optInt("round");
            if (round < 1 || round > rounds) continue;
            FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(column, matchHeight);
            position.leftMargin = (round - 1) * (column + gap);
            position.topMargin =
                    bracketCenter(header, row, round, match.optInt("slot")) - matchHeight / 2;
            LinearLayout tile = vertical();
            pad(tile, 8, 7, 8, 7);
            tile.setGravity(Gravity.CENTER);
            String playerA = match.optString("playerAUid");
            String playerB = match.optString("playerBUid");
            String winner = match.optString("winnerUid");
            boolean yours = uid.equals(playerA) || uid.equals(playerB);
            boolean highlighted = yours && state.equals("active");
            GradientDrawable background =
                    shape(
                            highlighted
                                    ? tone(0xffEBF5E2, 0xff3B4B3C)
                                    : tone(0xffF5F7F3, 0xff383E41),
                            16);
            background.setStroke(
                    dp(1),
                    highlighted ? tone(0xff79A65D, 0xffA2CA84) : tone(0xffDAE4D9, 0xff54635A));
            tile.setBackground(background);

            LinearLayout pairing = horizontal();
            pairing.setGravity(Gravity.CENTER);
            LinearLayout profileA = bracketProfile(match, "A", playerA, winner);
            LinearLayout profileB = bracketProfile(match, "B", playerB, winner);
            pairing.addView(profileA, new LinearLayout.LayoutParams(0, -2, 1));
            TextView versus = text("VS", 9, tone(0xff526B5B, 0xffC1CFC4), true);
            versus.setGravity(Gravity.CENTER);
            versus.setBackground(shape(tone(0xffE2EBE0, 0xff4B5B50), 20));
            pairing.addView(versus, lp(24, 24));
            pairing.addView(profileB, new LinearLayout.LayoutParams(0, -2, 1));
            tile.addView(pairing, lp(-1, -2));

            String matchStatus = match.optString("status");
            boolean readyToPlay =
                    highlighted
                            && matchStatus.equals("pending")
                            && !playerA.isEmpty()
                            && !playerB.isEmpty();
            if (readyToPlay) {
                boolean opponentIsB = uid.equals(playerA);
                LinearLayout opponent = opponentIsB ? profileB : profileA;
                String opponentName =
                        bracketPlayer(match, opponentIsB ? "playerBName" : "playerAName");
                opponent.setContentDescription("Play match against " + opponentName);
                opponent.setOnClickListener(v -> confirmLeagueInvite(match, opponentName));
            } else {
                linkProfilePhoto(profileA, memberByUid(playerA));
                linkProfilePhoto(profileB, memberByUid(playerB));
            }
            String detailLabel =
                    matchStatus.equals("completed")
                            ? "Winner · " + firstName(match.optString("winnerName"))
                            : readyToPlay ? "Tap opponent to play" : bracketStatus(match, state);
            TextView status =
                    text(
                            detailLabel,
                            11,
                            highlighted
                                    ? tone(0xff285625, 0xffC5E7AF)
                                    : tone(0xff526B5B, 0xffC1CFC4),
                            true);
            status.setGravity(Gravity.CENTER);
            status.setMaxLines(1);
            status.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams statusLayout = lp(-1, -2);
            statusLayout.topMargin = dp(5);
            tile.addView(status, statusLayout);
            tile.setOnClickListener(v -> openBracketMatch(match, state, yours));
            tree.addView(tile, position);
        }
        scroll.addView(tree, new FrameLayout.LayoutParams(width, height));
        summary.addView(scroll, lp(-1, -2));
    }

    private LinearLayout bracketProfile(
            JSONObject match, String side, String playerUid, String winnerUid) {
        String fullName = match.optString("player" + side + "Name").trim();
        String name =
                fullName.isEmpty()
                        ? bracketPlayer(match, "player" + side + "Name")
                        : firstName(fullName);
        boolean winner = !playerUid.isEmpty() && playerUid.equals(winnerUid);
        LinearLayout profile = vertical();
        profile.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout ring = new FrameLayout(this);
        GradientDrawable outline =
                shape(winner ? tone(0xff78B44C, 0xffB5E18D) : tone(0xffD7E4D6, 0xff5D7064), 50);
        outline.setShape(GradientDrawable.OVAL);
        ring.setBackground(outline);
        FrameLayout portrait = avatar(fullName.isEmpty() ? "?" : fullName, memberByUid(playerUid));
        if (fullName.isEmpty() && match.optString("status").equals("bye")) {
            ((TextView) portrait.getChildAt(0)).setText("–");
        }
        ring.addView(portrait, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER));
        profile.addView(ring, lp(50, 50));

        TextView label = text(name, 13, INK, true);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine();
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setContentDescription(fullName.isEmpty() ? name : fullName);
        LinearLayout.LayoutParams nameLayout = lp(-1, -2);
        nameLayout.topMargin = dp(4);
        profile.addView(label, nameLayout);
        return profile;
    }

    private String firstName(String name) {
        return name.trim().split("\\s+", 2)[0];
    }

    int firstRoundSlotsFor(int totalRounds, int round) {
        return 1 << (totalRounds - round);
    }

    int bracketCenter(int header, int row, int round, int slot) {
        int span = 1 << (round - 1);
        return header + row * span * slot + row * span / 2;
    }

    String bracketPlayer(JSONObject match, String key) {
        String name = match.optString(key);
        if (!name.isEmpty()) return name;
        return match.optString("status").equals("bye") ? "Bye" : "To be decided";
    }

    String bracketStatus(JSONObject match, String tournamentState) {
        String state = match.optString("status");
        if (state.equals("bye")) return "Advances with a bye";
        if (state.equals("completed")) return "Winner · " + match.optString("winnerName");
        if (!tournamentState.equals("active")) return "Closed";
        if (state.equals("pending")) return "Ready to play";
        switch (match.optString("gameStatus")) {
            case "invited":
                return "Waiting for acceptance";
            case "active":
                return "Match in progress";
            case "awaiting_confirmation":
                return "Waiting for result confirmation";
            case "disputed":
                return "Result under review";
            default:
                return "Waiting for previous round";
        }
    }

    void openBracketMatch(JSONObject match, String tournamentState, boolean yours) {
        String pairing =
                bracketPlayer(match, "playerAName") + " vs " + bracketPlayer(match, "playerBName");
        JSONObject game = findGame(match.optString("gameId"));
        if (isOwnLeagueGame(game)
                && (isTimingMatch(game.optString("status"))
                        || game.optString("status").equals("confirmed"))) {
            openMatchTimer(game);
            return;
        }
        AlertDialog.Builder dialog =
                new AlertDialog.Builder(this).setTitle(pairing).setNegativeButton("Close", null);
        if (tournamentState.equals("active")
                && game != null
                && yours
                && match.optString("status").equals("in_progress")) {
            LinearLayout details = vertical();
            pad(details, 14, 0, 14, 0);
            AlertDialog matchDialog = dialog.setView(details).create();
            gameCard(details, game, matchDialog::dismiss);
            matchDialog.show();
            return;
        }
        dialog.setMessage(bracketStatus(match, tournamentState)).show();
    }

    private void confirmLeagueInvite(JSONObject match, String opponentName) {
        new AlertDialog.Builder(this)
                .setTitle("Play match?")
                .setMessage(
                        "Send an invitation to "
                                + opponentName
                                + ". The match starts when they accept.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Play match", (dialog, which) -> createTournamentInvite(match))
                .show();
    }

    String leagueDate(long timestamp) {
        return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                .format(new Date(timestamp));
    }

    void renderOpenGames() {
        boolean heading = false;
        for (int i = 0; i < games.length(); i++) {
            JSONObject g = games.optJSONObject(i);
            if (g == null || !g.optString("tournamentMatchId").isEmpty()) continue;
            String state = g.optString("status");
            if (!(state.equals("queued")
                    || state.equals("invited")
                    || state.equals("active")
                    || state.equals("awaiting_confirmation")
                    || state.equals("disputed"))) continue;
            if (!heading) {
                LinearLayout.LayoutParams hp = lp(-1, -2);
                hp.topMargin = dp(16);
                content.addView(lineLabel("1V1 CHALLENGES & LIVE GAMES"), hp);
                heading = true;
            }
            gameCard(content, g);
        }
    }

    void gameCard(LinearLayout parent, JSONObject g) {
        gameCard(parent, g, () -> {});
    }

    void gameCard(LinearLayout parent, JSONObject g, Runnable closeMatchDialog) {
        String a = g.optString("playerAName", "Player A"),
                b = g.optString("playerBName", "Player B"),
                state = g.optString("status"),
                gameId = g.optString("id");
        LinearLayout c = card(parent);
        c.addView(text(a + "  vs  " + b, 14, INK, true));
        String detail;
        if (state.equals("queued")) detail = "Saved on this phone · invite sends when online";
        else if (state.equals("invited")) {
            if (uid.equals(g.optString("playerBUid")))
                detail = a + " challenged you · accept to start the timer";
            else detail = "Waiting for " + b + " to accept";
        } else if (state.equals("active")) detail = "Match in progress";
        else if (state.equals("awaiting_confirmation")) {
            String myReport =
                    uid.equals(g.optString("playerAUid"))
                            ? g.optString("reportAWinnerUid")
                            : g.optString("reportBWinnerUid");
            detail =
                    myReport.isEmpty()
                            ? "Opponent reported "
                                    + g.optString("winnerName", "a winner")
                                    + " · confirm or dispute"
                            : "Waiting for your opponent to confirm the result";
        } else if (state.equals("disputed"))
            detail =
                    isAdmin
                            ? "Players reported different winners · admin decision needed"
                            : "Result disputed · waiting for admin review";
        else detail = "";
        LinearLayout.LayoutParams dp1 = lp(-1, -2);
        dp1.topMargin = dp(6);
        c.addView(text(detail, 12, MUTED, false), dp1);
        if (state.equals("invited") && uid.equals(g.optString("playerBUid"))) {
            addInviteActions(c, gameId, closeMatchDialog);
        }
        if ((state.equals("active") || state.equals("awaiting_confirmation"))
                && (uid.equals(g.optString("playerAUid"))
                        || uid.equals(g.optString("playerBUid")))) {
            TextView result =
                    button(
                            state.equals("active")
                                    ? "Open match timer"
                                    : "Confirm or dispute result");
            LinearLayout.LayoutParams rp = lp(-1, -2);
            rp.topMargin = dp(10);
            c.addView(result, rp);
            result.setOnClickListener(
                    v -> {
                        closeMatchDialog.run();
                        if (state.equals("active") && isOwnStartedGame(g)) openMatchTimer(g);
                        else reportWinnerDialog(g);
                    });
        }
        if (state.equals("disputed") && isAdmin) {
            TextView resolve = button("Resolve result");
            LinearLayout.LayoutParams rp = lp(-1, -2);
            rp.topMargin = dp(10);
            c.addView(resolve, rp);
            resolve.setOnClickListener(
                    v -> {
                        closeMatchDialog.run();
                        resolveDisputeDialog(g);
                    });
        }
    }

    private void addInviteActions(LinearLayout c, String gameId, Runnable closeMatchDialog) {
        LinearLayout actions = horizontal();
        TextView accept = button("Accept · start timer");
        actions.addView(accept, new LinearLayout.LayoutParams(0, -2, 1));
        accept.setOnClickListener(
                v -> {
                    closeMatchDialog.run();
                    gameAction(gameId, "respond", boolPayload("accept", true));
                });
        TextView decline = button("Decline");
        LinearLayout.LayoutParams dl = lp(-2, -2);
        dl.leftMargin = dp(8);
        actions.addView(decline, dl);
        decline.setOnClickListener(
                v -> {
                    closeMatchDialog.run();
                    gameAction(gameId, "respond", boolPayload("accept", false));
                });
        LinearLayout.LayoutParams ap = lp(-1, -2);
        ap.topMargin = dp(10);
        c.addView(actions, ap);
    }

    private long parseServerTime(String value) throws java.text.ParseException {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        format.setLenient(false);
        return format.parse(value).getTime();
    }

    String elapsed(String started, String finished) {
        try {
            long from = parseServerTime(started);
            long to =
                    finished.isEmpty()
                            ? (hasServerClock
                                    ? SystemClock.elapsedRealtime() + serverClockOffsetMs
                                    : System.currentTimeMillis())
                            : parseServerTime(finished);
            return formatDuration(Math.max(0, (to - from) / 1000));
        } catch (java.text.ParseException e) {
            return "—";
        }
    }

    private String formatDuration(long seconds) {
        return String.format(
                Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60);
    }

    void addMatch() {
        ArrayList<OfficeMember> choices = new ArrayList<>();
        for (OfficeMember m : members) if (!m.uid.equals(uid)) choices.add(m);
        if (choices.isEmpty()) {
            toast("Coworkers appear here after they create their office profiles.");
            return;
        }
        LinearLayout form = vertical();
        pad(form, 4, 4, 4, 4);
        form.addView(text("CHALLENGE A COWORKER", 11, MUTED, true));
        Spinner spinner = new Spinner(this);
        ArrayList<String> names = new ArrayList<>();
        for (OfficeMember m : choices) names.add(m.name);
        spinner.setAdapter(
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        form.addView(spinner);
        new AlertDialog.Builder(this)
                .setTitle("Send a game challenge")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(
                        "Send invite",
                        (d, w) -> createInvite(choices.get(spinner.getSelectedItemPosition())))
                .show();
    }

    void createInvite(OfficeMember opponent) {
        JSONObject g = new JSONObject();
        try {
            String id = UUID.randomUUID().toString();
            g.put("id", id);
            g.put("playerAUid", uid);
            g.put("playerBUid", opponent.uid);
            g.put("playerAName", currentName());
            g.put("playerBName", opponent.name);
            g.put("initiatedByUid", uid);
            g.put(
                    "createdAt",
                    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(new Date()));
            g.put("status", "queued");
            games.put(g);
            save();
            show();
            if (online) syncNow();
            toast(
                    online
                            ? "Challenge sent. The timer starts when they accept."
                            : "Challenge saved; it will send when the server is back online.");
        } catch (Exception e) {
            toast(errorText(e));
        }
    }

    void createTournamentInvite(JSONObject match) {
        try {
            postServer(
                    "/games/invite",
                    new JSONObject()
                            .put("id", UUID.randomUUID().toString())
                            .put("tournamentMatchId", match.optString("id")),
                    "League invitation sent.");
        } catch (JSONException e) {
            toast(errorText(e));
        }
    }

    void reportWinnerDialog(JSONObject game) {
        String a = game.optString("playerAName"), b = game.optString("playerBName");
        new AlertDialog.Builder(this)
                .setTitle("Who won?")
                .setItems(
                        new String[] {a, b},
                        (d, which) -> {
                            String winner =
                                    which == 0
                                            ? game.optString("playerAUid")
                                            : game.optString("playerBUid");
                            try {
                                gameAction(
                                        game.optString("id"),
                                        "report",
                                        new JSONObject().put("winnerUid", winner));
                            } catch (Exception e) {
                                toast(errorText(e));
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    void resolveDisputeDialog(JSONObject game) {
        String a = game.optString("playerAName"), b = game.optString("playerBName");
        new AlertDialog.Builder(this)
                .setTitle("Confirm official winner")
                .setItems(
                        new String[] {a, b},
                        (d, which) -> {
                            String winner =
                                    which == 0
                                            ? game.optString("playerAUid")
                                            : game.optString("playerBUid");
                            try {
                                postServer(
                                        "/admin/games/" + game.optString("id") + "/resolve",
                                        new JSONObject().put("winnerUid", winner),
                                        "Result resolved and stats updated.");
                            } catch (Exception e) {
                                toast(errorText(e));
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    JSONObject boolPayload(String key, boolean value) {
        JSONObject o = new JSONObject();
        try {
            o.put(key, value);
        } catch (Exception ignored) {
        }
        return o;
    }

    void gameAction(String gameId, String action, JSONObject body) {
        JSONObject local = findGame(gameId);
        if (!online && local != null && !local.optString("tournamentMatchId").isEmpty()) {
            toast("Connect to the office server to update this league match.");
            return;
        }
        if (!online) {
            try {
                if (local != null) {
                    local.put("pendingAction", action);
                    if (action.equals("report"))
                        local.put("pendingWinnerUid", body.optString("winnerUid"));
                    if (action.equals("respond"))
                        local.put("pendingAccept", body.optBoolean("accept"));
                    save();
                    show();
                    toast("Saved offline; this will sync when your PC is back online.");
                }
            } catch (Exception e) {
                toast(errorText(e));
            }
            return;
        }
        postServer(
                "/games/" + gameId + "/" + action,
                body,
                action.equals("respond")
                        ? (body.optBoolean("accept")
                                ? "Game accepted. The timer has started."
                                : "Challenge declined.")
                        : "Your result was sent for confirmation.");
    }

    JSONObject findGame(String id) {
        for (int i = 0; i < games.length(); i++) {
            JSONObject g = games.optJSONObject(i);
            if (g != null && g.optString("id").equals(id)) return g;
        }
        return null;
    }

    void postServer(String path, JSONObject body, String success) {
        if (!online) {
            toast("Connect to the office server to do that.");
            return;
        }
        String base = serverUrl, auth = token;
        io.execute(
                () -> {
                    try {
                        JSONObject snap = new OfficeApi(base).post(path, auth, body);
                        if (!auth.equals(token)) return;
                        JSONArray previousGames = games;
                        mergeSnapshot(snap);
                        for (int i = 0; i < previousGames.length(); i++) {
                            JSONObject queued = previousGames.optJSONObject(i);
                            if (queued == null || !queued.optString("tournamentMatchId").isEmpty())
                                continue;
                            JSONObject remote = findGame(queued.optString("id"));
                            if (remote == null && queued.optString("status").equals("queued")) {
                                games.put(queued);
                            } else if (remote != null
                                    && !queued.optString("pendingAction").isEmpty()) {
                                remote.put("pendingAction", queued.optString("pendingAction"));
                                remote.put(
                                        "pendingWinnerUid", queued.optString("pendingWinnerUid"));
                                remote.put("pendingAccept", queued.optBoolean("pendingAccept"));
                            }
                        }
                        online = true;
                        save();
                        runOnUiThread(
                                () -> {
                                    show();
                                    beginPolling();
                                    toast(success);
                                });
                    } catch (Exception e) {
                        online = e instanceof OfficeApi.ApiException;
                        runOnUiThread(
                                () -> {
                                    updateStatus();
                                    toast(errorText(e));
                                });
                    }
                });
    }

    void adminPage() {
        if (!isAdmin) {
            showAdminPanel = false;
            show();
            return;
        }
        title(
                "LEAGUE ADMIN",
                "Manage office league",
                "Start a league, set its end date, or review disputed results.");
        LinearLayout settings = card();
        boolean active = "active".equals(seasonData.optString("status"));
        if (active) {
            String name = seasonData.optString("name");
            String leagueId = seasonData.optString("id");
            long deadline = seasonData.optLong("deadlineMs");
            settings.addView(text(name, 20, INK, true));
            settings.addView(
                    text(
                            deadline > 0
                                    ? "Ends " + leagueDate(deadline - 1)
                                    : "No scheduled end date",
                            13,
                            MUTED,
                            false));
            TextView end = button("End league now");
            settings.addView(end, lp(-1, -2));
            end.setOnClickListener(
                    v ->
                            new AlertDialog.Builder(this)
                                    .setTitle("End " + name + "?")
                                    .setMessage(
                                            "Any unfinished league matches will close. Completed"
                                                    + " results stay on the bracket.")
                                    .setNegativeButton("Keep playing", null)
                                    .setPositiveButton(
                                            "End league",
                                            (d, w) ->
                                                    postServer(
                                                            "/admin/seasons/" + leagueId + "/end",
                                                            new JSONObject(),
                                                            "League ended."))
                                    .show());
            renderDisputes();
        } else {
            settings.addView(text("Create a league", 20, INK, true));
            settings.addView(
                    text(
                            members.size() + " registered players · Random single-elimination draw",
                            13,
                            MUTED,
                            false));
            settings.addView(lineLabel("LEAGUE NAME"));
            EditText name = input("League name", false);
            name.setText(draftLeagueName);
            name.addTextChangedListener(
                    new android.text.TextWatcher() {
                        @Override
                        public void beforeTextChanged(
                                CharSequence s, int start, int count, int after) {}

                        @Override
                        public void onTextChanged(
                                CharSequence s, int start, int before, int count) {
                            draftLeagueName = s.toString();
                        }

                        @Override
                        public void afterTextChanged(android.text.Editable s) {}
                    });
            settings.addView(name, lp(-1, 52));
            settings.addView(lineLabel("END DATE"));
            TextView date =
                    button(
                            draftLeagueDeadline > 0
                                    ? leagueDate(draftLeagueDeadline - 1)
                                    : "Choose end date");
            settings.addView(date, lp(-1, -2));
            date.setOnClickListener(v -> pickLeagueEnd(date));
            settings.addView(
                    text("The league closes at the end of the selected day.", 12, MUTED, false));
            if (members.size() < 2) {
                settings.addView(
                        text("At least two registered players are needed.", 12, MUTED, false));
            }
            TextView start = button("Start league");
            LinearLayout.LayoutParams startLayout = lp(-1, -2);
            startLayout.topMargin = dp(12);
            settings.addView(start, startLayout);
            start.setEnabled(members.size() >= 2);
            start.setAlpha(start.isEnabled() ? 1f : .5f);
            start.setOnClickListener(
                    v -> {
                        String leagueName = draftLeagueName.trim();
                        if (leagueName.isEmpty() || leagueName.length() > 48) {
                            toast("Enter a league name of 1–48 characters.");
                            return;
                        }
                        if (draftLeagueDeadline <= System.currentTimeMillis()) {
                            toast("Choose an end date for the league.");
                            return;
                        }
                        try {
                            postServer(
                                    "/admin/seasons",
                                    new JSONObject()
                                            .put("name", leagueName)
                                            .put("deadlineMs", draftLeagueDeadline),
                                    "League started. The bracket is on Home.");
                        } catch (JSONException e) {
                            toast(errorText(e));
                        }
                    });
            renderDisputes();
        }
        TextView back = button("Back to Home");
        LinearLayout.LayoutParams bp = lp(-1, -2);
        bp.topMargin = dp(16);
        content.addView(back, bp);
        back.setOnClickListener(
                v -> {
                    showAdminPanel = false;
                    page = 0;
                    show();
                });
    }

    void pickLeagueEnd(TextView button) {
        Calendar selected = Calendar.getInstance();
        if (draftLeagueDeadline > 0) selected.setTimeInMillis(draftLeagueDeadline - 1);
        DatePickerDialog picker =
                new DatePickerDialog(
                        this,
                        (view, year, month, day) -> {
                            Calendar nextDay = Calendar.getInstance();
                            nextDay.clear();
                            nextDay.set(year, month, day);
                            nextDay.add(Calendar.DAY_OF_MONTH, 1);
                            draftLeagueDeadline = nextDay.getTimeInMillis();
                            button.setText(leagueDate(draftLeagueDeadline - 1));
                        },
                        selected.get(Calendar.YEAR),
                        selected.get(Calendar.MONTH),
                        selected.get(Calendar.DAY_OF_MONTH));
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        picker.getDatePicker().setMinDate(today.getTimeInMillis());
        picker.show();
    }

    void renderDisputes() {
        boolean found = false;
        for (int i = 0; i < games.length(); i++) {
            JSONObject g = games.optJSONObject(i);
            if (g == null || !g.optString("status").equals("disputed")) continue;
            if (!found) {
                LinearLayout.LayoutParams hp = lp(-1, -2);
                hp.topMargin = dp(18);
                content.addView(lineLabel("ADMIN · DISPUTED RESULTS"), hp);
                found = true;
            }
            gameCard(content, g);
        }
    }

    void playersPage() {
        title(
                "YOUR OFFICE",
                "Players",
                online
                        ? "Profiles shared with everyone in the league."
                        : "Your saved roster is available offline.");
        TextView edit = button("Edit my profile");
        edit.setOnClickListener(v -> editProfile());
        content.addView(edit, lp(-1, -2));
        sectionHeading("The lineup", members.size() + " players", () -> {});
        if (members.isEmpty()) {
            for (String p : players) playerCard(p, null);
        } else for (OfficeMember m : members) playerCard(m.name, m);
    }

    void playerCard(String name, OfficeMember member) {
        LinearLayout c = card();
        LinearLayout r = horizontal();
        FrameLayout avatar = avatar(name, member);
        linkProfilePhoto(avatar, member);
        r.addView(avatar, lp(48, 48));
        LinearLayout info = vertical();
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1);
        ip.leftMargin = dp(12);
        r.addView(info, ip);
        info.addView(text(name, 15, INK, true));
        info.addView(
                text(
                        wins(name) + "W  ·  " + losses(name) + "L  ·  " + rate(name) + " win rate",
                        11,
                        MUTED,
                        false));
        r.addView(text("✦  " + (wins(name) - losses(name)), 12, INK, true));
        c.addView(r);
    }

    FrameLayout avatar(String name, OfficeMember member) {
        FrameLayout frame = new FrameLayout(this);
        GradientDrawable circle = shape(tone(0xffEAF4DF, 0xff37483C), 50);
        circle.setShape(GradientDrawable.OVAL);
        frame.setBackground(circle);
        frame.setClipToOutline(true);
        TextView initials = text(initials(name), 14, INK, true);
        initials.setGravity(Gravity.CENTER);
        frame.addView(initials, new FrameLayout.LayoutParams(-1, -1));
        if (member != null
                && (avatarFile(member.uid).exists()
                        || pendingPhoto(member.uid) != null
                        || !member.photoPath.isEmpty())) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
            loadAvatar(member, image);
        }
        return frame;
    }

    private File avatarFile(String playerId) {
        return new File(getFilesDir(), "avatar-" + playerId);
    }

    private File pendingPhoto(String playerId) {
        if (!prefs.getBoolean("pendingPhoto", false)
                || !playerId.equals(prefs.getString("pendingPhotoOwner", uid))) return null;
        return new File(
                getFilesDir(), prefs.getString("pendingPhotoFile", "pending-profile-photo"));
    }

    void loadAvatar(OfficeMember member, ImageView view) {
        File cache = avatarFile(member.uid);
        File pending = pendingPhoto(member.uid);
        String base = serverUrl, auth = token;
        String revisionKey = "avatarRevision:" + member.uid;
        boolean canDownload = online && !base.isEmpty() && !member.photoPath.isEmpty();
        if (isDestroyed()) return;
        avatarIo.execute(
                () -> {
                    Bitmap photo = null;
                    try {
                        if (pending != null) photo = decodeAvatar(pending);
                        if (photo == null) photo = decodeAvatar(cache);
                        if (pending == null
                                && canDownload
                                && (photo == null
                                        || !member.photoPath.equals(
                                                prefs.getString(revisionKey, "")))) {
                            File temporary = File.createTempFile("avatar-", ".tmp", getFilesDir());
                            try {
                                new OfficeApi(base)
                                        .download(
                                                "/photos/"
                                                        + URLEncoder.encode(member.uid, "UTF-8")
                                                        + "?revision="
                                                        + URLEncoder.encode(
                                                                member.photoPath, "UTF-8"),
                                                auth,
                                                temporary,
                                                MAX_PHOTO_BYTES);
                                Bitmap fetched = decodeAvatar(temporary);
                                if (fetched != null) {
                                    photo = fetched;
                                    OfficeMember latest = memberByUid(member.uid);
                                    if (latest != null
                                            && latest.photoPath.equals(member.photoPath)
                                            && temporary.renameTo(cache)) {
                                        prefs.edit()
                                                .putString(revisionKey, member.photoPath)
                                                .apply();
                                    }
                                }
                            } finally {
                                temporary.delete();
                            }
                        }
                    } catch (Exception ignored) {
                        // Keep the cached photo or initials if a newer photo cannot be loaded.
                    }
                    Bitmap rendered = photo;
                    if (rendered != null) runOnUiThread(() -> view.setImageBitmap(rendered));
                });
    }

    private Bitmap decodeAvatar(File file) {
        if (!file.isFile()) return null;
        String path = file.getAbsolutePath();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        return BitmapFactory.decodeFile(path, avatarSample(bounds));
    }

    private BitmapFactory.Options avatarSample(BitmapFactory.Options bounds) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        int target = dp(56);
        int shortEdge = Math.min(bounds.outWidth, bounds.outHeight);
        while (shortEdge / (options.inSampleSize * 2) >= target) options.inSampleSize *= 2;
        return options;
    }

    String initials(String s) {
        String[] a = s.trim().split("\\s+");
        return a.length > 1
                ? ("" + a[0].charAt(0) + a[1].charAt(0)).toUpperCase(Locale.ROOT)
                : s.substring(0, Math.min(2, s.length())).toUpperCase(Locale.ROOT);
    }

    ArrayList<String> ranked() {
        ArrayList<String> a = new ArrayList<>();
        if (members.isEmpty()) a.addAll(players);
        else for (OfficeMember m : members) a.add(m.name);
        Collections.sort(
                a,
                (x, y) -> {
                    int d = Integer.compare(wins(y) - losses(y), wins(x) - losses(x));
                    return d != 0 ? d : Integer.compare(wins(y), wins(x));
                });
        return a;
    }

    void tablePage() {
        title("THE OFFICE LEAGUE", "Leaderboard", "Ranked by Elo rating");
        if (members.isEmpty()) {
            emptyCard("No players yet", "Registered profiles will appear here after syncing.");
            return;
        }
        LinearLayout board = card();
        LinearLayout header = horizontal();
        header.addView(text("PLAYER", 10, MUTED, true), new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(standingValue("W", 10, MUTED), lp(30, -2));
        header.addView(standingValue("L", 10, MUTED), lp(30, -2));
        header.addView(standingValue("ELO", 10, MUTED), lp(52, -2));
        board.addView(header);
        ArrayList<OfficeMember> standings = new ArrayList<>(members);
        Collections.sort(
                standings,
                (a, b) -> {
                    int ratingOrder = Integer.compare(b.rating, a.rating);
                    if (ratingOrder != 0) return ratingOrder;
                    int nameOrder = a.name.compareToIgnoreCase(b.name);
                    return nameOrder != 0 ? nameOrder : a.uid.compareTo(b.uid);
                });

        Map<String, int[]> records = new HashMap<>();
        for (OfficeMember member : standings) records.put(member.uid, new int[2]);
        for (int i = 0; i < matches.length(); i++) {
            JSONObject match = matches.optJSONObject(i);
            if (match == null || !match.optBoolean("synced", true)) continue;
            String a = match.optString("playerAUid"), b = match.optString("playerBUid");
            String winner = match.optString("winnerUid");
            if (a.isEmpty()
                    || b.isEmpty()
                    || a.equals(b)
                    || (!winner.equals(a) && !winner.equals(b))) continue;
            String loser = winner.equals(a) ? b : a;
            int[] wins = records.get(winner), losses = records.get(loser);
            if (wins == null || losses == null) continue;
            wins[0]++;
            losses[1]++;
        }
        int rank = 0;
        int previousRating = Integer.MIN_VALUE;
        for (int i = 0; i < standings.size(); i++) {
            OfficeMember member = standings.get(i);
            if (i == 0 || member.rating != previousRating) rank = i + 1;
            previousRating = member.rating;
            int[] record = records.get(member.uid);
            LinearLayout row = horizontal();
            row.addView(text(String.valueOf(rank), 11, MUTED, true), lp(26, -2));
            row.addView(
                    text(member.name, 14, INK, member.uid.equals(uid)),
                    new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(standingValue(String.valueOf(record[0]), 12, INK), lp(30, -2));
            row.addView(standingValue(String.valueOf(record[1]), 12, MUTED), lp(30, -2));
            row.addView(
                    standingValue(String.valueOf(member.rating), 14, tone(0xff285625, 0xffC5E7AF)),
                    lp(52, -2));
            row.setMinimumHeight(dp(48));
            board.addView(row, lp(-1, -2));
            View divider = new View(this);
            divider.setBackgroundColor(LINE);
            board.addView(divider, lp(-1, 1));
        }
        TextView note =
                text(
                        "Everyone starts at 1,200. Beating a higher-rated player earns more"
                                + " points.",
                        12,
                        MUTED,
                        false);
        LinearLayout.LayoutParams placement = lp(-1, -2);
        placement.topMargin = dp(10);
        content.addView(note, placement);
    }

    private TextView standingValue(String value, int size, int color) {
        TextView column = text(value, size, color, true);
        column.setGravity(Gravity.END);
        return column;
    }

    void statsPage() {
        boolean mine = viewedProfileUid.isEmpty();
        String profileUid = mine ? uid : viewedProfileUid;
        OfficeMember me = memberByUid(profileUid);
        if (!mine && me == null) {
            title("CUE CLUB", "Player profile", "This profile is unavailable.");
            addHomeMatchButton(content, "Back", this::closeCoworkerProfile);
            return;
        }
        String name = me == null ? currentName() : me.name;
        int rating = me == null ? OfficeMember.START_RATING : me.rating;
        title("CUE CLUB", mine ? "My profile" : "Player profile", null);
        LinearLayout overview = card();
        LinearLayout identity = horizontal();
        identity.addView(avatar(name, me), lp(56, 56));
        TextView identityName = text(name, 21, INK, true);
        LinearLayout.LayoutParams namePosition = new LinearLayout.LayoutParams(0, -2, 1);
        namePosition.leftMargin = dp(12);
        identity.addView(identityName, namePosition);
        overview.addView(identity, lp(-1, -2));
        if (mine) addHomeMatchButton(overview, "Edit profile", this::editProfile);
        else addHomeMatchButton(overview, "Back", this::closeCoworkerProfile);
        statRow(overview, "Current Elo", String.valueOf(rating));

        int wins = 0, played = 0;
        StringBuilder form = new StringBuilder();
        for (int i = 0; i < matches.length(); i++) {
            JSONObject match = matches.optJSONObject(i);
            if (match == null
                    || !match.optBoolean("synced", true)
                    || (!profileUid.equals(match.optString("playerAUid"))
                            && !profileUid.equals(match.optString("playerBUid")))) continue;
            boolean won = profileUid.equals(match.optString("winnerUid"));
            if (played < 5) {
                if (played > 0) form.append("  ");
                form.append(won ? "W" : "L");
            }
            played++;
            if (won) wins++;
        }

        LinearLayout progress = card();
        progress.addView(text("Elo progression", 19, INK, true));
        ArrayList<Integer> history = new ArrayList<>();
        JSONArray sourceHistory = mine ? ratingHistory : me.ratingHistory;
        for (int i = 0; i < sourceHistory.length(); i++)
            history.add(sourceHistory.optInt(i, rating));
        if (!history.isEmpty())
            statRow(progress, "Personal best", String.valueOf(Collections.max(history)));
        if (history.size() > 1) {
            int recent = Math.min(5, history.size() - 1);
            int trend = history.get(history.size() - 1) - history.get(history.size() - 1 - recent);
            statRow(
                    progress,
                    "Change over last " + recent + (recent == 1 ? " match" : " matches"),
                    String.format(Locale.ROOT, "%+d", trend));
            List<Integer> points =
                    history.subList(Math.max(0, history.size() - 21), history.size());
            progress.addView(ratingChart(points), lp(-1, 116));
            progress.addView(
                    text(
                            "Last "
                                    + (points.size() - 1)
                                    + " confirmed "
                                    + (points.size() == 2 ? "match" : "matches")
                                    + " · oldest to newest",
                            12,
                            MUTED,
                            false));
        } else {
            progress.addView(
                    text(
                            played == 0
                                    ? "Rating history starts with the first confirmed match."
                                    : "Rating history appears after your next server sync.",
                            13,
                            MUTED,
                            false));
        }

        LinearLayout performance = card();
        performance.addView(text("Match stats", 19, INK, true));
        statRow(performance, "Confirmed matches", String.valueOf(played));
        statRow(performance, "Wins · losses", wins + " · " + (played - wins));
        statRow(
                performance,
                "Win rate",
                played == 0 ? "—" : Math.round(100f * wins / played) + "%");
        if (played > 0) statRow(performance, "Recent form · newest first", form.toString());
    }

    private View ratingChart(List<Integer> points) {
        int min = Collections.min(points) - 4;
        int max = Collections.max(points) + 4;
        Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
        guide.setColor(LINE);
        guide.setStrokeWidth(dp(1));
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(tone(0xff3D7134, GREEN));
        stroke.setStrokeWidth(dp(2));
        stroke.setStrokeCap(Paint.Cap.ROUND);
        View chart =
                new View(this) {
                    @Override
                    protected void onDraw(Canvas canvas) {
                        float inset = dp(10),
                                width = getWidth() - inset * 2,
                                height = getHeight() - inset * 2;
                        canvas.drawLine(
                                inset, inset + height, inset + width, inset + height, guide);
                        canvas.drawLine(
                                inset,
                                inset + height / 2,
                                inset + width,
                                inset + height / 2,
                                guide);
                        float previousX = inset,
                                previousY = inset + height * (max - points.get(0)) / (max - min);
                        for (int i = 1; i < points.size(); i++) {
                            float x = inset + width * i / (points.size() - 1);
                            float y = inset + height * (max - points.get(i)) / (max - min);
                            canvas.drawLine(previousX, previousY, x, y, stroke);
                            previousX = x;
                            previousY = y;
                        }
                        canvas.drawCircle(previousX, previousY, dp(4), stroke);
                    }
                };
        chart.setContentDescription(
                "Elo chart. Starts at "
                        + points.get(0)
                        + ", ends at "
                        + points.get(points.size() - 1)
                        + ".");
        return chart;
    }

    void statRow(LinearLayout parent, String label, String value) {
        LinearLayout r = horizontal();
        r.addView(text(label, 12, MUTED, false), new LinearLayout.LayoutParams(0, -2, 1));
        r.addView(text(value, 13, INK, true));
        parent.addView(r, lp(-1, 43));
        View d = new View(this);
        d.setBackgroundColor(LINE);
        parent.addView(d, lp(-1, 1));
    }

    void editProfile() {
        EditText name = input("Display name", false);
        name.setText(sessionName);
        AlertDialog d =
                new AlertDialog.Builder(this)
                        .setTitle("Your office profile")
                        .setView(name)
                        .setNegativeButton("Cancel", null)
                        .setNeutralButton("Change photo", (x, w) -> pickPhoto())
                        .setPositiveButton(
                                "Save name",
                                (x, w) -> saveProfileName(name.getText().toString().trim()))
                        .create();
        d.show();
        d.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(MUTED);
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(INK);
        d.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(INK);
    }

    void saveProfileName(String name) {
        if (name.isEmpty() || name.length() > 48) {
            toast("Name must be 1–48 characters.");
            return;
        }
        sessionName = name;
        for (int i = 0; i < members.size(); i++)
            if (members.get(i).uid.equals(uid)) {
                OfficeMember existing = members.get(i);
                members.set(
                        i,
                        new OfficeMember(
                                uid,
                                name,
                                existing.photoPath,
                                existing.rating,
                                existing.ratingHistory));
                break;
            }
        prefs.edit().putBoolean("pendingName", true).putString("pendingNameValue", name).apply();
        save();
        show();
        syncNow();
    }

    void pickPhoto() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, PICK_PHOTO);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != PICK_PHOTO || result != RESULT_OK || data == null || data.getData() == null)
            return;
        Uri uri = data.getData();
        String playerId = uid;
        String mime = getContentResolver().getType(uri);
        if (!"image/jpeg".equals(mime) && !"image/png".equals(mime) && !"image/webp".equals(mime)) {
            toast("Choose a JPEG, PNG, or WebP photo.");
            return;
        }
        io.execute(() -> stageProfilePhoto(uri, playerId, mime));
    }

    private void stageProfilePhoto(Uri uri, String playerId, String mime) {
        File staged = null;
        try {
            staged = File.createTempFile("pending-photo-", ".image", getFilesDir());
            try (InputStream in = getContentResolver().openInputStream(uri);
                    OutputStream out = new FileOutputStream(staged)) {
                if (in == null) throw new IOException("Could not open the selected photo.");
                OfficeApi.copy(in, out, MAX_PHOTO_BYTES);
            } catch (IOException e) {
                if ("File is too large.".equals(e.getMessage()))
                    throw new IOException(PHOTO_LIMIT_MESSAGE);
                throw e;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(staged.getAbsolutePath(), bounds);
            if (!mime.equals(bounds.outMimeType))
                throw new IOException(
                        "Choose a valid " + mime.substring(6).toUpperCase(Locale.ROOT) + " photo.");
            if (!playerId.equals(uid) || token.isEmpty()) return;
            File previous = pendingPhoto(playerId);
            prefs.edit()
                    .putBoolean("pendingPhoto", true)
                    .putString("pendingPhotoMime", mime)
                    .putString("pendingPhotoOwner", playerId)
                    .putString("pendingPhotoFile", staged.getName())
                    .apply();
            staged = null;
            if (previous != null) previous.delete();
            runOnUiThread(
                    () -> {
                        show();
                        syncNow();
                        toast(
                                online
                                        ? "Photo saved and syncing."
                                        : "Photo saved offline; it will upload when the PC"
                                                + " returns.");
                    });
        } catch (Exception e) {
            runOnUiThread(() -> toast(errorText(e)));
        } finally {
            if (staged != null) staged.delete();
        }
    }

    private void uploadPendingPhoto(OfficeApi api, String auth) throws Exception {
        File photo = pendingPhoto(uid);
        if (photo == null) return;
        if (!photo.isFile()) {
            prefs.edit().putBoolean("pendingPhoto", false).apply();
            runOnUiThread(() -> toast("Choose your profile photo again to sync it."));
            return;
        }
        JSONObject response;
        try {
            response =
                    api.upload(
                            "/profiles/me/photo",
                            auth,
                            photo,
                            prefs.getString("pendingPhotoMime", "image/jpeg"));
        } catch (OfficeApi.ApiException e) {
            if (e.code != 400 && e.code != 413) throw e;
            prefs.edit().putBoolean("pendingPhoto", false).apply();
            photo.delete();
            runOnUiThread(() -> toast(errorText(e)));
            return;
        }
        String revision = response.optString("photoPath");
        if (photo.renameTo(avatarFile(uid))) {
            prefs.edit().putString("avatarRevision:" + uid, revision).apply();
        } else {
            photo.delete();
        }
        prefs.edit()
                .putBoolean("pendingPhoto", false)
                .remove("pendingPhotoFile")
                .remove("pendingPhotoOwner")
                .apply();
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    String visibleDataKey() {
        JSONArray p = new JSONArray();
        for (OfficeMember m : members) p.put(m.toJson());
        return matches.toString()
                + "|"
                + p.toString()
                + "|"
                + games.toString()
                + "|"
                + seasonData.toString()
                + "|"
                + playingNow.toString()
                + "|"
                + isAdmin;
    }

    void syncNow() {
        if (token.isEmpty() || serverUrl.isEmpty() || syncBusy) return;
        syncBusy = true;
        String base = serverUrl, auth = token;
        io.execute(
                () -> {
                    boolean was = online;
                    String before = visibleDataKey();
                    try {
                        OfficeApi api = new OfficeApi(base);
                        api.get("/health", null);
                        if (prefs.getBoolean("pendingName", false)) {
                            api.put(
                                    "/profiles/me",
                                    auth,
                                    new JSONObject()
                                            .put(
                                                    "displayName",
                                                    prefs.getString(
                                                            "pendingNameValue", sessionName)));
                            prefs.edit().putBoolean("pendingName", false).apply();
                        }
                        uploadPendingPhoto(api, auth);
                        String rejectedChange = flushGameQueue(api, auth);
                        JSONObject snap = api.get("/sync", auth);
                        if (!auth.equals(token)) return;
                        mergeSnapshot(snap);
                        online = true;
                        save();
                        boolean changed = !before.equals(visibleDataKey());
                        runOnUiThread(
                                () -> {
                                    updateStatus();
                                    if (!was || changed) show();
                                    if (rejectedChange != null) toast(rejectedChange);
                                });
                    } catch (Exception e) {
                        online = false;
                        runOnUiThread(
                                () -> {
                                    if (was && poolTableStatus != null) show();
                                    else updateStatus();
                                });
                    } finally {
                        syncBusy = false;
                    }
                });
    }

    private String flushGameQueue(OfficeApi api, String auth) throws Exception {
        String rejection = null;
        for (int i = 0; i < games.length(); i++) {
            JSONObject game = games.optJSONObject(i);
            if (game == null) continue;
            String id = game.optString("id");
            String action = game.optString("pendingAction");
            try {
                if (game.optString("status").equals("queued")) {
                    api.post(
                            "/games/invite",
                            auth,
                            new JSONObject()
                                    .put("id", id)
                                    .put("opponentUid", game.optString("playerBUid"))
                                    .put("tournamentMatchId", game.optString("tournamentMatchId")));
                }
                if (!action.isEmpty()) {
                    JSONObject body =
                            action.equals("respond")
                                    ? new JSONObject()
                                            .put("accept", game.optBoolean("pendingAccept"))
                                    : new JSONObject()
                                            .put("winnerUid", game.optString("pendingWinnerUid"));
                    api.post("/games/" + id + "/" + action, auth, body);
                }
            } catch (OfficeApi.ApiException e) {
                if (e.code != 400 && e.code != 403 && e.code != 404 && e.code != 409) throw e;
                rejection = errorText(e);
            }
        }
        return rejection;
    }

    void mergeSnapshot(JSONObject snap) throws Exception {
        long serverTimeMs = snap.optLong("serverTimeMs");
        if (serverTimeMs > 0) {
            serverClockOffsetMs = serverTimeMs - SystemClock.elapsedRealtime();
            hasServerClock = true;
        }
        isAdmin = snap.optBoolean("isAdmin", isAdmin);
        JSONArray playing = snap.optJSONArray("playingNow");
        hasTableStatus = playing != null;
        playingNow = playing == null ? new JSONArray() : playing;
        JSONArray history = snap.optJSONArray("ratingHistory");
        if (history != null) ratingHistory = history;
        JSONArray remoteGames = snap.optJSONArray("games");
        if (remoteGames != null) games = remoteGames;
        JSONObject remoteSeason = snap.optJSONObject("season");
        seasonData = remoteSeason == null ? new JSONObject() : remoteSeason;
        JSONArray remoteMembers = snap.optJSONArray("members"),
                remoteMatches = snap.optJSONArray("matches");
        ArrayList<OfficeMember> next = new ArrayList<>();
        if (remoteMembers != null)
            for (int i = 0; i < remoteMembers.length(); i++)
                next.add(OfficeMember.fromJson(remoteMembers.getJSONObject(i)));
        members = next;
        JSONArray combined = new JSONArray();
        if (remoteMatches != null) {
            for (int i = 0; i < remoteMatches.length(); i++) {
                combined.put(toLocalMatch(remoteMatches.getJSONObject(i)));
            }
        }
        matches = combined;
    }

    JSONObject toLocalMatch(JSONObject j) throws Exception {
        String a = j.optString("playerAName"),
                b = j.optString("playerBName"),
                winner = j.optString("winnerUid"),
                aUid = j.optString("playerAUid"),
                bUid = j.optString("playerBUid"),
                created = j.optString("createdAt");
        JSONObject m = new JSONObject();
        m.put("id", j.optString("id"));
        m.put("a", a);
        m.put("b", b);
        m.put("playerAUid", aUid);
        m.put("playerBUid", bUid);
        m.put("winnerUid", winner);
        m.put("win", winner.equals(aUid));
        m.put("date", created.length() > 16 ? created.substring(0, 16).replace('T', ' ') : created);
        m.put("createdAt", created);
        m.put("synced", true);
        m.put("isLeagueMatch", j.optBoolean("isLeagueMatch"));
        long seconds = j.optLong("durationSeconds", -1);
        if (seconds >= 0) m.put("durationSeconds", seconds);
        int change = j.optInt("ratingChange", -1);
        if (change >= 0) m.put("ratingChange", change);
        return m;
    }

    void updateStatus() {
        updatePoolTableStatus();
        if (statusChip == null) return;
        statusChip.setText(online ? "● ONLINE" : "● OFFLINE");
        statusChip.setTextColor(
                online ? tone(0xff3D7134, 0xffC1EAAE) : tone(0xff8B4639, 0xffFFD0C6));
        statusChip.setBackground(
                shape(online ? tone(0xffEAF4DF, 0xff37483C) : tone(0xffF8E9E5, 0xff503D3C), 14));
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        updateMatchAlertsStatus();
        updateMatchTimer();
        beginPolling();
        startInboxService();
    }

    @Override
    protected void onPause() {
        super.onPause();
        foreground = false;
        handler.removeCallbacks(timerTick);
        if (poll != null) handler.removeCallbacks(poll);
    }

    @Override
    protected void onDestroy() {
        avatarIo.shutdownNow();
        super.onDestroy();
    }

    void beginPolling() {
        if (poll != null) handler.removeCallbacks(poll);
        poll =
                new Runnable() {
                    @Override
                    public void run() {
                        if (token.isEmpty()) checkServer();
                        else syncNow();
                        checkForAppUpdate();
                        handler.postDelayed(this, matchPollingInterval());
                    }
                };
        handler.post(poll);
    }

    private void checkForAppUpdate() {
        long now = SystemClock.elapsedRealtime();
        if (now < nextUpdateCheck || !timerGameId.isEmpty()) return;
        nextUpdateCheck = now + UPDATE_RETRY_MS;
        io.execute(
                () -> {
                    try {
                        JSONObject release =
                                new OfficeApi(DEFAULT_SERVER_URL).get(ANDROID_UPDATE_PATH, "");
                        int versionCode = release.optInt("versionCode");
                        String versionName = release.optString("versionName");
                        runOnUiThread(() -> offerAppUpdate(versionCode, versionName));
                    } catch (Exception ignored) {
                        // The next poll after the retry interval can try again.
                    }
                });
    }

    private void offerAppUpdate(int versionCode, String versionName) {
        if (!foreground || isFinishing() || isDestroyed() || !timerGameId.isEmpty()) return;
        nextUpdateCheck = SystemClock.elapsedRealtime() + UPDATE_CHECK_INTERVAL_MS;
        if (versionCode <= Math.max(BuildConfig.VERSION_CODE, offeredUpdate)
                || versionName.isEmpty()) return;

        offeredUpdate = versionCode;
        new AlertDialog.Builder(this)
                .setTitle("Cue Club update available")
                .setMessage(
                        "Version " + versionName
                                + " is ready. Download the APK, then open it to install the update.")
                .setNegativeButton("Later", null)
                .setPositiveButton("Download", (dialog, which) -> downloadAppUpdate())
                .show();
    }

    private void downloadAppUpdate() {
        Uri apk = Uri.parse(DEFAULT_SERVER_URL + ANDROID_UPDATE_PATH + "/cue-club.apk");
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, apk));
        } catch (ActivityNotFoundException e) {
            offeredUpdate = 0;
            Toast.makeText(this, "Open a browser to download the Cue Club update.", Toast.LENGTH_LONG).show();
        }
    }

    private long matchPollingInterval() {
        for (int i = 0; i < games.length(); i++) {
            JSONObject game = games.optJSONObject(i);
            if (game == null) continue;
            if (!uid.equals(game.optString("playerAUid"))
                    && !uid.equals(game.optString("playerBUid"))) continue;
            String status = game.optString("status");
            if (status.equals("invited") || isTimingMatch(status)) return 3_000;
        }
        return !token.isEmpty() && page == 0 ? 4_000 : 15_000;
    }

    void checkServer() {
        if (serverUrl.isEmpty()) return;
        String base = serverUrl;
        io.execute(
                () -> {
                    boolean reachable;
                    try {
                        new OfficeApi(base).get("/health", null);
                        reachable = true;
                    } catch (Exception e) {
                        reachable = false;
                    }
                    online = reachable;
                    runOnUiThread(this::updateStatus);
                });
    }

    void startInboxService() {
        if (token.isEmpty()) return;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (!prefs.getBoolean("notificationPermissionAsked", false)) {
                prefs.edit().putBoolean("notificationPermissionAsked", true).apply();
                requestPermissions(
                        new String[] {android.Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFICATION_PERMISSION_REQUEST);
            }
            return;
        }
        try {
            Intent service = new Intent(this, MatchInboxService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
        } catch (Exception e) {
            toast("Allow notifications to receive game invites while Cue Club is closed.");
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST
                && grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED)
            startInboxService();
    }
}
