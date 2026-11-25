package com.officepool;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MatchInboxService extends Service {
    static final String LEAGUE_NOTIFICATION = "officepool.leagueNotification";
    static final String MATCH_NOTIFICATION_ID = "officepool.matchNotificationId";
    static final String CHANNEL = "cue_club_matches";
    private static final long POLL_INTERVAL_MS = 30_000;
    private static final int ONGOING_ID = 7101;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private boolean stopped = false, busy = false;
    private final Runnable poll = this::pollInbox;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(MainActivity.PREFERENCES_NAME, MODE_PRIVATE);
        createChannel();
        Notification ongoing =
                builder()
                        .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                        .setContentTitle("Cue Club is watching for games")
                        .setContentText(
                                "Match invites and result confirmations are checked in the"
                                        + " background.")
                        .setOngoing(true)
                        .setCategory(Notification.CATEGORY_SERVICE)
                        .build();
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(ONGOING_ID, ongoing, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(ONGOING_ID, ongoing);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (prefs.getString("token", "").isEmpty()) {
            stopped = true;
            stopForeground(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        stopped = false;
        handler.removeCallbacks(poll);
        handler.post(poll);
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel channel =
                        new NotificationChannel(
                                CHANNEL,
                                "Cue Club match alerts",
                                NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("Game invitations, timer starts, and result confirmations.");
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification.Builder builder() {
        return Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
    }

    private void pollInbox() {
        if (stopped || busy) return;
        String token = prefs.getString("token", "");
        String base = prefs.getString("serverUrl", "");
        if (token.isEmpty() || base.isEmpty()) {
            handler.postDelayed(poll, POLL_INTERVAL_MS);
            return;
        }
        busy = true;
        io.execute(
                () -> {
                    try {
                        JSONObject snapshot = new OfficeApi(base).get("/sync", token);
                        handler.post(
                                () -> {
                                    if (stopped || !token.equals(prefs.getString("token", "")))
                                        return;
                                    JSONArray games = snapshot.optJSONArray("games");
                                    if (games == null) return;
                                    boolean admin = snapshot.optBoolean("isAdmin", false);
                                    for (int i = 0; i < games.length(); i++) {
                                        notifyIfNeeded(games.optJSONObject(i), admin);
                                    }
                                });
                    } catch (Exception ignored) {
                        // Try again on the next poll.
                    } finally {
                        handler.post(
                                () -> {
                                    busy = false;
                                    if (!stopped) handler.postDelayed(poll, POLL_INTERVAL_MS);
                                });
                    }
                });
    }

    private void notifyIfNeeded(JSONObject game, boolean isAdmin) {
        if (game == null) return;
        String id = game.optString("id"),
                status = game.optString("status"),
                uid = prefs.getString("uid", "");
        String a = game.optString("playerAName", "A coworker"),
                b = game.optString("playerBName", "a coworker");
        boolean isPlayerA = uid.equals(game.optString("playerAUid"));
        boolean isPlayerB = uid.equals(game.optString("playerBUid"));
        boolean isParticipant = isPlayerA || isPlayerB;
        String event = "", title = "", message = "";
        if (status.equals("invited") && isPlayerB) {
            event = "invite";
            title = "Pool game invitation";
            message = a + " challenged you. Tap to review and accept.";
        } else if (status.equals("active") && isParticipant) {
            event = "started";
            title = "Game timer started";
            message = a + " vs " + b + " is underway.";
        } else if (status.equals("awaiting_confirmation")) {
            String myReport =
                    isPlayerA
                            ? game.optString("reportAWinnerUid")
                            : game.optString("reportBWinnerUid");
            if (isParticipant && myReport.isEmpty()) {
                event = "confirm";
                title = "Confirm the game result";
                message =
                        "Your opponent reported "
                                + game.optString("winnerName", "a winner")
                                + ". Tap to confirm or dispute.";
            }
        } else if (status.equals("disputed") && isAdmin) {
            event = "dispute";
            title = "Admin review needed";
            message = a + " vs " + b + " has conflicting results.";
        } else if (status.equals("confirmed") && isParticipant) {
            event = "confirmed";
            title = "Game result agreed";
            message =
                    game.optString("winnerName", "The winner")
                            + " gets the win. Stats are updated.";
        }
        if (event.isEmpty()) return;
        String key = "gameNotice:" + id + ":" + event;
        if (prefs.getBoolean(key, false)) return;
        boolean leagueMatch = !game.optString("tournamentMatchId").isEmpty();
        Intent open =
                new Intent(this, MainActivity.class)
                        .putExtra(LEAGUE_NOTIFICATION, leagueMatch)
                        .putExtra(MATCH_NOTIFICATION_ID, id)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap =
                PendingIntent.getActivity(
                        this,
                        (id + event).hashCode(),
                        open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification =
                builder()
                        .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                        .setContentTitle(leagueMatch ? "League · " + title : title)
                        .setContentText(message)
                        .setStyle(new Notification.BigTextStyle().bigText(message))
                        .setContentIntent(tap)
                        .setAutoCancel(true)
                        .setCategory(Notification.CATEGORY_MESSAGE)
                        .build();
        getSystemService(NotificationManager.class).notify((id + event).hashCode(), notification);
        prefs.edit().putBoolean(key, true).apply();
    }

    @Override
    public void onDestroy() {
        stopped = true;
        handler.removeCallbacks(poll);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
