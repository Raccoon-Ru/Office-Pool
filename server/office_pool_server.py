#!/usr/bin/env python3
"""Private Cue Club API and Home Screen app."""
from __future__ import annotations

import hashlib
import hmac
import json
import mimetypes
import os
import random
import re
import secrets
import sqlite3
import threading
import time
import uuid
from collections import Counter
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

HERE = Path(__file__).resolve().parent
DATA = Path(os.environ.get("OFFICE_POOL_DATA_DIR", HERE / "data")).resolve()
PHOTOS = DATA / "photos"
CONFIG_FILE = Path(
    os.environ.get("OFFICE_POOL_CONFIG", HERE / "server_config.json")
).resolve()
if __package__:
    from . import database, push_delivery
else:
    import database
    import push_delivery

WEB_ROOT = Path(__file__).resolve().parent / "web"
WEB_ASSETS = {
    "/cue-club/": ("index.html", "text/html; charset=utf-8"),
    "/cue-club/style.css": ("style.css", "text/css; charset=utf-8"),
    "/cue-club/app.js": ("app.js", "text/javascript; charset=utf-8"),
    "/cue-club/sw.js": ("sw.js", "text/javascript; charset=utf-8"),
    "/cue-club/manifest.webmanifest": (
        "manifest.webmanifest",
        "application/manifest+json",
    ),
    "/cue-club/icon.jpg": ("icon.jpg", "image/jpeg"),
}
WEB_VERSION = hashlib.sha256(
    b"".join((WEB_ROOT / filename).read_bytes() for filename, _ in WEB_ASSETS.values())
).hexdigest()
ANDROID_RELEASES = HERE / "releases"
ANDROID_APK = ANDROID_RELEASES / "cue-club.apk"
ANDROID_MANIFEST = ANDROID_RELEASES / "android.json"
ANDROID_APK_PATH = "/updates/android/cue-club.apk"
ACCOUNT_DELETION_PATH = "/profiles/me/account-deletion"

ON_RENDER = os.environ.get("RENDER", "").lower() == "true"
HOST = os.environ.get("OFFICE_POOL_HOST", "0.0.0.0" if ON_RENDER else "127.0.0.1")
PORT = int(os.environ.get("PORT", "8765"))
MAX_JSON = 1_000_000
ELO_START = 1200
ELO_K = 32
MAX_PHOTO_MB = 15
MAX_PHOTO = MAX_PHOTO_MB * 1024 * 1024
PHOTO_REJECTION_MARGIN = 64 * 1024
PHOTO_ERROR = f"Choose a JPEG, PNG, or WebP image of {MAX_PHOTO_MB} MB or less."
PHOTO_TYPES = {
    "image/jpeg": ("jpg", b"\xff\xd8\xff"),
    "image/png": ("png", b"\x89PNG\r\n\x1a\n"),
    "image/webp": ("webp", b"RIFF"),
}
PBKDF2_ROUNDS = 310_000
ADMIN_EMAIL = (
    os.environ.get("OFFICE_POOL_ADMIN_EMAIL", "ruan.raaths@acumensoft.net")
    .strip()
    .lower()
)
DB_LOCK = threading.RLock()


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def get_config() -> dict:
    if ON_RENDER and not database.POSTGRES:
        raise RuntimeError("Render requires DATABASE_URL.")
    return json.loads(CONFIG_FILE.read_text(encoding="utf-8")) if CONFIG_FILE.is_file() else {}


CONFIG = get_config()
WEB_APP_ENABLED = CONFIG.get("web_app_enabled", ON_RENDER) is True
if not database.POSTGRES:
    PHOTOS.mkdir(parents=True, exist_ok=True)
DB_PATH = DATA / "office_pool.sqlite3"


def connect_db():
    return database.connect(DB_PATH)


SCHEMA = """
            CREATE TABLE IF NOT EXISTS members (
                uid TEXT PRIMARY KEY,
                email TEXT NOT NULL UNIQUE COLLATE NOCASE,
                display_name TEXT NOT NULL,
                is_admin INTEGER NOT NULL DEFAULT 0,
                password_salt TEXT NOT NULL,
                password_hash TEXT NOT NULL,
                photo_file TEXT NOT NULL DEFAULT '',
                removed_at TEXT NOT NULL DEFAULT '',
                guest_expires_at BIGINT NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS sessions (
                token_hash TEXT PRIMARY KEY,
                uid TEXT NOT NULL REFERENCES members(uid) ON DELETE CASCADE,
                created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS account_deletion_requests (
                uid TEXT PRIMARY KEY REFERENCES members(uid) ON DELETE CASCADE,
                requested_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS table_next_up (
                id INTEGER PRIMARY KEY CHECK(id=1),
                player_a_uid TEXT NOT NULL REFERENCES members(uid) ON DELETE CASCADE,
                player_b_uid TEXT NOT NULL REFERENCES members(uid) ON DELETE CASCADE
            );
            CREATE TABLE IF NOT EXISTS matches (
                id TEXT PRIMARY KEY,
                player_a_uid TEXT NOT NULL,
                player_a_name TEXT NOT NULL,
                player_b_uid TEXT NOT NULL,
                player_b_name TEXT NOT NULL,
                winner_uid TEXT NOT NULL,
                recorded_by_uid TEXT NOT NULL,
                rating_only INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS matches_created_at ON matches(created_at DESC);
            CREATE TABLE IF NOT EXISTS seasons (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('active','completed')),
                started_at TEXT NOT NULL,
                ended_at TEXT NOT NULL DEFAULT ''
            );
            CREATE UNIQUE INDEX IF NOT EXISTS one_active_season ON seasons(status) WHERE status='active';
            CREATE TABLE IF NOT EXISTS tournaments (
                id TEXT PRIMARY KEY,
                season_id TEXT NOT NULL UNIQUE REFERENCES seasons(id),
                name TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('active','completed','cancelled')),
                champion_uid TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS tournament_matches (
                id TEXT PRIMARY KEY,
                tournament_id TEXT NOT NULL REFERENCES tournaments(id),
                round_no INTEGER NOT NULL,
                slot_no INTEGER NOT NULL,
                player_a_uid TEXT NOT NULL DEFAULT '',
                player_b_uid TEXT NOT NULL DEFAULT '',
                winner_uid TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL CHECK(status IN ('waiting','pending','in_progress','bye','completed')),
                source_a_match_id TEXT NOT NULL DEFAULT '',
                source_b_match_id TEXT NOT NULL DEFAULT '',
                game_id TEXT NOT NULL DEFAULT '',
                stats_match_id TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                UNIQUE(tournament_id,round_no,slot_no)
            );
            CREATE INDEX IF NOT EXISTS tournament_matches_order ON tournament_matches(tournament_id,round_no,slot_no);
            CREATE TABLE IF NOT EXISTS web_push_identity (
                id INTEGER PRIMARY KEY CHECK(id=1),
                private_key TEXT NOT NULL,
                public_key TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS web_push_subscriptions (
                endpoint TEXT PRIMARY KEY,
                uid TEXT NOT NULL REFERENCES members(uid),
                session_hash TEXT NOT NULL,
                encryption TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS web_push_member ON web_push_subscriptions(uid);
            CREATE TABLE IF NOT EXISTS games (
                id TEXT PRIMARY KEY,
                player_a_uid TEXT NOT NULL REFERENCES members(uid),
                player_b_uid TEXT NOT NULL REFERENCES members(uid),
                initiated_by_uid TEXT NOT NULL REFERENCES members(uid),
                tournament_match_id TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL CHECK(status IN ('invited','declined','active','awaiting_confirmation','disputed','confirmed')),
                created_at TEXT NOT NULL,
                accepted_at TEXT NOT NULL DEFAULT '',
                started_at TEXT NOT NULL DEFAULT '',
                report_a_winner_uid TEXT NOT NULL DEFAULT '',
                report_b_winner_uid TEXT NOT NULL DEFAULT '',
                winner_uid TEXT NOT NULL DEFAULT '',
                finished_at TEXT NOT NULL DEFAULT '',
                stats_match_id TEXT NOT NULL DEFAULT ''
            );
            CREATE INDEX IF NOT EXISTS games_stats_match ON games(stats_match_id) WHERE stats_match_id<>'';
            CREATE INDEX IF NOT EXISTS games_players_status ON games(player_a_uid,player_b_uid,status,created_at DESC);
            CREATE TABLE IF NOT EXISTS doubles_games (
                id TEXT PRIMARY KEY,
                host_uid TEXT NOT NULL REFERENCES members(uid),
                status TEXT NOT NULL DEFAULT 'invited',
                created_at TEXT NOT NULL,
                started_at TEXT NOT NULL DEFAULT '',
                finished_at TEXT NOT NULL DEFAULT '',
                winner_team INTEGER
            );
            CREATE TABLE IF NOT EXISTS doubles_players (
                game_id TEXT NOT NULL REFERENCES doubles_games(id) ON DELETE CASCADE,
                uid TEXT NOT NULL REFERENCES members(uid),
                seat INTEGER NOT NULL CHECK(seat BETWEEN 0 AND 3),
                accepted INTEGER NOT NULL DEFAULT 0,
                reported_team INTEGER CHECK(reported_team IN (0,1)),
                PRIMARY KEY(game_id,uid),
                UNIQUE(game_id,seat)
            );
            CREATE INDEX IF NOT EXISTS doubles_players_member ON doubles_players(uid,game_id);
"""


def init_db(sync_admin: bool = True) -> None:
    with DB_LOCK, connect_db() as db:
        if database.POSTGRES:
            database.initialize_postgres(db, SCHEMA)
        else:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript(SCHEMA)
            columns = {row["name"] for row in db.execute("PRAGMA table_info(members)")}
            if "is_admin" not in columns:
                db.execute(
                    "ALTER TABLE members ADD COLUMN is_admin INTEGER NOT NULL DEFAULT 0"
                )
            if "removed_at" not in columns:
                db.execute("ALTER TABLE members ADD COLUMN removed_at TEXT NOT NULL DEFAULT ''")
            if "guest_expires_at" not in columns:
                db.execute("ALTER TABLE members ADD COLUMN guest_expires_at BIGINT NOT NULL DEFAULT 0")
            columns = {row["name"] for row in db.execute("PRAGMA table_info(matches)")}
            if "rating_only" not in columns:
                db.execute("ALTER TABLE matches ADD COLUMN rating_only INTEGER NOT NULL DEFAULT 0")
            columns = {row["name"] for row in db.execute("PRAGMA table_info(seasons)")}
            if "deadline_ms" not in columns:
                db.execute(
                    "ALTER TABLE seasons ADD COLUMN deadline_ms INTEGER NOT NULL DEFAULT 0"
                )
        db.execute("""CREATE INDEX IF NOT EXISTS members_guest_expiry ON members(guest_expires_at)
                      WHERE guest_expires_at>0 AND removed_at=''""")
        if sync_admin:
            db.execute(
                "UPDATE members SET is_admin=CASE WHEN lower(email)=? THEN 1 ELSE 0 END",
                (ADMIN_EMAIL,),
            )


def doubles_snapshot(db, uid: str) -> list[dict]:
    rows = db.execute(
        """SELECT g.*, p.uid, p.seat, p.accepted, p.reported_team, m.display_name
           FROM doubles_games g JOIN doubles_players p ON p.game_id=g.id
           JOIN members m ON m.uid=p.uid
           WHERE g.status IN ('active','awaiting_confirmation','confirmed')
              OR EXISTS (SELECT 1 FROM doubles_players my WHERE my.game_id=g.id AND my.uid=?)
           ORDER BY g.created_at DESC, g.id, p.seat""", (uid,)
    ).fetchall()
    games = {}
    for row in rows:
        game = games.setdefault(row["id"], {
            "id": row["id"], "hostUid": row["host_uid"], "status": row["status"],
            "createdAt": row["created_at"], "startedAt": row["started_at"],
            "finishedAt": row["finished_at"], "winnerTeam": row["winner_team"], "players": [],
        })
        game["players"].append({"uid": row["uid"], "name": row["display_name"],
                                "accepted": bool(row["accepted"]), "reportedTeam": row["reported_team"]})
    return list(games.values())


def password_hash(password: str, salt: bytes | None = None) -> tuple[str, str]:
    salt = salt or secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac(
        "sha256", password.encode("utf-8"), salt, PBKDF2_ROUNDS
    )
    return salt.hex(), digest.hex()


def member_json(
    row: sqlite3.Row, rating: int | None = None, history: list[int] | None = None
) -> dict:
    member = {
        "uid": row["uid"],
        "displayName": row["display_name"],
        "photoPath": row["photo_file"],
        "isAdmin": bool(row["is_admin"]),
        "createdAt": row["created_at"],
    }
    if rating is not None:
        member["rating"] = rating
    if history is not None:
        member["ratingHistory"] = history
    return member


def match_duration(start: str | None, finish: str | None) -> int | None:
    if not start or not finish:
        return None
    try:
        return max(
            0,
            int(
                (
                    datetime.fromisoformat(finish) - datetime.fromisoformat(start)
                ).total_seconds()
            ),
        )
    except (TypeError, ValueError):
        return None


def match_json(row: sqlite3.Row, rating_change: int | None, winner_elo: int | None) -> dict:
    return {
        "id": row["id"],
        "playerAUid": row["player_a_uid"],
        "playerAName": row["player_a_name"],
        "playerBUid": row["player_b_uid"],
        "playerBName": row["player_b_name"],
        "winnerUid": row["winner_uid"],
        "recordedByUid": row["recorded_by_uid"],
        "createdAt": row["created_at"],
        "isLeagueMatch": bool(row["tournament_match_id"]),
        "durationSeconds": match_duration(
            row["game_started_at"], row["game_finished_at"]
        ),
        "ratingChange": rating_change,
        "winnerEloAfter": winner_elo,
    }


def scorebook_ratings(
    members: list[sqlite3.Row], matches: list[sqlite3.Row]
) -> tuple[dict[str, int], dict[str, int], dict[str, list[int]], dict[str, int]]:
    ratings = {member["uid"]: ELO_START for member in members}
    changes = {}
    winner_elos = {}
    history = {uid: [ELO_START] for uid in ratings}
    # Use the order results were saved to the scorebook, including those with equal timestamps.
    for match in sorted(matches, key=lambda row: row["scorebook_order"]):
        a, b, winner = match["player_a_uid"], match["player_b_uid"], match["winner_uid"]
        if a == b or a not in ratings or b not in ratings or winner not in (a, b):
            continue
        loser = b if winner == a else a
        gap = ratings[winner] - ratings[loser]
        smaller_probability = 10 ** (-abs(gap) / 400)
        smaller_probability /= 1 + smaller_probability
        expected_loser = smaller_probability if gap >= 0 else 1 - smaller_probability
        change = int(ELO_K * expected_loser + 0.5)
        ratings[winner] += change
        ratings[loser] -= change
        changes[match["id"]] = change
        winner_elos[match["id"]] = ratings[winner]
        history[winner].append(ratings[winner])
        history[loser].append(ratings[loser])
    return ratings, changes, history, winner_elos


def weekly_highlights(members, matches, rating_changes, now):
    office_timezone = timezone(timedelta(hours=2))
    office_date = now.astimezone(office_timezone).date()
    monday = office_date - timedelta(days=office_date.weekday())
    names = {member["uid"]: member["display_name"] for member in members}
    gains, streaks = Counter(), Counter()
    upset, longest = None, ("", 1)
    for match in reversed(matches):
        change = rating_changes.get(match["id"])
        if change is None:
            continue
        try:
            played = datetime.fromisoformat(match["created_at"])
        except (TypeError, ValueError):
            continue
        if played.tzinfo is None:
            played = played.replace(tzinfo=timezone.utc)
        if not monday <= played.astimezone(office_timezone).date() <= office_date:
            continue
        winner = match["winner_uid"]
        loser = (
            match["player_b_uid"]
            if winner == match["player_a_uid"]
            else match["player_a_uid"]
        )
        gains[winner] += change
        gains[loser] -= change
        streaks[winner] += 1
        streaks[loser] = 0
        if streaks[winner] > longest[1]:
            longest = winner, streaks[winner]
        if change > ELO_K / 2 and (upset is None or change > upset[2]):
            upset = winner, loser, change, match["id"]

    highlights = []
    if upset:
        winner, loser, points, match_id = upset
        highlights.append(
            {"title": "Biggest upset", "detail": f"{names[winner]} beat {names[loser]} · +{points} Elo",
             "matchId": match_id}
        )
    if longest[0]:
        player, wins = longest
        highlights.append(
            {"title": "Longest win streak", "detail": f"{names[player]} · {wins} wins in a row"}
        )
    if len(highlights) < 2 and gains:
        player, points = gains.most_common(1)[0]
        if points > 0:
            highlights.append(
                {"title": "Most improved", "detail": f"{names[player]} · +{points} Elo this week"}
            )
    return highlights


def add_player_bracket_route(tournament, uid):
    matches = tournament["matches"]
    first = next(
        (match for match in matches if uid in (match["playerAUid"], match["playerBUid"])),
        None,
    )
    if first is None:
        return
    slot = first["slot"]
    round_no = first["round"]
    for match in matches:
        if match["round"] != round_no or match["slot"] != slot:
            continue
        match["onMyRoute"] = True
        if tournament["status"] == "active" and match["status"] in ("pending", "in_progress"):
            tournament["myMatchId"] = match["id"]
        if match["winnerUid"] and match["winnerUid"] != uid:
            break
        round_no += 1
        slot //= 2


class Handler(BaseHTTPRequestHandler):
    server_version = "OfficePool/1.0"

    def log_message(self, fmt: str, *args) -> None:
        print("[%s] %s" % (self.log_date_time_string(), fmt % args))

    def send_json(self, status: int, body: dict) -> None:
        raw = json.dumps(body, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def read_body(self, limit: int = MAX_JSON) -> bytes:
        size = int(self.headers.get("Content-Length", "0"))
        if size < 0 or size > limit:
            raise ValueError("Request is too large")
        raw = self.rfile.read(size)
        if len(raw) != size:
            raise ValueError("The request ended before the full body arrived.")
        return raw

    def read_json(self) -> dict:
        raw = self.read_body()
        data = json.loads(raw.decode("utf-8") if raw else "{}")
        if not isinstance(data, dict):
            raise ValueError("Expected a JSON object")
        return data

    def current_uid(self) -> str | None:
        auth = self.headers.get("Authorization", "")
        if not auth.startswith("Bearer "):
            return None
        token = auth[7:].strip()
        if not token:
            return None
        token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
        with DB_LOCK, connect_db() as db:
            expire_guests(db)
            row = db.execute(
                "SELECT s.uid, s.created_at FROM sessions s WHERE s.token_hash=?",
                (token_hash,),
            ).fetchone()
        if not row:
            return None
        try:
            created = datetime.fromisoformat(row["created_at"])
        except ValueError:
            return None
        if created < datetime.now(timezone.utc) - timedelta(days=60):
            with DB_LOCK, connect_db() as db:
                db.execute(
                    "DELETE FROM web_push_subscriptions WHERE session_hash=?",
                    (token_hash,),
                )
                db.execute("DELETE FROM sessions WHERE token_hash=?", (token_hash,))
            return None
        return row["uid"]

    def require_user(self) -> tuple[str | None, bool]:
        uid = self.current_uid()
        if uid is None:
            self.send_json(401, {"error": "Sign in again to continue."})
            return None, False
        return uid, True

    def require_admin(self) -> tuple[str | None, bool]:
        uid, ok = self.require_user()
        if not ok:
            return None, False
        with DB_LOCK, connect_db() as db:
            row = db.execute(
                "SELECT is_admin FROM members WHERE uid=?", (uid,)
            ).fetchone()
        if not row or not row["is_admin"]:
            self.send_json(
                403, {"error": "Only the league administrator can manage seasons."}
            )
            return None, False
        return uid, True

    def league_snapshot(self, uid: str) -> dict:
        with DB_LOCK, connect_db() as db:
            members = db.execute(
                "SELECT * FROM members ORDER BY lower(display_name)"
            ).fetchall()
            matches = db.execute(
                f"""SELECT m.*, m.{database.ORDER_COLUMN} AS scorebook_order, g.tournament_match_id,
                           g.started_at AS game_started_at,g.finished_at AS game_finished_at
                    FROM matches m LEFT JOIN games g ON g.stats_match_id=m.id AND g.stats_match_id<>''
                    ORDER BY m.created_at DESC, m.{database.ORDER_COLUMN} DESC"""
            ).fetchall()
            admin = db.execute(
                "SELECT is_admin,guest_expires_at FROM members WHERE uid=?", (uid,)
            ).fetchone()
            expire_leagues(db)
            season_row = db.execute(
                f"SELECT * FROM seasons ORDER BY started_at DESC, {database.ORDER_COLUMN} DESC LIMIT 1"
            ).fetchone()
            season = None
            if season_row:
                season = {
                    "id": season_row["id"],
                    "name": season_row["name"],
                    "status": season_row["status"],
                    "startedAt": season_row["started_at"],
                    "endedAt": season_row["ended_at"],
                    "deadlineMs": season_row["deadline_ms"],
                    "tournament": None,
                }
                tournament = db.execute(
                    "SELECT * FROM tournaments WHERE season_id=?", (season_row["id"],)
                ).fetchone()
                if tournament:
                    rows = db.execute(
                        """SELECT tm.*, a.display_name AS player_a_name, b.display_name AS player_b_name,
                                  w.display_name AS winner_name
                           FROM tournament_matches tm
                           LEFT JOIN members a ON a.uid=tm.player_a_uid
                           LEFT JOIN members b ON b.uid=tm.player_b_uid
                           LEFT JOIN members w ON w.uid=tm.winner_uid
                           WHERE tm.tournament_id=? ORDER BY tm.round_no,tm.slot_no""",
                        (tournament["id"],),
                    ).fetchall()
                    max_round = max((row["round_no"] for row in rows), default=1)
                    game_states = {
                        row["id"]: row["status"]
                        for row in db.execute(
                            """SELECT g.id, g.status FROM games g
                               JOIN tournament_matches tm ON tm.game_id=g.id
                               WHERE tm.tournament_id=?""",
                            (tournament["id"],),
                        )
                    }
                    season["tournament"] = {
                        "id": tournament["id"],
                        "name": tournament["name"],
                        "status": tournament["status"],
                        "championUid": tournament["champion_uid"],
                        "championName": "",
                        "roundCount": max_round,
                        "matches": [
                            {
                                "id": row["id"],
                                "round": row["round_no"],
                                "slot": row["slot_no"],
                                "playerAUid": row["player_a_uid"],
                                "playerAName": row["player_a_name"] or "",
                                "playerBUid": row["player_b_uid"],
                                "playerBName": row["player_b_name"] or "",
                                "winnerUid": row["winner_uid"],
                                "winnerName": row["winner_name"] or "",
                                "status": row["status"],
                                "gameId": row["game_id"],
                                "statsMatchId": row["stats_match_id"],
                                "gameStatus": game_states.get(row["game_id"], ""),
                            }
                            for row in rows
                        ],
                    }
                    if tournament["champion_uid"]:
                        champion = db.execute(
                            "SELECT display_name FROM members WHERE uid=?",
                            (tournament["champion_uid"],),
                        ).fetchone()
                        if champion:
                            season["tournament"]["championName"] = champion[
                                "display_name"
                            ]
                    add_player_bracket_route(season["tournament"], uid)
            game_query = """SELECT g.*, a.display_name AS player_a_name, b.display_name AS player_b_name,
                                  w.display_name AS winner_name
                           FROM games g
                           LEFT JOIN members a ON a.uid=g.player_a_uid
                           LEFT JOIN members b ON b.uid=g.player_b_uid
                           LEFT JOIN members w ON w.uid=COALESCE(NULLIF(g.winner_uid,''), NULLIF(g.report_a_winner_uid,''), g.report_b_winner_uid)"""
            if admin and admin["is_admin"]:
                game_rows = db.execute(
                    game_query
                    + " WHERE g.player_a_uid=? OR g.player_b_uid=? OR g.status='disputed' ORDER BY g.created_at DESC LIMIT 100",
                    (uid, uid),
                ).fetchall()
            else:
                game_rows = db.execute(
                    game_query
                    + " WHERE g.player_a_uid=? OR g.player_b_uid=? ORDER BY g.created_at DESC LIMIT 100",
                    (uid, uid),
                ).fetchall()
            playing = db.execute(
                f"""SELECT g.id,g.player_a_uid,g.player_b_uid,g.started_at,g.tournament_match_id,
                          a.display_name AS player_a_name,b.display_name AS player_b_name
                   FROM games g
                   JOIN members a ON a.uid=g.player_a_uid
                   JOIN members b ON b.uid=g.player_b_uid
                   WHERE g.status='active' AND g.started_at<>''
                   ORDER BY g.started_at DESC,g.{database.ORDER_COLUMN} DESC"""
            ).fetchall()
            next_up = db.execute(
                "SELECT player_a_uid,player_b_uid FROM table_next_up WHERE id=1"
            ).fetchone()
            doubles = doubles_snapshot(db, uid)
        games = [
            {
                "id": row["id"],
                "playerAUid": row["player_a_uid"],
                "playerAName": row["player_a_name"] or "",
                "playerBUid": row["player_b_uid"],
                "playerBName": row["player_b_name"] or "",
                "initiatedByUid": row["initiated_by_uid"],
                "tournamentMatchId": row["tournament_match_id"],
                "status": row["status"],
                "createdAt": row["created_at"],
                "acceptedAt": row["accepted_at"],
                "startedAt": row["started_at"],
                "reportAWinnerUid": row["report_a_winner_uid"],
                "reportBWinnerUid": row["report_b_winner_uid"],
                "winnerUid": row["winner_uid"],
                "winnerName": row["winner_name"] or "",
                "finishedAt": row["finished_at"],
                "statsMatchId": row["stats_match_id"],
            }
            for row in game_rows
        ]
        ratings, rating_changes, rating_history, winner_elos = scorebook_ratings(members, matches)
        visible_matches = [match for match in matches if not match["rating_only"]]
        return {
            "members": [
                member_json(
                    member, ratings[member["uid"]], rating_history[member["uid"]]
                )
                for member in members if not member["removed_at"]
            ],
            "matches": [
                match_json(match, rating_changes.get(match["id"]), winner_elos.get(match["id"]))
                for match in visible_matches
            ],
            "games": games,
            "doubles": doubles,
            "weeklyHighlights": weekly_highlights(
                members, visible_matches, rating_changes, datetime.now(timezone.utc)
            ),
            "ratingHistory": rating_history.get(uid, [ELO_START]),
            "playingNow": [
                {
                    "id": row["id"],
                    "playerAUid": row["player_a_uid"],
                    "playerAName": row["player_a_name"],
                    "playerBUid": row["player_b_uid"],
                    "playerBName": row["player_b_name"],
                    "startedAt": row["started_at"],
                    "isLeagueMatch": bool(row["tournament_match_id"]),
                }
                for row in playing
            ] + [
                {"id": game["id"], "startedAt": game["startedAt"], "isDoubles": True,
                 "playerAName": " & ".join(player["name"] for player in game["players"][:2]),
                 "playerBName": " & ".join(player["name"] for player in game["players"][2:])}
                for game in doubles if game["status"] in ("active", "awaiting_confirmation")
            ],
            "isAdmin": bool(admin and admin["is_admin"]),
            "guestExpiresAt": admin["guest_expires_at"] if admin else 0,
            "nextUp": (
                {"playerAUid": next_up["player_a_uid"], "playerBUid": next_up["player_b_uid"]}
                if next_up else None
            ),
            "season": season,
            "serverTime": now_iso(),
            "serverTimeMs": int(time.time() * 1000),
        }

    def serve_web_asset(self, path: str, head: bool = False) -> None:
        if not WEB_APP_ENABLED:
            self.send_error(404)
            return
        if path == "/cue-club":
            self.send_response(308)
            self.send_header("Location", "/cue-club/")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        asset = WEB_ASSETS.get(path)
        if asset is None:
            self.send_error(404)
            return
        filename, mime = asset
        raw = (WEB_ROOT / filename).read_bytes()
        if filename == "index.html":
            raw = raw.replace(b"__CUE_CLUB_WEB_VERSION__", WEB_VERSION.encode("ascii"))
        self.send_response(200)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-cache")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header(
            "Content-Security-Policy",
            "default-src 'self'; img-src 'self' blob:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
        )
        self.end_headers()
        if not head:
            self.wfile.write(raw)

    def do_HEAD(self) -> None:
        path = urlparse(self.path).path
        if path == ANDROID_APK_PATH:
            self.serve_android_apk(head=True)
            return
        self.serve_web_asset(path, head=True)

    def serve_android_apk(self, head: bool = False) -> None:
        if not ANDROID_APK.is_file():
            self.send_error(404)
            return
        raw = ANDROID_APK.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", "application/vnd.android.package-archive")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Content-Disposition", 'attachment; filename="Cue-Club.apk"')
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        if not head:
            self.wfile.write(raw)

    def manage_web_push(self, remove: bool = False) -> None:
        if not WEB_APP_ENABLED:
            self.send_json(404, {"error": "Not found"})
            return
        uid, ok = self.require_user()
        if not ok:
            return
        data = self.read_json()
        token_hash = hashlib.sha256(
            self.headers.get("Authorization", "")[7:].strip().encode()
        ).hexdigest()
        if remove:
            endpoint = data.get("endpoint")
            if not isinstance(endpoint, str):
                raise ValueError("Invalid match alert endpoint.")
            with DB_LOCK, connect_db() as db:
                db.execute(
                    "DELETE FROM web_push_subscriptions WHERE endpoint=? AND uid=? AND session_hash=?",
                    (endpoint, uid, token_hash),
                )
        else:
            if push_delivery.webpush is None:
                self.send_json(
                    503, {"error": "Match alerts are unavailable on the server."}
                )
                return
            endpoint, encryption = push_delivery.subscription(data)
            with DB_LOCK, connect_db() as db:
                db.execute(
                    """INSERT INTO web_push_subscriptions(endpoint,uid,session_hash,encryption)
                              VALUES(?,?,?,?) ON CONFLICT(endpoint) DO UPDATE SET uid=excluded.uid,
                              session_hash=excluded.session_hash,encryption=excluded.encryption""",
                    (endpoint, uid, token_hash, json.dumps(encryption)),
                )
        self.send_json(200, {"ok": True})

    def do_GET(self) -> None:
        url = urlparse(self.path)
        path = url.path
        if path == "/updates/web":
            self.send_json(200, {"version": WEB_VERSION})
            return
        if path == "/updates/android":
            release = {}
            if ANDROID_APK.is_file() and ANDROID_MANIFEST.is_file():
                release = json.loads(ANDROID_MANIFEST.read_text(encoding="utf-8"))
            self.send_json(200, release)
            return
        if path == ANDROID_APK_PATH:
            self.serve_android_apk()
            return
        if path == "/cue-club" or path.startswith("/cue-club/"):
            self.serve_web_asset(path)
            return
        if path == "/health":
            if database.POSTGRES:
                try:
                    with connect_db() as db:
                        db.execute("SELECT 1").fetchone()
                except Exception:
                    self.send_json(503, {"online": False})
                    return
            self.send_json(200, {"online": True, "serverTime": now_iso()})
            return
        uid, ok = self.require_user()
        if not ok:
            return
        if path == "/web-push/key" and WEB_APP_ENABLED:
            if push_delivery.webpush is None:
                self.send_json(
                    503, {"error": "Match alerts are unavailable on the server."}
                )
                return
            with DB_LOCK, connect_db() as db:
                _, public_key = push_delivery.keys(db)
            self.send_json(200, {"publicKey": public_key})
            return
        if path == "/sync":
            self.send_json(200, self.league_snapshot(uid))
            return
        if path.startswith("/photos/"):
            photo_uid = path.rsplit("/", 1)[-1]
            revision = parse_qs(url.query).get("revision", [""])[0]
            with DB_LOCK, connect_db() as db:
                row = db.execute(
                    "SELECT photo_file FROM members WHERE uid=?", (photo_uid,)
                ).fetchone()
                filename = row["photo_file"] if row else ""
                raw = None
                if filename and (not revision or revision == filename):
                    if database.POSTGRES:
                        photo = db.execute(
                            "SELECT content FROM profile_photos WHERE filename=?",
                            (filename,),
                        ).fetchone()
                        raw = bytes(photo["content"]) if photo else None
                    else:
                        try:
                            raw = (PHOTOS / filename).read_bytes()
                        except FileNotFoundError:
                            pass
            if raw is None:
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header(
                "Content-Type",
                mimetypes.guess_type(filename)[0] or "application/octet-stream",
            )
            self.send_header("Content-Length", str(len(raw)))
            self.send_header(
                "Cache-Control",
                "private, max-age=86400" if revision else "private, no-cache",
            )
            self.end_headers()
            self.wfile.write(raw)
            return
        self.send_json(404, {"error": "Not found"})

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        try:
            routes = {
                "/auth/register": self.register,
                "/auth/login": self.login,
                "/auth/logout": self.logout,
                ACCOUNT_DELETION_PATH: lambda: self.send_json(409, {"error": "Update Cue Club to delete your account."}),
                "/sync": self.sync_pending,
                "/admin/seasons": self.admin_start_season,
                "/games/invite": self.create_game_invite,
                "/doubles/invite": self.invite_doubles,
                "/table/next-up": self.update_next_up,
                "/profiles/me/photo": self.upload_photo,
                "/web-push/subscribe": self.manage_web_push,
                "/web-push/unsubscribe": lambda: self.manage_web_push(remove=True),
            }
            handler = routes.get(path)
            if handler is not None:
                handler()
                return
            if path.startswith("/admin/seasons/"):
                self.admin_season_action(path)
                return
            if path.startswith("/admin/games/"):
                parts = path.strip("/").split("/")
                if len(parts) == 4 and parts[2] and parts[3] == "resolve":
                    self.admin_resolve_game(parts[2])
                    return
                self.send_json(404, {"error": "Not found"})
                return
            if path.startswith("/games/"):
                self.game_action(path)
                return
            if path.startswith("/doubles/"):
                self.doubles_action(path)
                return
            self.send_json(404, {"error": "Not found"})
        except ValueError as exc:
            self.send_json(400, {"error": str(exc)})
        except sqlite3.IntegrityError:
            self.send_json(409, {"error": "That email address already has a profile."})
        except Exception as exc:
            print("Request failed:", type(exc).__name__, str(exc))
            self.send_json(
                500, {"error": "The PC server could not complete that request."}
            )

    def do_PUT(self) -> None:
        if urlparse(self.path).path != "/profiles/me":
            self.send_json(404, {"error": "Not found"})
            return
        uid, ok = self.require_user()
        if not ok:
            return
        try:
            data = self.read_json()
            name = str(data.get("displayName", "")).strip()
            if not 1 <= len(name) <= 48:
                raise ValueError("Name must be between 1 and 48 characters.")
            with DB_LOCK, connect_db() as db:
                db.execute(
                    "UPDATE members SET display_name=?, updated_at=? WHERE uid=? AND removed_at=''",
                    (name, now_iso(), uid),
                )
                row = db.execute("SELECT * FROM members WHERE uid=? AND removed_at=''", (uid,)).fetchone()
                if row is None:
                    self.send_json(401, {"error": "Sign in again to continue."})
                    return
            self.send_json(200, {"member": member_json(row)})
        except ValueError as exc:
            self.send_json(400, {"error": str(exc)})

    def register(self) -> None:
        data = self.read_json()
        raw_email = data.get("email")
        email = raw_email.strip().lower() if isinstance(raw_email, str) else ""
        name = str(data.get("displayName", "")).strip()
        password = str(data.get("password", ""))
        guest_expires_at = int(time.time()) + 86400 if data.get("isGuest") is True else 0
        local, _, domain = email.partition("@")
        label = r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
        if (
            len(email) > 254
            or len(local) > 64
            or not re.fullmatch(r"[^@\s\x00-\x1f\x7f]+", local)
            or not re.fullmatch(rf"{label}(?:\.{label})*", domain)
        ):
            raise ValueError("Enter a valid email address.")
        if email == ADMIN_EMAIL:
            raise ValueError("This address is reserved for the office administrator.")
        if not 1 <= len(name) <= 48:
            raise ValueError("Name must be between 1 and 48 characters.")
        if len(password) < 8:
            raise ValueError("Choose a password with at least 8 characters.")
        uid = uuid.uuid4().hex
        salt, digest = password_hash(password)
        stamp = now_iso()
        with DB_LOCK, connect_db() as db:
            expire_guests(db)
            db.execute(
                """INSERT INTO members(uid,email,display_name,password_salt,password_hash,
                                       created_at,updated_at,guest_expires_at) VALUES(?,?,?,?,?,?,?,?)""",
                (
                    uid,
                    email,
                    name,
                    salt,
                    digest,
                    stamp,
                    stamp,
                    guest_expires_at,
                ),
            )
            token = self.new_session(db, uid)
            member = db.execute("SELECT * FROM members WHERE uid=?", (uid,)).fetchone()
        self.send_json(
            201, {"token": token, "member": member_json(member), "email": email,
                  "guestExpiresAt": guest_expires_at}
        )

    def login(self) -> None:
        data = self.read_json()
        email = str(data.get("email", "")).strip().lower()
        password = str(data.get("password", ""))
        with DB_LOCK, connect_db() as db:
            expire_guests(db)
            row = db.execute("SELECT * FROM members WHERE email=? AND removed_at=''", (email,)).fetchone()
            if not row:
                raise ValueError("Email or password is incorrect.")
            _, digest = password_hash(password, bytes.fromhex(row["password_salt"]))
            if not hmac.compare_digest(digest, row["password_hash"]):
                raise ValueError("Email or password is incorrect.")
            token = self.new_session(db, row["uid"])
        self.send_json(
            200, {"token": token, "member": member_json(row), "email": email,
                  "guestExpiresAt": row["guest_expires_at"]}
        )

    def new_session(self, db: sqlite3.Connection, uid: str) -> str:
        token = secrets.token_urlsafe(32)
        token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
        db.execute(
            "INSERT INTO sessions(token_hash,uid,created_at) VALUES(?,?,?)",
            (token_hash, uid, now_iso()),
        )
        return token

    def logout(self) -> None:
        auth = self.headers.get("Authorization", "")
        if auth.startswith("Bearer "):
            token_hash = hashlib.sha256(auth[7:].strip().encode("utf-8")).hexdigest()
            with DB_LOCK, connect_db() as db:
                db.execute(
                    "DELETE FROM web_push_subscriptions WHERE session_hash=?",
                    (token_hash,),
                )
                db.execute("DELETE FROM sessions WHERE token_hash=?", (token_hash,))
        self.send_json(200, {"ok": True})

    def do_DELETE(self) -> None:
        path = urlparse(self.path).path
        parts = path.strip("/").split("/")
        target = parts[2] if len(parts) == 3 and parts[:2] == ["admin", "players"] else None
        if path != ACCOUNT_DELETION_PATH and not target:
            self.send_json(404, {"error": "Not found"})
            return
        try:
            self.delete_account(target)
        except ValueError as exc:
            self.send_json(400, {"error": str(exc)})
        except Exception as exc:
            print("Account deletion failed:", type(exc).__name__)
            self.send_json(500, {"error": "Could not delete your account. Try again."})

    def delete_account(self, target: str | None = None) -> None:
        uid, ok = self.require_admin() if target else self.require_user()
        if not ok:
            return
        password = self.read_json().get("password")
        if not isinstance(password, str) or not password:
            raise ValueError("Enter your current password.")
        with DB_LOCK, connect_db() as db:
            member = db.execute(
                """SELECT password_salt,password_hash,photo_file,guest_expires_at
                   FROM members WHERE uid=? AND removed_at=''""", (uid,)
            ).fetchone()
            if member is None:
                self.send_json(401, {"error": "Sign in again to continue."})
                return
            _, digest = password_hash(password, bytes.fromhex(member["password_salt"]))
            if not hmac.compare_digest(digest, member["password_hash"]):
                self.send_json(400, {"error": "Current password is incorrect."})
                return
            if target:
                if target == uid:
                    raise ValueError("Use Delete account in your settings to delete your own account.")
                player = db.execute(
                    "SELECT photo_file,guest_expires_at FROM members WHERE uid=? AND removed_at=''", (target,)
                ).fetchone()
                if player is None:
                    self.send_json(404, {"error": "Player not found."})
                    return
                remove_account_data(db, target, player["photo_file"], bool(player["guest_expires_at"]))
            else:
                remove_account_data(db, uid, member["photo_file"], bool(member["guest_expires_at"]))
        self.send_json(200, {"ok": True})

    def sync_pending(self) -> None:
        uid, ok = self.require_user()
        if not ok:
            return
        data = self.read_json()
        pending = data.get("matches", [])
        if not isinstance(pending, list) or len(pending) > 200:
            raise ValueError("Sync accepts up to 200 queued matches at a time.")
        # Older Android builds send queued scorebook rows on POST /sync.
        # Keep those builds connected while accepting results only through game confirmation.
        snapshot = self.league_snapshot(uid)
        if pending:
            snapshot["ignoredLegacyResults"] = len(pending)
        self.send_json(200, snapshot)

    def admin_start_season(self) -> None:
        uid, ok = self.require_admin()
        if not ok:
            return
        data = self.read_json()
        name = str(data.get("name", "")).strip()
        deadline = data.get("deadlineMs", 0)
        if type(deadline) is not int or (
            deadline and not time.time() * 1000 < deadline <= 253402300799999
        ):
            raise ValueError("Choose a league end date in the future.")
        if not 1 <= len(name) <= 48:
            raise ValueError("Season name must be between 1 and 48 characters.")
        with DB_LOCK, connect_db() as db:
            expire_leagues(db)
            if db.execute("SELECT 1 FROM seasons WHERE status='active'").fetchone():
                raise ValueError("End the current season before starting another.")
            participants = [
                row["uid"]
                for row in db.execute(
                    "SELECT uid FROM members WHERE removed_at='' AND guest_expires_at=0 ORDER BY uid"
                ).fetchall()
            ]
            if len(participants) < 2:
                raise ValueError(
                    "At least two office profiles are needed to start a title season."
                )
            stamp = now_iso()
            season_id = uuid.uuid4().hex
            tournament_id = uuid.uuid4().hex
            db.execute(
                "INSERT INTO seasons(id,name,status,started_at,deadline_ms) VALUES(?,?,'active',?,?)",
                (season_id, name, stamp, deadline),
            )
            db.execute(
                "INSERT INTO tournaments(id,season_id,name,status,created_at) VALUES(?,?,?,'active',?)",
                (tournament_id, season_id, "Office Title", stamp),
            )
            random.SystemRandom().shuffle(participants)
            bracket_size = 1
            while bracket_size < len(participants):
                bracket_size *= 2
            byes = bracket_size - len(participants)
            pairs: list[tuple[str, str]] = [(participants[i], "") for i in range(byes)]
            remaining = participants[byes:]
            pairs.extend(
                (remaining[i], remaining[i + 1]) for i in range(0, len(remaining), 2)
            )
            random.SystemRandom().shuffle(pairs)
            round_ids: list[str] = []
            for slot, (a_uid, b_uid) in enumerate(pairs):
                match_id = uuid.uuid4().hex
                status = "bye" if not b_uid else "pending"
                winner_uid = a_uid if not b_uid else ""
                db.execute(
                    """INSERT INTO tournament_matches(id,tournament_id,round_no,slot_no,player_a_uid,player_b_uid,winner_uid,status,created_at)
                       VALUES(?,?,1,?,?,?,?,?,?)""",
                    (
                        match_id,
                        tournament_id,
                        slot,
                        a_uid,
                        b_uid,
                        winner_uid,
                        status,
                        stamp,
                    ),
                )
                round_ids.append(match_id)
            round_no = 2
            while len(round_ids) > 1:
                next_ids = []
                for slot in range(0, len(round_ids), 2):
                    match_id = uuid.uuid4().hex
                    db.execute(
                        """INSERT INTO tournament_matches(id,tournament_id,round_no,slot_no,status,source_a_match_id,source_b_match_id,created_at)
                           VALUES(?,?,?,?,'waiting',?,?,?)""",
                        (
                            match_id,
                            tournament_id,
                            round_no,
                            slot // 2,
                            round_ids[slot],
                            round_ids[slot + 1],
                            stamp,
                        ),
                    )
                    next_ids.append(match_id)
                round_ids = next_ids
                round_no += 1
            refresh_bracket(db, tournament_id)
        self.send_json(201, self.league_snapshot(uid))

    def admin_season_action(self, path: str) -> None:
        uid, ok = self.require_admin()
        if not ok:
            return
        parts = path.strip("/").split("/")
        if len(parts) != 4 or parts[0:2] != ["admin", "seasons"] or parts[3] != "end":
            self.send_json(404, {"error": "Not found"})
            return
        season_id = parts[2]
        with DB_LOCK, connect_db() as db:
            season = db.execute(
                "SELECT * FROM seasons WHERE id=? AND status='active'", (season_id,)
            ).fetchone()
            if not season:
                self.send_json(404, {"error": "Active season not found."})
                return
            close_league(db, season_id)
        self.send_json(200, self.league_snapshot(uid))

    def create_game_invite(self) -> None:
        uid, ok = self.require_user()
        if not ok:
            return
        data = self.read_json()
        game_id = str(data.get("id", ""))[:64]
        opponent_uid = str(data.get("opponentUid", ""))
        tournament_match_id = str(data.get("tournamentMatchId", ""))
        if not game_id:
            raise ValueError("A game invite needs an id.")
        stamp = now_iso()
        with DB_LOCK, connect_db() as db:
            expire_leagues(db)
            existing = db.execute(
                "SELECT * FROM games WHERE id=?", (game_id,)
            ).fetchone()
            if existing:
                if existing["initiated_by_uid"] != uid:
                    raise ValueError("That game ID is already in use.")
                self.send_json(200, self.league_snapshot(uid))
                return
            a_uid, b_uid = uid, opponent_uid
            if tournament_match_id:
                bracket = db.execute(
                    """SELECT tm.* FROM tournament_matches tm
                       JOIN tournaments t ON t.id=tm.tournament_id
                       JOIN seasons s ON s.id=t.season_id
                       WHERE tm.id=? AND t.status='active' AND s.status='active'""",
                    (tournament_match_id,),
                ).fetchone()
                if not bracket or bracket["status"] != "pending":
                    raise ValueError("This league match is no longer ready to start.")
                players = (bracket["player_a_uid"], bracket["player_b_uid"])
                if uid not in players or not all(players):
                    raise ValueError("Only a player in this league match can start it.")
                b_uid = players[1] if uid == players[0] else players[0]
            if not b_uid or a_uid == b_uid:
                raise ValueError("Choose a coworker to challenge.")
            if not db.execute("SELECT 1 FROM members WHERE uid=? AND removed_at=''", (b_uid,)).fetchone():
                raise ValueError("That office profile could not be found.")
            if (
                not tournament_match_id
                and db.execute(
                    """SELECT 1 FROM games WHERE tournament_match_id=''
                   AND status IN ('invited','active','awaiting_confirmation','disputed')
                   AND ((player_a_uid=? AND player_b_uid=?) OR (player_a_uid=? AND player_b_uid=?))""",
                    (a_uid, b_uid, b_uid, a_uid),
                ).fetchone()
            ):
                raise ValueError("You already have an open 1v1 with this player.")
            db.execute(
                """INSERT INTO games(id,player_a_uid,player_b_uid,initiated_by_uid,tournament_match_id,status,created_at)
                   VALUES(?,?,?,?,?,'invited',?)""",
                (game_id, a_uid, b_uid, uid, tournament_match_id, stamp),
            )
            if tournament_match_id:
                db.execute(
                    "UPDATE tournament_matches SET status='in_progress',game_id=? WHERE id=?",
                    (game_id, tournament_match_id),
                )
        send_match_alert(
            {b_uid},
            game_id,
            (
                "League match invitation"
                if tournament_match_id
                else "Pool game invitation"
            ),
            "A coworker invited you to play. Open Cue Club to respond.",
        )
        self.send_json(201, self.league_snapshot(uid))

    def invite_doubles(self) -> None:
        uid, ok = self.require_user()
        if not ok:
            return
        data = self.read_json()
        game_id = str(data.get("id", ""))[:64]
        picks = [data.get(key) for key in ("partnerUid", "opponentUid", "opponentPartnerUid")]
        if (not game_id or any(not isinstance(pick, str) or not pick for pick in picks)
                or len(set([uid, *picks])) != 4):
            raise ValueError("Choose three different coworkers for a 2v2 match.")
        seats = [uid, *picks]
        with DB_LOCK, connect_db() as db:
            existing = db.execute("SELECT host_uid FROM doubles_games WHERE id=?", (game_id,)).fetchone()
            if existing and existing["host_uid"] != uid:
                raise ValueError("That match ID is already in use.")
            if not existing:
                members = db.execute("SELECT uid FROM members WHERE removed_at='' AND uid IN (?,?,?,?)", seats).fetchall()
                if len(members) != 4:
                    raise ValueError("An office profile could not be found.")
                db.execute("INSERT INTO doubles_games(id,host_uid,created_at) VALUES(?,?,?)",
                           (game_id, uid, now_iso()))
                for seat, player in enumerate(seats):
                    db.execute("INSERT INTO doubles_players(game_id,uid,seat,accepted) VALUES(?,?,?,?)",
                               (game_id, player, seat, int(seat == 0)))
        if not existing:
            send_match_alert(set(picks), game_id, "2v2 pool invitation",
                             "You’ve been invited to a doubles match. Open Cue Club to respond.")
        self.send_json(200 if existing else 201, self.league_snapshot(uid))

    def doubles_action(self, path: str) -> None:
        uid, ok = self.require_user()
        if not ok:
            return
        parts = path.strip("/").split("/")
        if len(parts) != 3 or parts[2] not in ("respond", "report"):
            self.send_json(404, {"error": "Not found"})
            return
        _, game_id, action = parts
        data = self.read_json()
        alert = None
        with DB_LOCK, connect_db() as db:
            game = db.execute("SELECT * FROM doubles_games WHERE id=?", (game_id,)).fetchone()
            seats = db.execute("SELECT * FROM doubles_players WHERE game_id=? ORDER BY seat",
                               (game_id,)).fetchall()
            me = next((player for player in seats if player["uid"] == uid), None)
            if not game or me is None:
                self.send_json(404, {"error": "2v2 match not found."})
                return
            others = [player for player in seats if player["uid"] != uid]
            if action == "respond":
                accept = data.get("accept")
                if type(accept) is not bool:
                    raise ValueError("Choose whether to accept the invitation.")
                if game["status"] == "invited":
                    if not accept:
                        db.execute("UPDATE doubles_games SET status='declined',finished_at=? WHERE id=?",
                                   (now_iso(), game_id))
                    else:
                        db.execute("UPDATE doubles_players SET accepted=1 WHERE game_id=? AND uid=?", (game_id, uid))
                        if all(player["accepted"] for player in others):
                            db.execute("UPDATE doubles_games SET status='active',started_at=? WHERE id=?",
                                       (now_iso(), game_id))
                            alert = (others, "2v2 match started", "Everyone accepted. Open Cue Club for the timer.")
                elif not ((accept and me["accepted"]) or (not accept and game["status"] == "declined")):
                    raise ValueError("This invitation is closed.")
            else:
                team = data.get("winnerTeam")
                if type(team) is not int or team not in (0, 1):
                    raise ValueError("Choose one of the two teams as the winner.")
                if game["status"] in ("active", "awaiting_confirmation"):
                    db.execute("UPDATE doubles_players SET reported_team=? WHERE game_id=? AND uid=?",
                               (team, game_id, uid))
                    if all(player["reported_team"] == team for player in others):
                        db.execute("""UPDATE doubles_games SET status='confirmed',winner_team=?,finished_at=?
                                      WHERE id=?""", (team, now_iso(), game_id))
                    else:
                        db.execute("UPDATE doubles_games SET status='awaiting_confirmation' WHERE id=?", (game_id,))
                        if game["status"] == "active":
                            alert = (others, "Confirm your 2v2 result", "A player reported the winning team.")
                elif game["status"] != "confirmed" or me["reported_team"] != team:
                    raise ValueError("This match is not waiting for a result.")
        if alert:
            recipients, title, body = alert
            send_match_alert({player["uid"] for player in recipients}, game_id, title, body)
        self.send_json(200, self.league_snapshot(uid))

    def update_next_up(self) -> None:
        uid, ok = self.require_user()
        if not ok:
            return
        data = self.read_json()
        with DB_LOCK, connect_db() as db:
            if data.get("cancel") is True:
                slot = db.execute("SELECT player_a_uid,player_b_uid FROM table_next_up WHERE id=1").fetchone()
                if slot and uid not in (slot["player_a_uid"], slot["player_b_uid"]):
                    admin = db.execute("SELECT is_admin FROM members WHERE uid=?", (uid,)).fetchone()
                    if not admin or not admin["is_admin"]:
                        self.send_json(403, {"error": "Only the next pair or the admin can clear this slot."})
                        return
                db.execute("DELETE FROM table_next_up WHERE id=1")
            else:
                opponent = data.get("opponentUid")
                if not isinstance(opponent, str) or opponent == uid or not db.execute(
                    "SELECT 1 FROM members WHERE uid=? AND removed_at=''", (opponent,)
                ).fetchone():
                    raise ValueError("Choose a coworker to play next with.")
                inserted = db.execute(
                    """INSERT INTO table_next_up(id,player_a_uid,player_b_uid) VALUES(1,?,?)
                       ON CONFLICT(id) DO NOTHING RETURNING id""", (uid, opponent)
                ).fetchone()
                if not inserted:
                    slot = db.execute("SELECT player_a_uid,player_b_uid FROM table_next_up WHERE id=1").fetchone()
                    if not slot or {uid, opponent} != {slot["player_a_uid"], slot["player_b_uid"]}:
                        self.send_json(409, {"error": "Another pair is already next up."})
                        return
        self.send_json(200, self.league_snapshot(uid))

    def game_action(self, path: str) -> None:
        uid, ok = self.require_user()
        if not ok:
            return
        parts = path.strip("/").split("/")
        if (
            len(parts) != 3
            or parts[0] != "games"
            or parts[2] not in ("respond", "report")
        ):
            self.send_json(404, {"error": "Not found"})
            return
        game_id, action = parts[1], parts[2]
        data = self.read_json()
        alert = None
        with DB_LOCK, connect_db() as db:
            expire_leagues(db)
            game = db.execute("SELECT * FROM games WHERE id=?", (game_id,)).fetchone()
            if not game or uid not in (game["player_a_uid"], game["player_b_uid"]):
                self.send_json(404, {"error": "Game request not found."})
                return
            if action == "respond":
                accept = bool(data.get("accept"))
                if uid == game["player_b_uid"] and (
                    (accept and game["status"] == "active")
                    or (not accept and game["status"] == "declined")
                ):
                    self.send_json(200, self.league_snapshot(uid))
                    return
                if uid != game["player_b_uid"] or game["status"] != "invited":
                    raise ValueError(
                        "Only the invited player can respond to this request."
                    )
                if accept:
                    alert = (
                        {game["player_a_uid"]},
                        "Match started",
                        "Your opponent accepted. Open Cue Club for the timer.",
                    )
                    accepted = now_iso()
                    db.execute(
                        "UPDATE games SET status='active',accepted_at=?,started_at=? WHERE id=?",
                        (accepted, accepted, game_id),
                    )
                    db.execute(
                        """DELETE FROM table_next_up WHERE
                           (player_a_uid=? AND player_b_uid=?) OR (player_a_uid=? AND player_b_uid=?)""",
                        (game["player_a_uid"], game["player_b_uid"],
                         game["player_b_uid"], game["player_a_uid"]),
                    )
                else:
                    db.execute(
                        "UPDATE games SET status='declined',finished_at=? WHERE id=?",
                        (now_iso(), game_id),
                    )
                    if game["tournament_match_id"]:
                        db.execute(
                            "UPDATE tournament_matches SET status='pending',game_id='' WHERE id=? AND status='in_progress'",
                            (game["tournament_match_id"],),
                        )
            else:
                winner_uid = str(data.get("winnerUid", ""))
                own_report = (
                    game["report_a_winner_uid"]
                    if uid == game["player_a_uid"]
                    else game["report_b_winner_uid"]
                )
                if game["status"] == "confirmed" and own_report == winner_uid:
                    self.send_json(200, self.league_snapshot(uid))
                    return
                if game["status"] not in ("active", "awaiting_confirmation"):
                    raise ValueError("This game is not waiting for a result.")
                if winner_uid not in (game["player_a_uid"], game["player_b_uid"]):
                    raise ValueError("Choose one of the two players as the winner.")
                report_col = (
                    "report_a_winner_uid"
                    if uid == game["player_a_uid"]
                    else "report_b_winner_uid"
                )
                other_col = (
                    "report_b_winner_uid"
                    if uid == game["player_a_uid"]
                    else "report_a_winner_uid"
                )
                other_report = game[other_col]
                if (
                    "expectedOpponentWinnerUid" in data
                    and data["expectedOpponentWinnerUid"] != other_report
                ):
                    self.send_json(409, {"error": "Your opponent’s result changed. Review it before responding."})
                    return
                db.execute(
                    f"UPDATE games SET {report_col}=? WHERE id=?", (winner_uid, game_id)
                )
                if not other_report:
                    if not own_report:
                        opponent = (
                            game["player_b_uid"]
                            if uid == game["player_a_uid"]
                            else game["player_a_uid"]
                        )
                        alert = (
                            {opponent},
                            "Confirm your match result",
                            "Your opponent reported a winner.",
                        )
                    db.execute(
                        "UPDATE games SET status='awaiting_confirmation' WHERE id=?",
                        (game_id,),
                    )
                elif other_report != winner_uid:
                    db.execute(
                        "UPDATE games SET status='disputed' WHERE id=?", (game_id,)
                    )
                else:
                    confirm_game_result(db, game, winner_uid)
                    alert = (
                        {game["player_a_uid"], game["player_b_uid"]},
                        "Match result confirmed",
                        "The shared results and Elo standings have been updated.",
                    )
        if alert:
            recipients, title, body = alert
            send_match_alert(recipients, game_id, title, body)
        self.send_json(200, self.league_snapshot(uid))

    def admin_resolve_game(self, game_id: str) -> None:
        uid, ok = self.require_admin()
        if not ok:
            return
        winner_uid = str(self.read_json().get("winnerUid", ""))
        with DB_LOCK, connect_db() as db:
            expire_leagues(db)
            game = db.execute(
                "SELECT * FROM games WHERE id=? AND status='disputed'", (game_id,)
            ).fetchone()
            if not game or winner_uid not in (
                game["player_a_uid"],
                game["player_b_uid"],
            ):
                raise ValueError("Choose a player in a disputed game.")
            confirm_game_result(db, game, winner_uid)
        send_match_alert(
            {game["player_a_uid"], game["player_b_uid"]},
            game_id,
            "Match result resolved",
            "The shared results and Elo standings have been updated.",
        )
        self.send_json(200, self.league_snapshot(uid))

    def upload_photo(self) -> None:
        uid, ok = self.require_user()
        if not ok:
            return
        content_type = self.headers.get("Content-Type", "").split(";", 1)[0].lower()
        if content_type not in PHOTO_TYPES:
            raise ValueError(PHOTO_ERROR)
        try:
            # Read a small excess so clients just over the limit receive the error
            # response even if they sent the entire request without waiting.
            raw = self.read_body(MAX_PHOTO + PHOTO_REJECTION_MARGIN)
            if len(raw) > MAX_PHOTO:
                raise ValueError(PHOTO_ERROR)
        except ValueError as exc:
            raise ValueError(PHOTO_ERROR) from exc
        ext, signature = PHOTO_TYPES[content_type]
        if (
            len(raw) < 12
            or not raw.startswith(signature)
            or (ext == "webp" and raw[8:12] != b"WEBP")
        ):
            raise ValueError("That file does not match its image type.")
        digest = hashlib.sha256(raw).hexdigest()
        filename = f"{uid}-{digest}.{ext}"
        path = PHOTOS / filename
        with DB_LOCK:
            with connect_db() as db:
                member = db.execute(
                    "SELECT photo_file FROM members WHERE uid=? AND removed_at=''", (uid,)
                ).fetchone()
                if member is None:
                    self.send_json(401, {"error": "Sign in again to continue."})
                    return
                previous = member["photo_file"]
                if database.POSTGRES:
                    db.execute(
                        "INSERT INTO profile_photos(filename,content) VALUES(?,?) ON CONFLICT(filename) DO NOTHING",
                        (filename, raw),
                    )
                    if previous and previous != filename:
                        db.execute(
                            "DELETE FROM profile_photos WHERE filename=?", (previous,)
                        )
                elif not path.is_file():
                    temp = path.with_suffix(path.suffix + ".tmp")
                    try:
                        temp.write_bytes(raw)
                        temp.replace(path)
                    finally:
                        temp.unlink(missing_ok=True)
                db.execute(
                    "UPDATE members SET photo_file=?, updated_at=? WHERE uid=?",
                    (filename, now_iso(), uid),
                )
            if not database.POSTGRES and previous and previous != filename:
                try:
                    (PHOTOS / previous).unlink(missing_ok=True)
                except OSError as exc:
                    print("Could not remove old profile photo:", type(exc).__name__)
        self.send_json(200, {"photoPath": filename})


def expire_guests(db):
    expired = db.execute(
        """SELECT uid,photo_file FROM members
           WHERE guest_expires_at>0 AND guest_expires_at<=? AND removed_at=''""",
        (int(time.time()),),
    ).fetchall()
    for guest in expired:
        remove_account_data(db, guest["uid"], guest["photo_file"], True)


def remove_account_data(db, uid, photo, guest=False):
    db.execute(
        """DELETE FROM doubles_players WHERE game_id IN (
               SELECT p.game_id FROM doubles_players p JOIN doubles_games g ON g.id=p.game_id
               WHERE p.uid=? AND (g.status<>'confirmed' OR ?=1)
           )""", (uid, int(guest))
    )
    db.execute("DELETE FROM doubles_games WHERE NOT EXISTS (SELECT 1 FROM doubles_players WHERE game_id=doubles_games.id)")
    affected = db.execute(
        """SELECT DISTINCT tm.tournament_id FROM tournament_matches tm
           JOIN tournaments t ON t.id=tm.tournament_id WHERE t.status='active'
           AND (tm.player_a_uid=? OR tm.player_b_uid=? OR tm.winner_uid=?)""",
        (uid, uid, uid),
    ).fetchall()
    # Withdraw the player from ready/live fixtures so the opponent can advance.
    db.execute(
        """UPDATE tournament_matches
           SET winner_uid=CASE WHEN player_a_uid=? THEN player_b_uid ELSE player_a_uid END,
               player_a_uid=CASE WHEN player_a_uid=? THEN player_b_uid ELSE player_a_uid END,
               player_b_uid='', status='bye', game_id=''
           WHERE status IN ('pending','in_progress')
             AND (player_a_uid=? OR player_b_uid=?)""",
        (uid, uid, uid, uid),
    )
    db.execute(
        """DELETE FROM games WHERE (status<>'confirmed' OR ?=1)
           AND (player_a_uid=? OR player_b_uid=? OR initiated_by_uid=?)""",
        (int(guest), uid, uid, uid),
    )
    db.execute("UPDATE matches SET player_a_name='Former player' WHERE player_a_uid=?", (uid,))
    db.execute("UPDATE matches SET player_b_name='Former player' WHERE player_b_uid=?", (uid,))
    if guest:
        db.execute(
            """UPDATE matches SET rating_only=1,player_a_name='',player_b_name='',recorded_by_uid=''
               WHERE player_a_uid=? OR player_b_uid=?""", (uid, uid)
        )
    db.execute("UPDATE matches SET recorded_by_uid='' WHERE recorded_by_uid=?", (uid,))
    db.execute("DELETE FROM web_push_subscriptions WHERE uid=?", (uid,))
    db.execute("DELETE FROM table_next_up WHERE player_a_uid=? OR player_b_uid=?", (uid, uid))
    db.execute("DELETE FROM sessions WHERE uid=?", (uid,))
    db.execute("DELETE FROM account_deletion_requests WHERE uid=?", (uid,))
    # Keep the anonymous match identity so replaying Elo uses the original opponents.
    db.execute(
        """UPDATE members SET email=?,display_name='Former player',is_admin=0,password_salt='',
           password_hash='',photo_file='',created_at='',updated_at='',guest_expires_at=0,
           removed_at=? WHERE uid=?""",
        ("removed:" + uid, now_iso(), uid),
    )
    if photo and database.POSTGRES:
        db.execute("DELETE FROM profile_photos WHERE filename=?", (photo,))
    elif photo:
        (PHOTOS / photo).unlink(missing_ok=True)
    for tournament in affected:
        refresh_bracket(db, tournament["tournament_id"])
        final = db.execute(
            """SELECT status,winner_uid FROM tournament_matches WHERE tournament_id=?
               ORDER BY round_no DESC,slot_no LIMIT 1""",
            (tournament["tournament_id"],),
        ).fetchone()
        if final and final["status"] in ("bye", "completed") and not final["winner_uid"]:
            db.execute("UPDATE tournaments SET status='cancelled' WHERE id=?", (tournament["tournament_id"],))
            db.execute(
                """UPDATE seasons SET status='completed',ended_at=?
                   WHERE id=(SELECT season_id FROM tournaments WHERE id=?) AND status='active'""",
                (now_iso(), tournament["tournament_id"]),
            )


def expire_leagues(db: sqlite3.Connection) -> None:
    expired = db.execute(
        "SELECT id FROM seasons WHERE status='active' AND deadline_ms > 0 AND deadline_ms <= ?",
        (int(time.time() * 1000),),
    ).fetchall()
    for season in expired:
        close_league(db, season["id"])
    if expired:
        db.commit()


def close_league(db: sqlite3.Connection, season_id: str) -> None:
    stamp = now_iso()
    db.execute(
        "UPDATE seasons SET status='completed',ended_at=? WHERE id=?",
        (stamp, season_id),
    )
    tournament = db.execute(
        "SELECT id FROM tournaments WHERE season_id=? AND status='active'", (season_id,)
    ).fetchone()
    if tournament:
        tournament_id = tournament["id"]
        db.execute(
            "UPDATE tournaments SET status='cancelled' WHERE id=?", (tournament_id,)
        )
        db.execute(
            """UPDATE games SET status='declined',finished_at=?
               WHERE status IN ('invited','active','awaiting_confirmation','disputed')
               AND tournament_match_id IN (SELECT id FROM tournament_matches WHERE tournament_id=?)""",
            (stamp, tournament_id),
        )


def refresh_bracket(db: sqlite3.Connection, tournament_id: str) -> None:
    rows = db.execute(
        "SELECT * FROM tournament_matches WHERE tournament_id=? ORDER BY round_no,slot_no",
        (tournament_id,),
    ).fetchall()
    terminal = {"bye", "completed"}
    for row in rows:
        if not row["source_a_match_id"] or row["status"] in (
            "completed",
            "bye",
            "in_progress",
        ):
            continue
        feeder = """SELECT COALESCE(m.uid,'') AS winner_uid, tm.status FROM tournament_matches tm
                    LEFT JOIN members m ON m.uid=tm.winner_uid AND m.removed_at='' WHERE tm.id=?"""
        left = db.execute(
            feeder,
            (row["source_a_match_id"],),
        ).fetchone()
        right = db.execute(
            feeder,
            (row["source_b_match_id"],),
        ).fetchone()
        left_done = bool(left and left["status"] in terminal)
        right_done = bool(right and right["status"] in terminal)
        a_uid = left["winner_uid"] if left_done else ""
        b_uid = right["winner_uid"] if right_done else ""
        if left_done and right_done:
            if a_uid and b_uid:
                db.execute(
                    "UPDATE tournament_matches SET player_a_uid=?,player_b_uid=?,winner_uid='',status='pending' WHERE id=?",
                    (a_uid, b_uid, row["id"]),
                )
            else:
                winner = a_uid or b_uid
                db.execute(
                    "UPDATE tournament_matches SET player_a_uid=?,player_b_uid='',winner_uid=?,status='bye' WHERE id=?",
                    (winner, winner, row["id"]),
                )
        else:
            db.execute(
                "UPDATE tournament_matches SET player_a_uid=?,player_b_uid=?,status='waiting' WHERE id=?",
                (a_uid, b_uid, row["id"]),
            )
    final = db.execute(
        "SELECT winner_uid,status FROM tournament_matches WHERE tournament_id=? ORDER BY round_no DESC,slot_no LIMIT 1",
        (tournament_id,),
    ).fetchone()
    if final and final["status"] in terminal and final["winner_uid"]:
        db.execute(
            "UPDATE tournaments SET status='completed',champion_uid=? WHERE id=?",
            (final["winner_uid"], tournament_id),
        )
        db.execute(
            """UPDATE seasons SET status='completed', ended_at=?
               WHERE id=(SELECT season_id FROM tournaments WHERE id=?) AND status='active'""",
            (now_iso(), tournament_id),
        )


def confirm_game_result(
    db: sqlite3.Connection, game: sqlite3.Row, winner_uid: str
) -> None:
    stamp = now_iso()
    stats_id = game["stats_match_id"] or uuid.uuid4().hex
    a = db.execute(
        "SELECT display_name FROM members WHERE uid=?", (game["player_a_uid"],)
    ).fetchone()
    b = db.execute(
        "SELECT display_name FROM members WHERE uid=?", (game["player_b_uid"],)
    ).fetchone()
    if not a or not b:
        raise ValueError("A player profile could not be found.")
    db.execute(
        """INSERT INTO matches(id,player_a_uid,player_a_name,player_b_uid,player_b_name,winner_uid,recorded_by_uid,created_at)
           VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING""",
        (
            stats_id,
            game["player_a_uid"],
            a["display_name"],
            game["player_b_uid"],
            b["display_name"],
            winner_uid,
            game["initiated_by_uid"],
            stamp,
        ),
    )
    db.execute(
        "UPDATE games SET status='confirmed',winner_uid=?,finished_at=?,stats_match_id=? WHERE id=?",
        (winner_uid, stamp, stats_id, game["id"]),
    )
    if game["tournament_match_id"]:
        db.execute(
            "UPDATE tournament_matches SET status='completed',winner_uid=?,stats_match_id=? WHERE id=?",
            (winner_uid, stats_id, game["tournament_match_id"]),
        )
        tournament = db.execute(
            "SELECT tournament_id FROM tournament_matches WHERE id=?",
            (game["tournament_match_id"],),
        ).fetchone()
        if tournament:
            refresh_bracket(db, tournament["tournament_id"])


def send_match_alert(recipients: set[str], game_id: str, title: str, body: str) -> None:
    if WEB_APP_ENABLED and push_delivery.webpush is not None:
        push_delivery.workers.submit(
            push_delivery.deliver,
            connect_db,
            DB_LOCK,
            ADMIN_EMAIL,
            recipients,
            {"title": title, "body": body, "gameId": game_id,
             "tag": "cue-club-" + game_id + "-" + title},
        )


class OfficePoolServer(ThreadingHTTPServer):
    next_guest_cleanup = 0.0

    def service_actions(self):
        now = time.monotonic()
        if now < self.next_guest_cleanup:
            return
        self.next_guest_cleanup = now + 30
        try:
            with DB_LOCK, connect_db() as db:
                expire_guests(db)
        except Exception as error:
            print("Guest cleanup failed:", type(error).__name__)


def main() -> None:
    init_db()
    httpd = OfficePoolServer((HOST, PORT), Handler)
    print(f"Office Pool server listening on http://{HOST}:{PORT}")
    if not ON_RENDER:
        print(
            "The API listens locally by default. Use Tailscale Funnel for a shared HTTPS address."
        )
    print(
        "Keep this window open while the PC is hosting the league. Press Ctrl+C to stop."
    )
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("Stopping Office Pool server...")
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()
