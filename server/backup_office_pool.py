"""Create and verify Cue Club backups without stopping the PC server."""

from __future__ import annotations

import argparse
import logging
import os
import shutil
import sqlite3
import tempfile
import time
import zipfile
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path

DEFAULT_DATA = Path(__file__).resolve().parent / "data"
DEFAULT_DESTINATION = Path.home() / "Documents" / "Cue Club Backups"
DATABASE = "office_pool.sqlite3"


def open_readonly(path: Path) -> sqlite3.Connection:
    return sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True, timeout=15)


def referenced_photos(database: Path) -> list[str]:
    with closing(open_readonly(database)) as db:
        if db.execute("PRAGMA integrity_check").fetchall() != [("ok",)]:
            raise ValueError(
                "The backup database did not pass SQLite integrity checks."
            )
        photos = [
            row[0]
            for row in db.execute(
                "SELECT DISTINCT photo_file FROM members WHERE photo_file<>'' ORDER BY photo_file"
            )
        ]
    if any(name != Path(name).name or name in (".", "..") for name in photos):
        raise ValueError("The database contains an invalid photo filename.")
    return photos


def verify_archive(archive: Path) -> int:
    with tempfile.TemporaryDirectory(prefix="cue-restore-") as directory:
        database = Path(directory) / DATABASE
        with zipfile.ZipFile(archive) as backup:
            if backup.testzip() is not None:
                raise ValueError("The backup archive is corrupt.")
            with backup.open(DATABASE) as source, database.open("wb") as target:
                shutil.copyfileobj(source, target)
            photos = referenced_photos(database)
            photo_directory = Path(directory) / "photos"
            photo_directory.mkdir()
            for name in photos:
                member = backup.getinfo(f"photos/{name}")
                if member.file_size == 0:
                    raise ValueError(f"Profile photo is empty: {name}")
                with backup.open(member) as source, (Path(directory) / name).open(
                    "wb"
                ) as target:
                    shutil.copyfileobj(source, target)
    return len(photos)


def create_backup(data: Path, destination: Path) -> tuple[Path, int]:
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S-%fZ")
    target = destination / f"Cue-Club-{stamp}.zip"
    for attempt in range(3):
        with tempfile.TemporaryDirectory(
            prefix="cue-backup-", dir=destination
        ) as directory:
            stage = Path(directory)
            database = stage / DATABASE
            with closing(open_readonly(data / DATABASE)) as source, closing(
                sqlite3.connect(database)
            ) as snapshot:
                source.backup(snapshot)
            photos = referenced_photos(database)
            archive = stage / target.name
            try:
                with zipfile.ZipFile(
                    archive, "w", zipfile.ZIP_DEFLATED, compresslevel=1
                ) as backup:
                    backup.write(database, DATABASE)
                    for name in photos:
                        backup.write(data / "photos" / name, f"photos/{name}")
            except FileNotFoundError:
                if attempt == 2:
                    raise
                time.sleep(0.5)
                continue
            verify_archive(archive)
            archive.replace(target)
            return target, len(photos)
    raise RuntimeError("Unable to create backup.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--data",
        type=Path,
        default=os.environ.get("OFFICE_POOL_DATA_DIR", DEFAULT_DATA),
    )
    parser.add_argument("--destination", type=Path, default=DEFAULT_DESTINATION)
    parser.add_argument("--verify", type=Path)
    args = parser.parse_args()
    if args.verify:
        count = verify_archive(args.verify)
        print(f"Restore check passed for the database and {count} profile photo(s).")
        return 0
    args.destination.mkdir(parents=True, exist_ok=True)
    logging.basicConfig(
        filename=args.destination / "backup.log",
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
        encoding="utf-8",
    )
    try:
        archive, count = create_backup(args.data, args.destination)
    except Exception:
        logging.exception("Cue Club backup failed")
        return 1
    logging.info("Verified %s with %d profile photo(s)", archive.name, count)
    print(archive)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
