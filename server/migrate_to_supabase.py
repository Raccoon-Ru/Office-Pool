"""Restore a verified Cue Club SQLite backup into an empty Supabase database."""

import argparse
import sqlite3
import tempfile
import zipfile
from contextlib import closing
from pathlib import Path

from .backup_office_pool import DATABASE, referenced_photos, verify_archive
from . import database, office_pool_server as server

TABLES = (
    "members",
    "sessions",
    "account_deletion_requests",
    "table_next_up",
    "matches",
    "seasons",
    "tournaments",
    "tournament_matches",
    "games",
)
ORDERED = {"matches", "seasons", "games"}


def import_backup(archive: Path):
    if not database.POSTGRES:
        raise RuntimeError(
            "Set DATABASE_URL to the Supabase session pooler connection first."
        )
    verify_archive(archive)
    server.init_db(sync_admin=False)
    with tempfile.TemporaryDirectory(
        prefix="cue-club-migration-"
    ) as folder, zipfile.ZipFile(archive) as backup:
        sqlite = Path(folder) / DATABASE
        sqlite.write_bytes(backup.read(DATABASE))
        approved_photos = referenced_photos(sqlite)
        with closing(sqlite3.connect(sqlite)) as source, server.connect_db() as target:
            source.row_factory = sqlite3.Row
            # The target must be empty. This protects any real users already on Render.
            for table in (*TABLES, "profile_photos"):
                if target.execute(f"SELECT 1 FROM {table} LIMIT 1").fetchone():
                    raise RuntimeError(
                        "The cloud database already contains Cue Club data. Import cancelled."
                    )
            counts = {}
            source_tables = {
                row["name"]
                for row in source.execute("SELECT name FROM sqlite_master WHERE type='table'")
            }
            for table in TABLES:
                if table in {"account_deletion_requests", "table_next_up"} and table not in source_tables:
                    counts[table] = 0
                    continue
                # Insertion order is the tiebreaker for Elo when result timestamps match.
                order = " ORDER BY rowid" if table in ORDERED else ""
                rows = source.execute(f"SELECT * FROM {table}{order}")
                columns = ",".join(column[0] for column in rows.description)
                placeholders = ",".join("?" for _ in rows.description)
                count = 0
                for row in rows:
                    target.execute(
                        f"INSERT INTO {table} ({columns}) VALUES ({placeholders})",
                        tuple(row),
                    )
                    count += 1
                counts[table] = count
            for filename in approved_photos:
                contents = backup.read(f"photos/{filename}")
                target.execute(
                    "INSERT INTO profile_photos(filename,content) VALUES(?,?)",
                    (filename, contents),
                )
            counts["profile_photos"] = len(approved_photos)
            target.execute(
                "UPDATE members SET is_admin=CASE WHEN lower(email)=? THEN 1 ELSE 0 END",
                (server.ADMIN_EMAIL,),
            )
    return counts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("backup", type=Path, help="Cue Club ZIP backup")
    args = parser.parse_args()
    counts = import_backup(args.backup)
    print(
        "Verified import:",
        ", ".join(f"{table}={count}" for table, count in counts.items()),
    )


if __name__ == "__main__":
    main()
