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
    private static final String ANDROID_UPDATE_PATH = "/updates/android";
    private static final String ACCOUNT_DELETION_PATH = "/profiles/me/account-deletion";
    private static final long UPDATE_RETRY_MS = 60_000;
    private static final long UPDATE_CHECK_INTERVAL_MS = 15 * 60_000;
    static final String PREFERENCES_NAME = "MainActivity";
    static final int PICK_PHOTO = 4102;
    private static final int MAX_PHOTO_MB = 15;
    private static final long MAX_PHOTO_BYTES = MAX_PHOTO_MB * 1024L * 1024L;
    private static final int PROFILE_PHOTO_EDGE = 512;
    private static final int PROFILE_PHOTO_QUALITY = 85;
    private static final String PHOTO_LIMIT_MESSAGE =
            "Choose a photo of " + MAX_PHOTO_MB + " MB or less.";
    static final int NOTIFICATION_PERMISSION_REQUEST = 5201;
    LinearLayout root, content, nav;
    ScrollView bodyScroll;
    private HorizontalScrollView bracketScroll;
    TextView statusChip;
    private TextView matchAlertsStatus;
    private TextView poolTableStatus;
    private JSONArray playingNow = new JSONArray();
    private JSONArray doubles = new JSONArray();
    private boolean doublesAvailable;
    private long guestExpiresAt;
    private JSONArray weeklyHighlights = new JSONArray();
    private final Set<String> newResults = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Queue<JSONObject> bracketAdvances = new ConcurrentLinkedQueue<>();
    private volatile boolean tableMotionPending;
    private boolean motionSnapshotSeen;
    private final Map<TextView, String> tableClocks = new LinkedHashMap<>();
    private boolean hasTableStatus;
    private volatile long lastSyncAt;
    private volatile boolean connectionChecked;
    private JSONObject nextUp;
    private boolean hasNextUp;
    private String notificationGameId = "";
    private TextView syncLabel;
    private String viewedProfileUid = "";
    private int profileReturnPage = 2;
    int page = 0;
    private final Deque<Integer> pageHistory = new ArrayDeque<>();

    private enum ResultFilter {
        ALL,
        LEAGUE,
        ONE_V_ONE,
        DOUBLES
    }

    private ResultFilter resultFilter = ResultFilter.ALL;
    JSONArray matches = new JSONArray(), games = new JSONArray(), ratingHistory = new JSONArray();
    ArrayList<String> players = new ArrayList<>();
    ArrayList<OfficeMember> members = new ArrayList<>();
    JSONObject seasonData = new JSONObject();
    long draftLeagueDeadline;
    String draftLeagueName = "Office League";
    SharedPreferences prefs;
    private final String serverUrl = DEFAULT_SERVER_URL;
    String uid = "", sessionName = "";
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
            notificationGameId = b.getString("notificationGameId", "");
            ArrayList<Integer> history = b.getIntegerArrayList("pageHistory");
            if (history != null) pageHistory.addAll(history);
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
        state.putString("notificationGameId", notificationGameId);
        state.putIntegerArrayList("pageHistory", new ArrayList<>(pageHistory));
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
        String target = intent.getStringExtra(MatchInboxService.MATCH_NOTIFICATION_ID);
        notificationGameId = target == null ? "" : target;
        timerGameId = "";
        intent.removeExtra(MatchInboxService.MATCH_NOTIFICATION_ID);
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
        token = prefs.getString("token", "");
        uid = prefs.getString("uid", "");
        sessionName = prefs.getString("sessionName", "");
        guestExpiresAt = prefs.getLong("guestExpiresAt", 0);
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
            doubles = new JSONArray(prefs.getString("doubles", "[]"));
            doublesAvailable = prefs.getBoolean("doublesAvailable", false);
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
                .putString("token", token)
                .putString("uid", uid)
                .putString("sessionName", sessionName)
                .putLong("guestExpiresAt", guestExpiresAt)
                .putBoolean("isAdmin", isAdmin)
                .putString("matches", matches.toString())
                .putString("games", games.toString())
                .putString("doubles", doubles.toString())
                .putBoolean("doublesAvailable", doublesAvailable)
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
        tableClocks.clear();
        bracketScroll = null;
        syncLabel = null;
        if (token.isEmpty()) {
            timerGameId = "";
            openedMatchTimers.clear();
            showAuth();
            return;
        }
        JSONObject exactGame = notificationGameId.isEmpty() ? null : findGame(notificationGameId);
        JSONObject exactDoubles = notificationGameId.isEmpty() ? null : findDoubles(notificationGameId);
        if (exactGame != null && isOwnStartedGame(exactGame)
                && !exactGame.optString("status").equals("declined")) {
            openMatchTimer(exactGame);
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
            if (notificationGameId.isEmpty() && isOwnStartedGame(game)
                    && isTimingMatch(game.optString("status"))
                    && !openedMatchTimers.contains(game.optString("id"))) {
                openMatchTimer(game);
                return;
            }
        }
        root = newRoot();
        setContentView(root);
        addHeader();
        boolean canRefresh = notificationGameId.isEmpty() && !showAdminPanel && (page == 0 || page == 1);
        FrameLayout scrollFrame = new FrameLayout(this);
        TextView cueBall = canRefresh ? PoolMotion.ball(this) : null;
        View poolCue = canRefresh ? PoolMotion.cue(this) : null;
        ScrollView s = canRefresh
                ? new PoolMotion.PullScroll(this, dp(72), cueBall, poolCue, this::syncNow)
                : new ScrollView(this);
        s.setFillViewport(true);
        bodyScroll = s;
        content = vertical();
        pad(content, 20, 10, 20, 24);
        s.addView(content);
        scrollFrame.addView(s, new FrameLayout.LayoutParams(-1, -1));
        if (cueBall != null) {
            cueBall.setVisibility(View.GONE);
            poolCue.setVisibility(View.GONE);
            FrameLayout.LayoutParams cuePosition = new FrameLayout.LayoutParams(
                    dp(92), dp(6), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            cuePosition.topMargin = dp(19);
            scrollFrame.addView(poolCue, cuePosition);
            scrollFrame.addView(cueBall, new FrameLayout.LayoutParams(dp(44), dp(44),
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        }
        root.addView(scrollFrame, new LinearLayout.LayoutParams(-1, 0, 1));
        if (!notificationGameId.isEmpty()) {
            title("MATCH ALERT", "Match", null);
            if (exactGame != null) gameCard(content, exactGame);
            else if (exactDoubles != null) doublesCard(exactDoubles);
            else content.addView(text(online ? "This match is no longer available." : "Looking for your match…",
                    14, MUTED, false));
            addHomeMatchButton(content, "Back", () -> {
                notificationGameId = "";
                show();
            });
        } else if (showAdminPanel) adminPage();
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
                        navigateToPage(n);
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
        FrameLayout center = new FrameLayout(this);
        center.addView(versus, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        matchup.addView(center, lp(72, -2));
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
        if (animate && PoolMotion.enabled(this)) {
            animateMatchIntro(left, right, center, versus, clock);
        }

        String reportField =
                uid.equals(game.optString("playerAUid")) ? "reportAWinnerUid" : "reportBWinnerUid";
        boolean reported = !game.optString(reportField).isEmpty();
        if (finished) {
            LinearLayout details = vertical();
            details.addView(text("Winner · " + game.optString("winnerName"), 18, resultGold(), true));
            String resultId = game.optString("statsMatchId");
            boolean reveal = newResults.remove(resultId) && PoolMotion.enabled(this);
            for (int i = 0; !resultId.isEmpty() && i < matches.length(); i++) {
                JSONObject match = matches.optJSONObject(i);
                if (match != null && resultId.equals(match.optString("id"))) {
                    addResultRating(details, match, reveal);
                    addRematchButton(details, match);
                    break;
                }
            }
            if (reveal) animatePocket(content, details, isLeagueFinal(resultId));
            content.addView(details, lp(-1, -2));
        } else if ("report".equals(game.optString("pendingAction"))) {
            content.addView(text("Result saved on this phone · waiting to sync", 14, MUTED, false));
        } else if (state.equals("disputed")) {
            content.addView(text("The league admin is reviewing the result.", 14, MUTED, false));
        } else if (reported) {
            String opponent = uid.equals(game.optString("playerAUid"))
                    ? game.optString("playerBName") : game.optString("playerAName");
            content.addView(
                    text("Waiting for " + opponent + " to confirm the winner.", 14, MUTED, false));
        } else if (state.equals("awaiting_confirmation") && !opponentReport(game).isEmpty()) {
            content.addView(text(reportedWinnerLabel(game), 14, MUTED, false));
            addResultConfirmation(content, game, () -> {});
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

    private void animateMatchIntro(View left, View right, FrameLayout center, View versus, View clock) {
        left.setTranslationX(-dp(32));
        right.setTranslationX(dp(32));
        left.setAlpha(0);
        right.setAlpha(0);
        versus.setAlpha(0);
        versus.setScaleX(.75f);
        versus.setScaleY(.75f);
        clock.setAlpha(0);
        left.animate().translationX(0).alpha(1).setDuration(500).start();
        right.animate().translationX(0).alpha(1).setDuration(500).start();
        View ball = PoolMotion.ball(this);
        center.addView(ball, new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER));
        ball.setTranslationX(-dp(47));
        ball.setRotation(-300);
        ball.animate().translationX(0).rotation(0).setDuration(700)
                .withEndAction(() -> ball.animate().alpha(0).scaleX(.65f).scaleY(.65f)
                        .setStartDelay(150).setDuration(170)
                        .withEndAction(() -> center.removeView(ball)).start())
                .start();
        versus.animate().scaleX(1).scaleY(1).alpha(1).setStartDelay(940).setDuration(250).start();
        clock.animate().alpha(1).setStartDelay(1120).setDuration(280).start();
    }

    private void updateMatchTimer() {
        handler.removeCallbacks(timerTick);
        JSONObject game = findGame(timerGameId);
        boolean running = false;
        if (matchTimerText != null && game != null) {
            running = isTimingMatch(game.optString("status"));
            String finished = game.optString("finishedAt");
            matchTimerText.setText(
                    running || !finished.isEmpty()
                            ? elapsed(game.optString("startedAt"), finished)
                            : "—");
        }
        for (Map.Entry<TextView, String> clock : tableClocks.entrySet()) {
            clock.getKey().setText(elapsed(clock.getValue(), ""));
        }
        if (foreground && (running || !tableClocks.isEmpty())) {
            handler.postDelayed(timerTick, 250);
        }
    }

    private void leaveMatchTimer() {
        JSONObject game = findGame(timerGameId);
        page = game != null && game.optString("tournamentMatchId").isEmpty() ? 1 : 0;
        timerGameId = "";
        notificationGameId = "";
        showAdminPanel = false;
        show();
    }

    private void navigateToPage(int destination) {
        notificationGameId = "";
        if (destination == 0) pageHistory.clear();
        else if (destination != page) pageHistory.push(page);
        page = destination;
    }

    @Override
    public void onBackPressed() {
        if (!timerGameId.isEmpty()) {
            timerGameId = "";
            notificationGameId = "";
            show();
        } else if (!notificationGameId.isEmpty()) {
            notificationGameId = "";
            show();
        } else if (!viewedProfileUid.isEmpty()) {
            closeCoworkerProfile();
        } else if (showAdminPanel) {
            showAdminPanel = false;
            show();
        } else if (token.isEmpty()) {
            if (createAccount) {
                createAccount = false;
                show();
            } else {
                super.onBackPressed();
            }
        } else if (page != 0) {
            page = pageHistory.isEmpty() ? 0 : pageHistory.pop();
            show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Leave Cue Club?")
                    .setMessage("Are you sure you want to leave the app?")
                    .setNegativeButton("Stay", null)
                    .setPositiveButton("Leave", (dialog, which) -> finish())
                    .show();
        }
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
                        ? "Use your email address to join the shared scorebook."
                        : "Sign in to see the shared players and scores.");
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
            form.addView(lineLabel("EMAIL"));
            EditText em = input("you@example.com", true);
            form.addView(em, lp(-1, 52));
            form.addView(spacer(8));
            form.addView(lineLabel("PASSWORD"));
            EditText pw = input("At least 8 characters", true);
            pw.setInputType(129);
            form.addView(pw, lp(-1, 52));
            CheckBox guest = new CheckBox(this);
            guest.setText("Guest account · expires in 24 hours");
            guest.setTextColor(INK);
            form.addView(guest, lp(-1, -2));
            form.addView(text("Leave unchecked for an employee account.", 12, MUTED, false));
            TextView join = button("Create office profile");
            LinearLayout.LayoutParams bp = lp(-1, -2);
            bp.topMargin = dp(14);
            form.addView(join, bp);
            join.setOnClickListener(
                    v -> {
                        register(
                                em.getText().toString().trim(),
                                pw.getText().toString(),
                                nm.getText().toString().trim(),
                                guest.isChecked());
                    });
        } else {
            form.addView(lineLabel("EMAIL"));
            EditText em = input("you@example.com", true);
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
                    createAccount = !createAccount;
                    showAuth();
                });
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
        if (email.isEmpty()) {
            toast("Enter your email address.");
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
                        onAuthSuccess(result);
                    } catch (Exception e) {
                        runOnUiThread(() -> toast(errorText(e)));
                    }
                });
    }

    void register(String email, String password, String name, boolean guest) {
        if (email.isEmpty()) {
            toast("Enter your email address.");
            return;
        }
        if (name.trim().isEmpty() || password.length() < 8) {
            toast("Enter your name and a password with at least 8 characters.");
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
                                                .put("isGuest", guest));
                        onAuthSuccess(result);
                    } catch (Exception e) {
                        runOnUiThread(() -> toast(errorText(e)));
                    }
                });
    }

    void onAuthSuccess(JSONObject r) {
        guestExpiresAt = r.optLong("guestExpiresAt");
        token = r.optString("token");
        uid = r.optJSONObject("member").optString("uid");
        sessionName = r.optJSONObject("member").optString("displayName");
        isAdmin = r.optJSONObject("member").optBoolean("isAdmin", false);
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
            return "Server reached, but the Cue Club API was not found.";
        return m == null || m.isEmpty() ? "Could not reach the server." : m;
    }

    private void openMyProfile() {
        viewedProfileUid = "";
        navigateToPage(4);
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
            addHomeMatchButton(menu, "Delete account", () -> {
                dialog.dismiss();
                showAccountDeletion();
            });
        }
        dialog.setOnDismissListener(dismissed -> statusChip = null);
        dialog.show();
    }

    private void showAccountDeletion() {
        showAccountDeletion(null);
    }

    private void showAccountDeletion(OfficeMember player) {
        String base = serverUrl;
        String session = token;
        String action = player == null ? "Delete account" : "Remove player";
        String description = player == null ? "Your profile details and photo will be deleted."
                : "Remove " + player.name + " and delete their profile details and photo?";
        EditText password = new EditText(this);
        password.setHint("Current password");
        password.setSingleLine(true);
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout fields = vertical();
        pad(fields, 20, 8, 20, 8);
        fields.addView(password, lp(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(action)
                .setMessage(description + " Employee results stay under “Former player.”"
                        + " Guest match history is removed. Elo is kept. This cannot be undone.")
                .setView(fields)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(action, null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = password.getText().toString();
            if (value.isEmpty()) {
                password.setError("Enter your current password.");
                return;
            }
            password.setText("");
            view.setEnabled(false);
            io.execute(() -> {
                try {
                    String path = player == null ? ACCOUNT_DELETION_PATH : "/admin/players/" + player.uid;
                    new OfficeApi(base).delete(path, session,
                            new JSONObject().put("password", value));
                    runOnUiThread(() -> {
                        if (!session.equals(token)) return;
                        dialog.dismiss();
                        if (player != null) {
                            avatarFile(player.uid).delete();
                            closeCoworkerProfile();
                            syncNow();
                            toast("Player removed.");
                            return;
                        }
                        clearDeletedAccountLocally();
                        toast("Your account was deleted.");
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        if (dialog.isShowing()) {
                            password.setError(errorText(error));
                            view.setEnabled(true);
                        }
                    });
                }
            });
        });
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
        clearAccountSession();
        io.execute(
                () -> {
                    try {
                        new OfficeApi(base).post("/auth/logout", oldToken, new JSONObject());
                    } catch (Exception ignored) {
                        // This phone has already signed out.
                    }
                });
    }

    private void clearAccountSession() {
        token = uid = sessionName = "";
        guestExpiresAt = 0;
        createAccount = false;
        isAdmin = showAdminPanel = false;
        page = 0;
        pageHistory.clear();
        matches = new JSONArray();
        games = new JSONArray();
        doubles = new JSONArray();
        doublesAvailable = false;
        seasonData = new JSONObject();
        ratingHistory = new JSONArray();
        viewedProfileUid = "";
        playingNow = new JSONArray();
        weeklyHighlights = new JSONArray();
        newResults.clear();
        bracketAdvances.clear();
        tableMotionPending = false;
        motionSnapshotSeen = false;
        hasTableStatus = false;
        hasNextUp = false;
        nextUp = null;
        notificationGameId = "";
        lastSyncAt = 0;
        connectionChecked = false;
        players.clear();
        members.clear();
        stopService(new Intent(this, MatchInboxService.class));
        save();
        show();
    }

    private void clearDeletedAccountLocally() {
        String deletedUid = uid;
        File photo = pendingPhoto(deletedUid);
        if (photo != null) photo.delete();
        avatarFile(deletedUid).delete();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (notifications != null) notifications.cancelAll();
        prefs.edit().remove("pendingName").remove("pendingNameValue")
                .remove("pendingPhoto").remove("pendingPhotoFile")
                .remove("pendingPhotoOwner").remove("pendingPhotoMime")
                .remove("avatarRevision:" + deletedUid).apply();
        clearAccountSession();
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
        renderNextUp();
        syncLabel = text(connectionText(), 12, MUTED, false);
        content.addView(syncLabel);
        List<JSONObject> actions = homeActions();
        JSONObject nextAction = actions.isEmpty() ? null : actions.get(0);
        if (nextAction != null) gameCard(content, nextAction);
        TextView challenge = button("Challenge a player to 1v1");
        if (nextAction != null) {
            challenge.setTextColor(INK);
            challenge.setBackground(shape(CARD, 14));
        }
        challenge.setOnClickListener(v -> addMatch());
        LinearLayout.LayoutParams challengeLayout = lp(-1, -2);
        challengeLayout.bottomMargin = dp(10);
        content.addView(challenge, challengeLayout);
        if (doublesAvailable) {
            addHomeMatchButton(content, "Start a 2v2", this::startDoubles);
            for (int i = 0; i < doubles.length(); i++) {
                JSONObject game = doubles.optJSONObject(i);
                if (game != null && doublesPlayerIndex(game, uid) >= 0
                        && !"confirmed".equals(game.optString("status"))
                        && !"declined".equals(game.optString("status"))) doublesCard(game);
            }
        }
        for (int i = 1; i < actions.size(); i++) {
            JSONObject game = actions.get(i);
            if (game.optString("tournamentMatchId").isEmpty()) gameCard(content, game);
        }
        renderMyNextMatch(nextAction == null ? "" : nextAction.optString("id"));
        renderWeeklyHighlights();
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
        c.addView(lineLabel("SERVER CONNECTION"));
        TextView t =
                text(
                        "Cue Club will sync your profile and queued changes when the server is"
                                + " reachable.",
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
        ResultFilter[] options = ResultFilter.values();
        String[] labels = {"All results", "League", "1v1", "2v2"};
        for (int i = 0; i < (doublesAvailable ? options.length : options.length - 1); i++) {
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
            if (resultFilter == ResultFilter.ALL || (resultFilter != ResultFilter.DOUBLES
                    && league == (resultFilter == ResultFilter.LEAGUE))) {
                results.add(match);
            }
        }
        ArrayList<JSONObject> doublesResults = new ArrayList<>();
        if (resultFilter == ResultFilter.ALL || resultFilter == ResultFilter.DOUBLES) {
            for (int i = 0; i < doubles.length(); i++) {
                JSONObject game = doubles.optJSONObject(i);
                if (game != null && "confirmed".equals(game.optString("status"))) doublesResults.add(game);
            }
        }
        int resultCount = results.size() + doublesResults.size();
        LinearLayout.LayoutParams section = lp(-1, -2);
        section.topMargin = dp(16);
        section.bottomMargin = dp(8);
        content.addView(
                lineLabel(
                        resultCount == 1
                                ? "1 CONFIRMED RESULT"
                                : resultCount + " CONFIRMED RESULTS"),
                section);
        if (resultCount == 0) {
            emptyCard("No results yet", "Confirmed matches will appear here.");
        } else {
            for (JSONObject match : results) sharedResultCard(match);
            for (JSONObject game : doublesResults) doublesCard(game);
        }
        TextView add = button("Challenge a player to 1v1");
        add.setOnClickListener(v -> addMatch());
        content.addView(add, lp(-1, -2));
    }

    private void sharedResultCard(JSONObject match) {
        LinearLayout panel = card();
        LinearLayout card = vertical();
        boolean reveal = newResults.remove(match.optString("id")) && PoolMotion.enabled(this);
        if (reveal) animatePocket(panel, card, isLeagueFinal(match.optString("id")));
        panel.addView(card, lp(-1, -2));
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
        LinearLayout players = horizontal();
        players.setGravity(Gravity.CENTER);
        players.addView(resultPlayer(match, "A", a, aWon), new LinearLayout.LayoutParams(0, -2, 1));
        players.addView(text("VS", 12, MUTED, true));
        players.addView(resultPlayer(match, "B", b, !aWon), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams playerLayout = lp(-1, -2);
        playerLayout.topMargin = dp(9);
        card.addView(players, playerLayout);
        LinearLayout footer = horizontal();
        footer.addView(
                text("Winner · " + (aWon ? a : b), 12, resultGold(), true),
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
        addResultRating(card, match, reveal);
        addRematchButton(card, match);
    }

    private int resultGold() {
        return tone(0xff765411, 0xffEFCE80);
    }

    private LinearLayout resultPlayer(JSONObject match, String side, String name, boolean won) {
        LinearLayout player = vertical();
        player.setGravity(Gravity.CENTER_HORIZONTAL);
        OfficeMember member = memberByUid(match.optString("player" + side + "Uid"));
        FrameLayout photo = avatar(name, member);
        linkProfilePhoto(photo, member);
        player.addView(photo, lp(44, 44));
        TextView label = text(name, 13, won ? resultGold() : INK, true);
        label.setGravity(Gravity.CENTER);
        player.addView(label, lp(-1, -2));
        return player;
    }

    private void addResultRating(LinearLayout destination, JSONObject match, boolean animate) {
        int change = match.optInt("ratingChange", -1);
        if (change >= 0) {
            destination.addView(
                    text("Elo · winner +" + change + "  /  opponent −" + change, 12, MUTED, false));
        }
        int after = match.optInt("winnerEloAfter", -1);
        if (after >= 0) {
            int before = change >= 0 ? after - change : after;
            TextView rating = text("Winner Elo · " + (animate ? before : after), 13, INK, true);
            destination.addView(rating);
            if (animate && before != after) {
                android.animation.ValueAnimator count = android.animation.ValueAnimator.ofInt(before, after);
                count.setStartDelay(790);
                count.setDuration(420);
                count.addUpdateListener(value -> rating.setText("Winner Elo · " + value.getAnimatedValue()));
                count.start();
            }
        }
        for (int i = 0; i < weeklyHighlights.length(); i++) {
            JSONObject highlight = weeklyHighlights.optJSONObject(i);
            if (highlight != null && match.optString("id").equals(highlight.optString("matchId"))) {
                destination.addView(text("Biggest upset this week", 12, resultGold(), true));
                break;
            }
        }
    }

    private boolean isLeagueFinal(String resultId) {
        JSONObject tournament = seasonData.optJSONObject("tournament");
        JSONArray tiles = tournament == null ? null : tournament.optJSONArray("matches");
        if (tiles == null || resultId.isEmpty()) return false;
        int finalRound = tournament.optInt("roundCount");
        for (int i = 0; i < tiles.length(); i++) {
            JSONObject tile = tiles.optJSONObject(i);
            if (tile != null && tile.optInt("round") == finalRound
                    && resultId.equals(tile.optString("statsMatchId"))) return true;
        }
        return false;
    }

    private void animatePocket(LinearLayout parent, View details, boolean leagueFinal) {
        FrameLayout shot = new FrameLayout(this);
        View pocket = new View(this);
        GradientDrawable ring = shape(0xff171B19, 40);
        ring.setStroke(dp(2), leagueFinal ? resultGold() : tone(0xff688473, 0xff8FB19A));
        pocket.setBackground(ring);
        shot.addView(pocket, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));
        View ball = PoolMotion.ball(this);
        shot.addView(ball, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
        parent.addView(shot, lp(-1, dp(56)));
        ball.setTranslationX(-dp(78));
        ball.setRotation(-360);
        details.setAlpha(0);
        ball.animate().translationX(0).rotation(0).setDuration(620)
                .withEndAction(() -> ball.animate().scaleX(.2f).scaleY(.2f).alpha(0)
                        .setDuration(220).withEndAction(() -> parent.removeView(shot)).start())
                .start();
        if (leagueFinal) {
            details.setScaleX(.94f);
            details.setScaleY(.94f);
        }
        details.animate().alpha(1).scaleX(1).scaleY(1).setStartDelay(790).setDuration(330).start();
    }

    private void addRematchButton(LinearLayout destination, JSONObject match) {
        if (match.optBoolean("isLeagueMatch")) return;
        String a = match.optString("playerAUid"), b = match.optString("playerBUid");
        OfficeMember opponent = uid.equals(a) ? memberByUid(b) : uid.equals(b) ? memberByUid(a) : null;
        if (opponent == null) return;
        addHomeMatchButton(destination, "Rematch", () ->
                new AlertDialog.Builder(this)
                        .setTitle("Play a rematch?")
                        .setMessage("Send a 1v1 invitation to " + opponent.name + "?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Send invite", (dialog, which) -> {
                            timerGameId = "";
                            page = 1;
                            createInvite(opponent);
                        })
                        .show());
    }

    private String resultDate(JSONObject match) {
        return formatServerDate(match.optString("createdAt"), match.optString("date"));
    }

    private String formatServerDate(String timestamp, String fallback) {
        try {
            return java.text.DateFormat.getDateTimeInstance(
                            java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                    .format(new Date(parseServerTime(timestamp)));
        } catch (java.text.ParseException e) {
            return fallback;
        }
    }

    private void renderPoolTable() {
        LinearLayout table = card();
        if (tableMotionPending && PoolMotion.enabled(this)) {
            table.setAlpha(.35f);
            table.animate().alpha(1).setDuration(280).start();
        }
        tableMotionPending = false;
        table.addView(lineLabel("POOL TABLE"));
        poolTableStatus = text("", 19, INK, true);
        table.addView(poolTableStatus, lp(-1, -2));
        updatePoolTableStatus();
        if (!online || !hasTableStatus) return;
        for (int i = 0; i < playingNow.length(); i++) {
            JSONObject game = playingNow.optJSONObject(i);
            if (game == null) continue;
            if (game.optBoolean("isDoubles")) table.addView(lineLabel("2V2 DOUBLES"));
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
            String started = game.optString("startedAt");
            if (!started.isEmpty()) {
                TextView clock = text("", 13, tone(0xff285625, GREEN), true);
                clock.setGravity(Gravity.CENTER);
                table.addView(clock, lp(-1, -2));
                tableClocks.put(clock, started);
            }
        }
        updateMatchTimer();
    }

    private void renderNextUp() {
        if (!hasNextUp) return;
        LinearLayout panel = card();
        panel.addView(lineLabel("NEXT UP"));
        if (nextUp == null) {
            if (online) addHomeMatchButton(panel, "Claim next up", this::claimNextUp);
            else panel.addView(text("Connect to claim the next slot.", 13, MUTED, false));
            return;
        }
        String aUid = nextUp.optString("playerAUid"), bUid = nextUp.optString("playerBUid");
        OfficeMember a = memberByUid(aUid), b = memberByUid(bUid);
        panel.addView(text((a == null ? "Player" : a.name) + " vs "
                + (b == null ? "Player" : b.name), 16, INK, true));
        if (online && (isAdmin || uid.equals(aUid) || uid.equals(bUid))) {
            addHomeMatchButton(panel, "Clear slot", () -> postServer(
                    "/table/next-up", boolPayload("cancel", true), "Next-up slot cleared."));
        }
    }

    private void claimNextUp() {
        ArrayList<OfficeMember> choices = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        for (OfficeMember member : members) {
            if (uid.equals(member.uid)) continue;
            choices.add(member);
            labels.add(member.name);
        }
        if (choices.isEmpty()) {
            toast("Coworkers appear here after registering.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Choose who’s playing next with you")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    try {
                        postServer("/table/next-up",
                                new JSONObject().put("opponentUid", choices.get(which).uid),
                                "You're next up.");
                    } catch (JSONException error) {
                        toast(errorText(error));
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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

    private void renderMyNextMatch(String featuredGameId) {
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
            if (!featuredGameId.isEmpty() && featuredGameId.equals(match.optString("gameId"))) {
                return;
            }
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
                if (needsConfirmation && !opponentReport(game).isEmpty()) {
                    panel.addView(text(reportedWinnerLabel(game), 13, MUTED, false));
                    addResultConfirmation(panel, game, () -> {});
                } else {
                    addHomeMatchButton(panel, "Open match timer", () -> openMatchTimer(game));
                }
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

    private List<JSONObject> homeActions() {
        ArrayList<JSONObject> invitations = new ArrayList<>();
        ArrayList<JSONObject> confirmations = new ArrayList<>();
        JSONObject active = null;
        for (int i = 0; i < games.length(); i++) {
            JSONObject game = games.optJSONObject(i);
            if (game == null) continue;
            String state = game.optString("status");
            if (state.equals("invited") && uid.equals(game.optString("playerBUid"))) {
                invitations.add(game);
            } else if (state.equals("awaiting_confirmation") && isOwnStartedGame(game)) {
                String reportKey = uid.equals(game.optString("playerAUid"))
                        ? "reportAWinnerUid" : "reportBWinnerUid";
                if (game.optString(reportKey).isEmpty()) confirmations.add(game);
            } else if (state.equals("active") && isOwnStartedGame(game)) {
                active = game;
            }
        }
        confirmations.addAll(invitations);
        if (confirmations.isEmpty() && active != null) confirmations.add(active);
        return confirmations;
    }

    private void renderWeeklyHighlights() {
        if (weeklyHighlights.length() == 0) return;
        LinearLayout panel = card();
        panel.addView(text("This week at the table", 17, INK, true));
        for (int i = 0; i < weeklyHighlights.length(); i++) {
            JSONObject highlight = weeklyHighlights.optJSONObject(i);
            if (highlight == null) continue;
            LinearLayout.LayoutParams spacing = lp(-1, -2);
            spacing.topMargin = dp(8);
            panel.addView(text(highlight.optString("title"), 14, INK, true), spacing);
            panel.addView(text(highlight.optString("detail"), 13, MUTED, false));
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
                                        ? "Deadline · " + leagueDate(deadline - 1)
                                        : "Single elimination · winners advance";
        summary.addView(text(detail, 13, state.equals("completed") ? resultGold() : MUTED, false));

        JSONArray matches = tournament.optJSONArray("matches");
        if (matches == null || matches.length() == 0) return;
        int rounds = tournament.optInt("roundCount", 1);
        int firstRoundSlots = 0;
        for (int i = 0; i < matches.length(); i++) {
            JSONObject match = matches.optJSONObject(i);
            if (match != null && match.optInt("round") == 1) firstRoundSlots++;
        }

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        bracketScroll = scroll;
        String myMatchId = tournament.optString("myMatchId");
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
        Paint advancePaint = new Paint(connectorPaint);
        advancePaint.setColor(tone(0xff447B40, GREEN));
        advancePaint.setStrokeWidth(dp(3));
        advancePaint.setStyle(Paint.Style.STROKE);
        Set<String> advancing = new HashSet<>();
        Set<String> destinations = new HashSet<>();
        for (JSONObject source = bracketAdvances.poll(); source != null; source = bracketAdvances.poll()) {
            int round = source.optInt("round"), slot = source.optInt("slot");
            if (PoolMotion.enabled(this)) {
                advancing.add(round + ":" + slot);
                destinations.add((round + 1) + ":" + slot / 2);
            }
        }
        float[] traceProgress = {0f};
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
                                if (advancing.contains(round + ":" + slot)) {
                                    Path path = new Path();
                                    path.moveTo(x, y);
                                    path.lineTo(x + gap / 2f, y);
                                    path.lineTo(x + gap / 2f, nextY);
                                    path.lineTo(x + gap, nextY);
                                    PathMeasure measure = new PathMeasure(path, false);
                                    Path trace = new Path();
                                    if (measure.getSegment(0, measure.getLength() * traceProgress[0], trace, true)) {
                                        canvas.drawPath(trace, advancePaint);
                                    }
                                }
                            }
                        }
                    }
                };
        tree.addView(connectors, new FrameLayout.LayoutParams(width, height));
        if (!advancing.isEmpty()) {
            android.animation.ValueAnimator trace = android.animation.ValueAnimator.ofFloat(0f, 1f);
            trace.setDuration(480);
            trace.addUpdateListener(value -> {
                traceProgress[0] = (float) value.getAnimatedValue();
                connectors.invalidate();
            });
            trace.start();
        }
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
            boolean highlighted = match.optBoolean("onMyRoute") || yours && state.equals("active");
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
            if (destinations.contains(round + ":" + match.optInt("slot"))) {
                GradientDrawable glow = shape(tone(0xffEBF5E2, 0xff3B4B3C), 16);
                glow.setStroke(dp(2), tone(0xff447B40, GREEN));
                tile.setBackground(glow);
                tile.setScaleX(.96f);
                tile.setScaleY(.96f);
                tile.animate().scaleX(1).scaleY(1).setStartDelay(420).setDuration(320)
                        .withEndAction(() -> tile.setBackground(background)).start();
            }

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
                            && yours
                            && state.equals("active")
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
            if (!myMatchId.isEmpty() && myMatchId.equals(match.optString("id"))) {
                addHomeMatchButton(summary, "Jump to my match", () -> {
                    Rect bounds = new Rect();
                    tile.getDrawingRect(bounds);
                    bodyScroll.offsetDescendantRectToMyCoords(tile, bounds);
                    bodyScroll.smoothScrollTo(0, Math.max(0, bounds.top - dp(16)));
                    scroll.smoothScrollTo(Math.max(
                            0, tile.getLeft() - (scroll.getWidth() - tile.getWidth()) / 2), 0);
                });
            }
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
        if ("report".equals(g.optString("pendingAction"))) {
            detail = "Result saved on this phone · waiting to sync";
        } else if (state.equals("queued")) detail = "Saved on this phone · invite sends when online";
        else if (state.equals("invited")) {
            if (uid.equals(g.optString("playerBUid")))
                detail = a + " challenged you · accept to start the timer";
            else detail = "Waiting for " + b + " to accept";
        } else if (state.equals("active")) detail = "Match in progress";
        else if (state.equals("awaiting_confirmation")) {
            boolean playerA = uid.equals(g.optString("playerAUid"));
            String myReport = g.optString(playerA ? "reportAWinnerUid" : "reportBWinnerUid");
            String opponent = playerA ? b : a;
            detail =
                    myReport.isEmpty()
                            ? reportedWinnerLabel(g)
                            : "Waiting for " + opponent + " to confirm the result";
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
            String reportKey = uid.equals(g.optString("playerAUid"))
                    ? "reportAWinnerUid" : "reportBWinnerUid";
            boolean needsConfirmation = state.equals("awaiting_confirmation")
                    && g.optString(reportKey).isEmpty() && !opponentReport(g).isEmpty()
                    && !"report".equals(g.optString("pendingAction"));
            if (needsConfirmation) {
                addResultConfirmation(c, g, closeMatchDialog);
                return;
            }
            TextView result = button("Open match timer");
            LinearLayout.LayoutParams rp = lp(-1, -2);
            rp.topMargin = dp(10);
            c.addView(result, rp);
            result.setOnClickListener(
                    v -> {
                        closeMatchDialog.run();
                        if (isOwnStartedGame(g)) openMatchTimer(g);
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
        choosePlayer("Choose an opponent", Collections.singleton(uid), opponent ->
                new AlertDialog.Builder(this)
                        .setTitle("Send 1v1 invitation?")
                        .setMessage("Invite " + opponent.name + ". The match starts when they accept.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Send invite", (confirmation, button) -> createInvite(opponent))
                        .show());
    }

    private interface PlayerSelection {
        void select(OfficeMember player);
    }

    private void choosePlayer(String heading, Set<String> excluded, PlayerSelection selection) {
        ArrayList<OfficeMember> choices = new ArrayList<>();
        for (OfficeMember m : members) if (!excluded.contains(m.uid)) choices.add(m);
        if (choices.isEmpty()) {
            toast("Coworkers appear here after they create their office profiles.");
            return;
        }
        Collections.sort(choices, (first, second) -> first.name.compareToIgnoreCase(second.name));
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = vertical();
        pad(panel, 20, 18, 20, 16);
        GradientDrawable surface = shape(CARD, 24);
        surface.setStroke(dp(1), LINE);
        panel.setBackground(surface);
        panel.setFocusableInTouchMode(true);
        panel.addView(text(heading, 22, INK, true));
        panel.addView(spacer(10));
        EditText search = input("Search players", false);
        search.setContentDescription("Search players");
        GradientDrawable field = shape(BG, 12);
        field.setStroke(dp(1), LINE);
        search.setBackground(field);
        pad(search, 12, 0, 12, 0);
        panel.addView(search, lp(-1, 48));
        panel.addView(spacer(8));

        FrameLayout roster = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setDivider(null);
        roster.addView(list, new FrameLayout.LayoutParams(-1, -1));
        TextView empty = text("No players match your search.", 14, MUTED, false);
        empty.setGravity(Gravity.CENTER);
        roster.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        panel.addView(roster, new LinearLayout.LayoutParams(-1, 0, 1));
        ArrayList<OfficeMember> visible = new ArrayList<>(choices);
        ArrayAdapter<OfficeMember> adapter = new ArrayAdapter<OfficeMember>(
                this, android.R.layout.simple_list_item_1, visible) {
            @Override
            public View getView(int position, View unused, ViewGroup parent) {
                OfficeMember player = getItem(position);
                LinearLayout row = horizontal();
                row.setGravity(Gravity.CENTER_VERTICAL);
                pad(row, 6, 7, 8, 7);
                row.setMinimumHeight(dp(60));
                row.addView(avatar(player.name, player), lp(42, 42));
                TextView name = text(player.name, 15, INK, true);
                LinearLayout.LayoutParams namePosition = new LinearLayout.LayoutParams(0, -2, 1);
                namePosition.leftMargin = dp(12);
                row.addView(name, namePosition);
                row.addView(text("›", 23, MUTED, false));
                return row;
            }
        };
        list.setAdapter(adapter);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim().toLowerCase(Locale.ROOT);
                visible.clear();
                for (OfficeMember player : choices) {
                    if (player.name.toLowerCase(Locale.ROOT).contains(query)) visible.add(player);
                }
                adapter.notifyDataSetChanged();
                list.setSelection(0);
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {}
        });
        TextView cancel = secondaryProfileButton("Cancel");
        cancel.setOnClickListener(view -> dialog.dismiss());
        panel.addView(cancel, lp(-1, -2));
        list.setOnItemClickListener((parent, view, position, id) -> {
            OfficeMember opponent = adapter.getItem(position);
            dialog.dismiss();
            selection.select(opponent);
        });
        dialog.setContentView(panel);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(420)),
                    Math.min(getResources().getDisplayMetrics().heightPixels * 3 / 4,
                            dp(Math.min(choices.size(), 5) * 60 + 160)));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        panel.requestFocus();
    }

    private void startDoubles() {
        if (members.size() < 4) {
            toast("Four registered players are needed for 2v2.");
            return;
        }
        chooseDoublesPlayers(new ArrayList<>());
    }

    private void chooseDoublesPlayers(ArrayList<OfficeMember> picks) {
        String[] headings = {"Choose your teammate", "Choose the first opponent", "Choose the second opponent"};
        Set<String> excluded = new HashSet<>();
        excluded.add(uid);
        for (OfficeMember player : picks) excluded.add(player.uid);
        choosePlayer(headings[picks.size()], excluded, player -> {
            picks.add(player);
            if (picks.size() < headings.length) {
                chooseDoublesPlayers(picks);
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Send 2v2 invitations?")
                    .setMessage(currentName() + " & " + picks.get(0).name + " vs "
                            + picks.get(1).name + " & " + picks.get(2).name)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Send invites", (dialog, button) -> {
                        try {
                            postServer("/doubles/invite", new JSONObject()
                                    .put("id", UUID.randomUUID().toString())
                                    .put("partnerUid", picks.get(0).uid)
                                    .put("opponentUid", picks.get(1).uid)
                                    .put("opponentPartnerUid", picks.get(2).uid), "2v2 invitations sent.");
                        } catch (JSONException error) {
                            toast(errorText(error));
                        }
                    }).show();
        });
    }

    private JSONObject findDoubles(String id) {
        for (int i = 0; i < doubles.length(); i++) {
            JSONObject game = doubles.optJSONObject(i);
            if (game != null && id.equals(game.optString("id"))) return game;
        }
        return null;
    }

    private int doublesPlayerIndex(JSONObject game, String playerId) {
        JSONArray seats = game.optJSONArray("players");
        if (seats == null) return -1;
        for (int i = 0; i < seats.length(); i++) {
            JSONObject player = seats.optJSONObject(i);
            if (player != null && playerId.equals(player.optString("uid"))) return i;
        }
        return -1;
    }

    private void doublesCard(JSONObject game) {
        JSONArray seats = game.optJSONArray("players");
        if (seats == null || seats.length() != 4) return;
        JSONObject[] players = new JSONObject[4];
        int accepted = 0, reported = 0;
        Set<Integer> reports = new HashSet<>();
        for (int i = 0; i < players.length; i++) {
            players[i] = seats.optJSONObject(i);
            if (players[i] == null) return;
            if (players[i].optBoolean("accepted")) accepted++;
            if (!players[i].isNull("reportedTeam")) {
                reports.add(players[i].optInt("reportedTeam"));
                reported++;
            }
        }
        String[] teams = {
            players[0].optString("name") + " & " + players[1].optString("name"),
            players[2].optString("name") + " & " + players[3].optString("name")
        };
        String status = game.optString("status");
        boolean running = "active".equals(status) || "awaiting_confirmation".equals(status);
        boolean confirmed = "confirmed".equals(status);
        String path = "/doubles/" + game.optString("id");
        int me = doublesPlayerIndex(game, uid);
        LinearLayout panel = card();
        panel.addView(lineLabel("2V2 DOUBLES"));
        panel.addView(text(teams[0] + " vs " + teams[1], 17, INK, true));
        if (confirmed) {
            panel.addView(text(teams[game.optInt("winnerTeam")] + " won", 15, INK, true));
            panel.addView(text("Duration · " + elapsed(game.optString("startedAt"),
                    game.optString("finishedAt")), 13, MUTED, false));
        } else if (running) {
            String progress = reports.size() > 1 ? "Players reported different winners"
                    : reported == 0 ? "Match in progress"
                    : reported + " of 4 confirmed " + teams[reports.iterator().next()];
            panel.addView(text(progress, 13, MUTED, false));
            String started = game.optString("startedAt");
            TextView clock = text(elapsed(started, ""), 24, tone(0xff285625, GREEN), true);
            panel.addView(clock);
            tableClocks.put(clock, started);
            updateMatchTimer();
            if (me >= 0) {
                for (int team = 0; team < teams.length; team++) {
                    int selected = team;
                    String label = teams[team] + " won";
                    if (players[me].optInt("reportedTeam", -1) == team) label += " ✓";
                    addHomeMatchButton(panel, label, () -> {
                        try {
                            postServer(path + "/report", new JSONObject().put("winnerTeam", selected),
                                    "Result submitted.");
                        } catch (JSONException error) {
                            toast(errorText(error));
                        }
                    });
                }
            }
        } else if ("invited".equals(status)) {
            panel.addView(text(accepted + " of 4 ready", 13, MUTED, false));
            if (me >= 0) {
                if (!players[me].optBoolean("accepted")) addHomeMatchButton(panel, "Accept", () ->
                        postServer(path + "/respond", boolPayload("accept", true), "Invitation accepted."));
                addHomeMatchButton(panel, game.optString("hostUid").equals(uid) ? "Cancel invitation" : "Decline",
                        () -> postServer(path + "/respond", boolPayload("accept", false), "Invitation closed."));
            }
        } else {
            panel.addView(text("Invitation closed", 13, MUTED, false));
        }
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

    private String opponentReport(JSONObject game) {
        return game.optString(uid.equals(game.optString("playerAUid"))
                ? "reportBWinnerUid" : "reportAWinnerUid");
    }

    private String reportedWinnerLabel(JSONObject game) {
        boolean playerA = uid.equals(game.optString("playerAUid"));
        String opponent = game.optString(playerA ? "playerBName" : "playerAName");
        String winner = opponentReport(game);
        String name = game.optString(winner.equals(game.optString("playerAUid"))
                ? "playerAName" : "playerBName");
        return opponent + " reported " + name + " as the winner";
    }

    private void addResultConfirmation(LinearLayout panel, JSONObject game, Runnable close) {
        String winner = opponentReport(game);
        String a = game.optString("playerAUid");
        boolean aReportedWinner = winner.equals(a);
        String alternative = aReportedWinner ? game.optString("playerBUid") : a;
        String name = game.optString(aReportedWinner ? "playerBName" : "playerAName");
        addHomeMatchButton(panel, "Confirm winner", () -> sendWinner(game, winner, close));
        addHomeMatchButton(panel, "Dispute", () -> new AlertDialog.Builder(this)
                .setTitle("Dispute result?")
                .setMessage("Report " + name + " as the winner and send the result for admin review.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send dispute", (dialog, which) -> sendWinner(game, alternative, close))
                .show());
    }

    private void sendWinner(JSONObject game, String winner, Runnable close) {
        try {
            close.run();
            gameAction(game.optString("id"), "report", new JSONObject()
                    .put("winnerUid", winner).put("expectedOpponentWinnerUid", opponentReport(game)));
        } catch (JSONException error) {
            toast(errorText(error));
        }
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
                    if (action.equals("report")) {
                        local.put("pendingWinnerUid", body.optString("winnerUid"));
                        local.put("pendingExpectedWinnerUid", body.optString("expectedOpponentWinnerUid"));
                    }
                    if (action.equals("respond"))
                        local.put("pendingAccept", body.optBoolean("accept"));
                    save();
                    show();
                    toast(action.equals("report") ? "Result saved on this phone · waiting to sync"
                            : "Change saved on this phone · waiting to sync");
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
                        : "Result sent.");
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
                                remote.put("pendingExpectedWinnerUid", queued.optString("pendingExpectedWinnerUid"));
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
        if (!mine && isAdmin) addHomeMatchButton(overview, "Remove player", () -> showAccountDeletion(me));
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
        if (doublesAvailable) {
            int teamWins = 0, teamLosses = 0;
            for (int i = 0; i < doubles.length(); i++) {
                JSONObject game = doubles.optJSONObject(i);
                if (game == null || !"confirmed".equals(game.optString("status"))) continue;
                int position = doublesPlayerIndex(game, profileUid);
                if (position < 0) continue;
                if (position / 2 == game.optInt("winnerTeam")) teamWins++;
                else teamLosses++;
            }
            LinearLayout teamStats = card();
            teamStats.addView(text("2v2 stats", 19, INK, true));
            statRow(teamStats, "Confirmed matches", String.valueOf(teamWins + teamLosses));
            statRow(teamStats, "Wins · losses", teamWins + " · " + teamLosses);
        }
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

    private ProfileEditor profileEditor;

    void editProfile() {
        profileEditor = new ProfileEditor();
        profileEditor.show();
    }

    private final class ProfileEditor {
        private final Dialog dialog = new Dialog(MainActivity.this);
        private final EditText name = input("Display name", false);
        private final TextView message = text("", 12, MUTED, false);
        private final TextView saveButton = button("Save changes");
        private final TextView choose = secondaryProfileButton("Change photo");
        private final FrameLayout preview = avatar(currentName(), memberByUid(uid));
        private final ImageView selectedPreview = new ImageView(MainActivity.this);
        private final String owner = uid;
        private File photo;

        void show() {
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout panel = vertical();
            pad(panel, 22, 20, 22, 18);
            GradientDrawable surface = shape(CARD, 24);
            surface.setStroke(dp(1), LINE);
            panel.setBackground(surface);
            panel.setFocusableInTouchMode(true);
            panel.addView(text("Edit profile", 23, INK, true));
            panel.addView(spacer(18));

            LinearLayout portrait = horizontal();
            selectedPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            selectedPreview.setVisibility(View.GONE);
            preview.addView(selectedPreview, new FrameLayout.LayoutParams(-1, -1));
            portrait.addView(preview, lp(76, 76));
            LinearLayout photoControls = vertical();
            pad(photoControls, 14, 0, 0, 0);
            choose.setOnClickListener(view -> pickPhoto());
            photoControls.addView(choose, lp(-2, -2));
            photoControls.addView(spacer(4));
            photoControls.addView(text(
                    "JPEG, PNG or WebP · up to " + MAX_PHOTO_MB + " MB", 11, MUTED, false));
            portrait.addView(photoControls, new LinearLayout.LayoutParams(0, -2, 1));
            panel.addView(portrait);
            panel.addView(spacer(20));

            panel.addView(text("Display name", 13, INK, true));
            panel.addView(spacer(6));
            name.setText(sessionName);
            name.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(48)});
            name.setContentDescription("Display name");
            GradientDrawable field = shape(BG, 13);
            field.setStroke(dp(1), LINE);
            name.setBackground(field);
            pad(name, 14, 0, 14, 0);
            panel.addView(name, lp(-1, 52));
            message.setVisibility(View.GONE);
            panel.addView(message, lp(-1, -2));
            panel.addView(spacer(16));

            LinearLayout actions = horizontal();
            TextView cancel = secondaryProfileButton("Cancel");
            cancel.setOnClickListener(view -> dialog.dismiss());
            actions.addView(cancel, lp(-2, -2));
            LinearLayout.LayoutParams savePosition = new LinearLayout.LayoutParams(0, -2, 1);
            savePosition.leftMargin = dp(10);
            actions.addView(saveButton, savePosition);
            saveButton.setOnClickListener(view -> saveEdits());
            panel.addView(actions);
            ScrollView scroll = new ScrollView(MainActivity.this);
            scroll.setFillViewport(true);
            scroll.addView(panel);
            dialog.setContentView(scroll);
            dialog.setOnDismissListener(ignored -> {
                if (photo != null) photo.delete();
                if (profileEditor == this) profileEditor = null;
            });
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(32), dp(420)), -2);
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
            panel.requestFocus();
        }

        void selectPhoto(Uri uri) {
            String type = getContentResolver().getType(uri);
            if (!"image/jpeg".equals(type) && !"image/png".equals(type) && !"image/webp".equals(type)) {
                status("Choose a JPEG, PNG, or WebP photo.", true);
                return;
            }
            saveButton.setEnabled(false);
            choose.setEnabled(false);
            status("Preparing photo…", false);
            io.execute(() -> {
                File file = null;
                try {
                    file = readProfilePhoto(uri, type);
                    Bitmap bitmap = decodeAvatar(file);
                    if (bitmap == null) throw new IOException("Could not preview this photo.");
                    File selected = file;
                    runOnUiThread(() -> {
                        if (profileEditor != this || !dialog.isShowing()) {
                            selected.delete();
                            return;
                        }
                        if (photo != null) photo.delete();
                        photo = selected;
                        selectedPreview.setImageBitmap(bitmap);
                        selectedPreview.setVisibility(View.VISIBLE);
                        saveButton.setEnabled(true);
                        choose.setEnabled(true);
                        status("Photo ready to save.", false);
                    });
                } catch (Exception error) {
                    if (file != null) file.delete();
                    runOnUiThread(() -> {
                        if (profileEditor != this) return;
                        saveButton.setEnabled(true);
                        choose.setEnabled(true);
                        status(errorText(error), true);
                    });
                }
            });
        }

        private void status(String value, boolean error) {
            message.setTextColor(error ? tone(0xff923E32, 0xffFFD0C6) : MUTED);
            message.setText(value);
            message.setVisibility(View.VISIBLE);
        }

        private void saveEdits() {
            String value = name.getText().toString().trim();
            if (value.isEmpty()) {
                status("Enter a display name.", true);
                name.requestFocus();
                return;
            }
            if (!owner.equals(uid) || token.isEmpty()) {
                dialog.dismiss();
                return;
            }
            boolean photoChanged = photo != null;
            if (photoChanged) {
                File previous = pendingPhoto(owner);
                prefs.edit().putBoolean("pendingPhoto", true)
                        .putString("pendingPhotoMime", "image/jpeg")
                        .putString("pendingPhotoOwner", owner)
                        .putString("pendingPhotoFile", photo.getName()).apply();
                photo = null;
                if (previous != null) previous.delete();
            }
            dialog.dismiss();
            if (!value.equals(sessionName)) saveProfileName(value);
            else if (photoChanged) {
                MainActivity.this.show();
                syncNow();
            } else return;
            toast(online ? "Profile saved." : "Profile saved on this phone · waiting to sync");
        }
    }

    private TextView secondaryProfileButton(String label) {
        TextView control = button(label);
        control.setTextColor(INK);
        GradientDrawable outline = shape(CARD, 14);
        outline.setStroke(dp(1), LINE);
        control.setBackground(outline);
        return control;
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
        if (profileEditor != null) profileEditor.selectPhoto(data.getData());
    }

    private File readProfilePhoto(Uri uri, String mime) throws IOException {
        File file = File.createTempFile("photo-source-", ".image", getCacheDir());
        try {
            try (InputStream in = getContentResolver().openInputStream(uri);
                    OutputStream out = new FileOutputStream(file)) {
                if (in == null) throw new IOException("Could not open the selected photo.");
                OfficeApi.copy(in, out, MAX_PHOTO_BYTES);
            } catch (IOException error) {
                if ("File is too large.".equals(error.getMessage())) throw new IOException(PHOTO_LIMIT_MESSAGE);
                throw error;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (!mime.equals(bounds.outMimeType)) {
                throw new IOException("Choose a valid " + mime.substring(6).toUpperCase(Locale.ROOT) + " photo.");
            }
            return writeProfilePhoto(decodeProfilePhoto(file, bounds));
        } finally {
            file.delete();
        }
    }

    private Bitmap decodeProfilePhoto(File file, BitmapFactory.Options bounds) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), (decoder, info, source) -> {
                int width = info.getSize().getWidth();
                int height = info.getSize().getHeight();
                float scale = Math.min(1f, (float) PROFILE_PHOTO_EDGE / Math.max(width, height));
                decoder.setTargetSize(Math.max(1, Math.round(width * scale)),
                        Math.max(1, Math.round(height * scale)));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (2 * options.inSampleSize)
                >= PROFILE_PHOTO_EDGE) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) throw new IOException("Could not read this photo.");
        if (!"image/jpeg".equals(bounds.outMimeType)) return bitmap;
        android.media.ExifInterface exif = new android.media.ExifInterface(file.getAbsolutePath());
        int orientation = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
        Matrix transform = new Matrix();
        switch (orientation) {
            case 2: transform.setScale(-1, 1); break;
            case 3: transform.setRotate(180); break;
            case 4: transform.setScale(1, -1); break;
            case 5: transform.setRotate(90); transform.postScale(-1, 1); break;
            case 6: transform.setRotate(90); break;
            case 7: transform.setRotate(-90); transform.postScale(-1, 1); break;
            case 8: transform.setRotate(-90); break;
            default: return bitmap;
        }
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(),
                transform, true);
        if (rotated != bitmap) bitmap.recycle();
        return rotated;
    }

    private File writeProfilePhoto(Bitmap photo) throws IOException {
        float scale = Math.min(1f, (float) PROFILE_PHOTO_EDGE
                / Math.max(photo.getWidth(), photo.getHeight()));
        int width = Math.max(1, Math.round(photo.getWidth() * scale));
        int height = Math.max(1, Math.round(photo.getHeight() * scale));
        Bitmap flattened = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(flattened);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(photo, null, new Rect(0, 0, width, height),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        photo.recycle();
        File file = null;
        boolean written = false;
        try {
            file = File.createTempFile("pending-photo-", ".image", getFilesDir());
            try (OutputStream out = new FileOutputStream(file)) {
                if (!flattened.compress(Bitmap.CompressFormat.JPEG, PROFILE_PHOTO_QUALITY, out)) {
                    throw new IOException("Could not prepare this photo.");
                }
            }
            written = true;
            return file;
        } finally {
            flattened.recycle();
            if (!written && file != null) file.delete();
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
                + doublesAvailable
                + doubles.toString()
                + "|"
                + seasonData.toString()
                + "|"
                + playingNow.toString()
                + "|"
                + weeklyHighlights.toString()
                + "|"
                + hasNextUp
                + "|"
                + nextUp
                + "|"
                + isAdmin;
    }

    void syncNow() {
        syncNow(null);
    }

    private void syncNow(Runnable refreshFinished) {
        if (token.isEmpty() || serverUrl.isEmpty() || syncBusy) {
            if (refreshFinished != null) refreshFinished.run();
            return;
        }
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
                                    if (!was || changed) showAfterRefresh(refreshFinished);
                                    else if (refreshFinished != null) refreshFinished.run();
                                    if (rejectedChange != null) toast(rejectedChange);
                                });
                    } catch (Exception e) {
                        if (e instanceof OfficeApi.ApiException
                                && ((OfficeApi.ApiException) e).code == 401 && guestExpiresAt > 0) {
                            runOnUiThread(() -> {
                                if (!auth.equals(token)) return;
                                clearDeletedAccountLocally();
                                toast("Your guest account is no longer active.");
                            });
                            return;
                        }
                        online = false;
                        connectionChecked = true;
                        runOnUiThread(
                                () -> {
                                    if (was && poolTableStatus != null) showAfterRefresh(refreshFinished);
                                    else {
                                        updateStatus();
                                        if (refreshFinished != null) refreshFinished.run();
                                    }
                                });
                    } finally {
                        syncBusy = false;
                    }
                });
    }

    private void showAfterRefresh(Runnable refreshFinished) {
        if (refreshFinished == null) {
            showSyncedPage();
            return;
        }
        refreshFinished.run();
        View refreshedRoot = root;
        handler.postDelayed(() -> {
            if (root == refreshedRoot && !isFinishing() && !isDestroyed()) showSyncedPage();
        }, PoolMotion.enabled(this) ? 600 : 0);
    }

    private void showSyncedPage() {
        int previousPage = page;
        ScrollView oldBody = bodyScroll;
        int vertical = oldBody != null && oldBody.isAttachedToWindow() ? oldBody.getScrollY() : 0;
        int horizontal = bracketScroll == null ? 0 : bracketScroll.getScrollX();
        show();
        ScrollView refreshedBody = bodyScroll;
        if (page != previousPage || refreshedBody == oldBody || !timerGameId.isEmpty()) return;
        HorizontalScrollView refreshedBracket = bracketScroll;
        refreshedBody.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                refreshedBody.getViewTreeObserver().removeOnPreDrawListener(this);
                if (bodyScroll == refreshedBody) {
                    refreshedBody.scrollTo(0, vertical);
                    if (refreshedBracket != null) refreshedBracket.scrollTo(horizontal, 0);
                }
                return true;
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
                    String expected = game.optString("pendingExpectedWinnerUid");
                    if (!expected.isEmpty()) body.put("expectedOpponentWinnerUid", expected);
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
        lastSyncAt = System.currentTimeMillis();
        guestExpiresAt = snap.optLong("guestExpiresAt", guestExpiresAt);
        connectionChecked = true;
        hasNextUp = snap.has("nextUp");
        nextUp = snap.optJSONObject("nextUp");
        long serverTimeMs = snap.optLong("serverTimeMs");
        if (serverTimeMs > 0) {
            serverClockOffsetMs = serverTimeMs - SystemClock.elapsedRealtime();
            hasServerClock = true;
        }
        isAdmin = snap.optBoolean("isAdmin", isAdmin);
        JSONArray playing = snap.optJSONArray("playingNow");
        JSONArray teamGames = snap.optJSONArray("doubles");
        doublesAvailable = teamGames != null;
        doubles = doublesAvailable ? teamGames : new JSONArray();
        if (motionSnapshotSeen) {
            tableMotionPending |= playing != null && !playing.toString().equals(playingNow.toString());
        }
        hasTableStatus = playing != null;
        playingNow = playing == null ? new JSONArray() : playing;
        JSONArray highlights = snap.optJSONArray("weeklyHighlights");
        weeklyHighlights = highlights == null ? new JSONArray() : highlights;
        JSONArray history = snap.optJSONArray("ratingHistory");
        if (history != null) ratingHistory = history;
        JSONArray remoteGames = snap.optJSONArray("games");
        if (remoteGames != null) games = remoteGames;
        JSONObject remoteSeason = snap.optJSONObject("season");
        if (motionSnapshotSeen) trackBracketAdvances(seasonData, remoteSeason);
        seasonData = remoteSeason == null ? new JSONObject() : remoteSeason;
        JSONArray remoteMembers = snap.optJSONArray("members"),
                remoteMatches = snap.optJSONArray("matches");
        ArrayList<OfficeMember> next = new ArrayList<>();
        if (remoteMembers != null)
            for (int i = 0; i < remoteMembers.length(); i++)
                next.add(OfficeMember.fromJson(remoteMembers.getJSONObject(i)));
        Set<String> activeMembers = new HashSet<>();
        for (OfficeMember member : next) activeMembers.add(member.uid);
        for (OfficeMember member : members) {
            if (!activeMembers.contains(member.uid)) {
                avatarFile(member.uid).delete();
                prefs.edit().remove("avatarRevision:" + member.uid).apply();
            }
        }
        members = next;
        JSONArray combined = new JSONArray();
        Set<String> knownResults = new HashSet<>();
        if (motionSnapshotSeen) {
            for (int i = 0; i < matches.length(); i++) {
                JSONObject known = matches.optJSONObject(i);
                if (known != null) knownResults.add(known.optString("id"));
            }
        }
        if (remoteMatches != null) {
            for (int i = 0; i < remoteMatches.length(); i++) {
                JSONObject match = toLocalMatch(remoteMatches.getJSONObject(i));
                if (motionSnapshotSeen && !knownResults.contains(match.optString("id"))) {
                    newResults.add(match.optString("id"));
                }
                combined.put(match);
            }
        }
        matches = combined;
        motionSnapshotSeen = true;
    }

    private void trackBracketAdvances(JSONObject previous, JSONObject incoming) {
        JSONObject oldCup = previous.optJSONObject("tournament");
        JSONObject newCup = incoming == null ? null : incoming.optJSONObject("tournament");
        if (oldCup == null || newCup == null || !oldCup.optString("id").equals(newCup.optString("id"))) {
            bracketAdvances.clear();
            return;
        }
        JSONArray oldTiles = oldCup.optJSONArray("matches"), newTiles = newCup.optJSONArray("matches");
        if (oldTiles == null || newTiles == null) return;
        Set<String> completed = new HashSet<>();
        for (int i = 0; i < oldTiles.length(); i++) {
            JSONObject tile = oldTiles.optJSONObject(i);
            if (tile != null && "completed".equals(tile.optString("status"))) {
                completed.add(tile.optString("id"));
            }
        }
        for (int i = 0; i < newTiles.length(); i++) {
            JSONObject tile = newTiles.optJSONObject(i);
            if (tile != null && "completed".equals(tile.optString("status"))
                    && !completed.contains(tile.optString("id"))
                    && tile.optInt("round") < newCup.optInt("roundCount")) {
                bracketAdvances.add(tile);
            }
        }
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
        int winnerElo = j.optInt("winnerEloAfter", -1);
        if (winnerElo >= 0) m.put("winnerEloAfter", winnerElo);
        return m;
    }

    void updateStatus() {
        updatePoolTableStatus();
        if (syncLabel != null) syncLabel.setText(connectionText());
        if (statusChip == null) return;
        statusChip.setText(online ? "● ONLINE" : connectionText());
        statusChip.setTextColor(
                online ? tone(0xff3D7134, 0xffC1EAAE) : tone(0xff8B4639, 0xffFFD0C6));
        statusChip.setBackground(
                shape(online ? tone(0xffEAF4DF, 0xff37483C) : tone(0xffF8E9E5, 0xff503D3C), 14));
    }

    private String connectionText() {
        if (lastSyncAt == 0) return online ? "Connected" : connectionChecked ? "Offline · waiting to reconnect" : "Connecting…";
        String time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                .format(new Date(lastSyncAt));
        return (online ? "Last synced at " : "Offline · last synced at ") + time;
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
        if (profileEditor != null) profileEditor.dialog.dismiss();
        avatarIo.shutdownNow();
        super.onDestroy();
    }

    void beginPolling() {
        if (poll != null) handler.removeCallbacks(poll);
        poll =
                new Runnable() {
                    @Override
                    public void run() {
                        long now = hasServerClock ? SystemClock.elapsedRealtime() + serverClockOffsetMs
                                : System.currentTimeMillis();
                        if (guestExpiresAt > 0 && now >= guestExpiresAt * 1000) {
                            clearDeletedAccountLocally();
                            toast("Your guest account expired.");
                        }
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
