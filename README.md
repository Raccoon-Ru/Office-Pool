# Cue Club (Office Pool)

A native Android pool league tracker for the Acumensoft office. The app has five main screens: Home, Results, Players, Table, and Profile. Profiles and confirmed results sync through the office PC server. The app keeps working offline and queues changes for sync when the server is reachable again. Profile photos can be JPEG, PNG, or WebP and up to 15 MB. The app resizes them to at most 512 pixels on the longest edge and compresses them as JPEG before uploading.

Tap your name or photo at the top right of Home to open **My profile**. It shows your Elo, personal best, rating progression, recent form, and match stats. **Settings** sits at the top right of My profile and contains the connection status, theme, and sign-out controls. Tap a coworker’s photo on Players or on the Home table card to see their rating and match stats. Dark is the default, and your preferred theme is saved on that device.

## Match flow

Home shows pool table availability, who is playing, your next league matchup, incoming 1v1 invitations, and whether Android allows match alerts. The table shows a game from acceptance until the first result report, when the on-table play is over. Accepted 1v1s open a VS intro and a live timer for both players. Tap the alert status to open Android notification settings.

1. A player challenges a coworker from the office player list.
2. The coworker accepts before the game timer starts.
3. Both players report the winner. Stats and season brackets update only when their reports agree; conflicting reports are available to the admin for review.

The phone runs a foreground inbox service to check for invitations and result confirmations while Cue Club is closed. Android shows an ongoing notification while this service is active. This uses additional battery and requires notification permission. After a device reboot, the service starts again after the app has been opened and notifications are allowed.

## Office leagues

Only the configured admin account can open **Manage office league** from Home. Enter a league name and select an end date. Starting the league randomly draws every registered profile into a single-elimination bracket and assigns byes when needed. Players who register after the draw can join the next league.

The bracket appears on Home for everyone. When your matchup is ready, tap the opponent profile photo and choose **Play match** to send an invitation. When the opponent accepts, both players see the live league timer as soon as their apps sync. The timer keeps running until both players report the same winner. The bracket then advances and the final duration appears on the Results tab. The admin can resolve disputed results. A confirmed final crowns the champion and closes the league. The admin can also end a league early. An unfinished league closes at the end of its selected day, and the existing bracket stays visible until another league starts.

Use **Challenge a player to 1v1** for regular matches, including against someone you are paired with in the league. Only results submitted through a league matchup advance the bracket.

## Elo ratings

The Table ranks players by Elo and still shows wins and losses. Everyone starts at 1,200. Confirmed league and 1v1 results count toward the same rating, including matches already in the shared scorebook. Wins against higher-rated opponents earn more points. The loser drops by the same amount, and Results shows the change for each match.

## PC server

The PC hosts the shared data and must be powered on, connected to the internet, and signed in to Tailscale for the phones to show Online. `server/start_online.ps1` starts the API and Tailscale Funnel. The Windows task **Cue Club Online Server** runs it after the PC user signs in. Tailscale Funnel must remain configured for the HTTPS address used by the app.

Server data and profile photos are stored under `server/data`. The Windows task **Cue Club Daily Backup** runs at 18:00 Johannesburg time while the PC is available and the account is signed in. It saves verified ZIP snapshots of the database and its profile photos in `Documents/Cue Club Backups`. Run `python server/backup_office_pool.py` for a manual backup. Run `python server/backup_office_pool.py --verify <archive>` for a restore check in a temporary directory. The API binds to `127.0.0.1:8765` and is exposed to phones through the configured Funnel address.

## iPhone Home Screen app

Open the Cue Club web link in Safari, tap **Share**, then **Add to Home Screen**. Choose **Open as Web App** when Safari shows that option. Open Cue Club from the new icon and sign in with your existing office account or create a profile with the office join code. iPhone and Android use the same profiles, matches, and standings.

After installing, tap **Enable match alerts** on Home to grant notification permission. The Home Screen app uses Apple Web Push for invitations and result updates. Its page can reopen offline, but sign-in, profile changes, and match actions need the office PC to be reachable.

On the PC, install notification support with `python -m pip install -r server/requirements-web.txt`. The existing server serves the web app at `/cue-club/` when `web_app_enabled` is set to `true` in the server configuration. The notification signing key and subscriptions are saved in the Cue Club database.

## Render and Supabase deployment

Cue Club supports one Render Python web service backed by Supabase Postgres. The backend creates a private `cue_club` schema and stores profile photos there as binary data, so the app does not depend on a Render disk or a separate public storage bucket. Keep the database connection server-side.

Configure the existing Render service from the repository root.

- Build command `python -m pip install -r server/requirements-render.txt`
- Start command `python -m server.office_pool_server`
- Health check path `/health`
- Environment `DATABASE_URL` from Supabase **Connect > Session pooler**, with your database password filled in, and `OFFICE_POOL_JOIN_CODE` set to the office join code. Optionally configure `OFFICE_POOL_ADMIN_EMAIL` and `OFFICE_POOL_DOMAIN` if they differ from the current office settings.
- Use an always-on Render service plan if instant availability is required. The Render free plan can sleep while idle.

The server uses Render's `PORT`, enables the iPhone web app at `/cue-club/`, and checks the database on `/health`. PostgreSQL connections verify the TLS certificate against the system or configured CA bundle. To use a Supabase CA file, set `OFFICE_POOL_DB_SSLROOTCERT` to its path on Render.

For an existing PC installation, create a fresh verified ZIP with `server/backup_office_pool.py`. Run `python -m server.migrate_to_supabase <backup.zip>` with `DATABASE_URL` set securely in the migration environment. The command imports existing accounts, sessions, confirmed results, leagues and photos into an empty target. It cancels the import if the target already has Cue Club rows. Schedule the final backup and import during a pause in match activity to avoid recording results in two places. After verification, share the new Render `/cue-club/` link. iPhone users will sign in again and enable alerts on the new web address.

Cue Club's Android build uses the Render URL by default. Set `-PcueClubServerUrl=https://YOUR-SERVICE.onrender.com` when building for a different server.

## Publish an Android update

Increase `versionCode` and `versionName` in `app/build.gradle`. On the signing PC, run `powershell -File server/publish_android_update.ps1` from the repository root. Use the same Android signing key as the installed APKs. Commit the generated `server/releases/cue-club.apk` and `server/releases/android.json` together with the version change and push to `main`. Render serves both after deployment. Users on an update-aware version get a download prompt and approve installation through Android.

The first update-aware APK is available at `https://office-pool.onrender.com/updates/android/cue-club.apk`. Install it once on phones that still use an older version. The iPhone web app prompts users to reload when its deployed files change.

## Build the APK

From the `my_app` folder, run:

```powershell
.\gradlew.bat assembleDebug
```

The installable debug APK is written to `app\build\outputs\apk\debug\app-debug.apk`. A copy named `OfficePool-debug.apk` is also kept in the project root.

## Install on a connected Android device

Enable USB debugging and connect the phone, then run:

```powershell
.\gradlew.bat installDebug
```
