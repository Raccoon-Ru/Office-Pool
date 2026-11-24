"""Database connections for local SQLite and the private Supabase schema."""

import atexit
import os
import sqlite3
from contextlib import closing, contextmanager
from threading import Lock

DATABASE_URL = os.environ.get("DATABASE_URL", "").strip()
POSTGRES = bool(DATABASE_URL)
ORDER_COLUMN = "record_order" if POSTGRES else "rowid"
_pool = None
_pool_lock = Lock()


def postgres_pool():
    global _pool
    with _pool_lock:
        if _pool is None:
            from psycopg.rows import dict_row
            from psycopg_pool import ConnectionPool

            sslmode = os.environ.get("OFFICE_POOL_DB_SSLMODE", "verify-full")
            tls = {"sslmode": sslmode}
            if sslmode == "verify-full":
                tls["sslrootcert"] = os.environ.get(
                    "OFFICE_POOL_DB_SSLROOTCERT", "system"
                )
            _pool = ConnectionPool(
                DATABASE_URL,
                open=True,
                min_size=0,
                max_size=5,
                timeout=12,
                kwargs={
                    "row_factory": dict_row,
                    "prepare_threshold": None,
                    "connect_timeout": 8,
                    **tls,
                },
            )
    return _pool


@atexit.register
def close_pool():
    if _pool is not None:
        _pool.close()


class PostgresConnection:
    def __init__(self, connection):
        self.connection = connection

    def execute(self, sql, args=()):
        from psycopg import IntegrityError

        try:
            return self.connection.execute(sql.replace("?", "%s"), args)
        except IntegrityError as error:
            raise sqlite3.IntegrityError(str(error)) from error

    def executescript(self, sql):
        # The common schema contains only plain DDL statements.
        for statement in sql.split(";"):
            if statement.strip():
                self.execute(statement)

    def commit(self):
        self.connection.commit()


@contextmanager
def connect(path):
    if not POSTGRES:
        with closing(sqlite3.connect(path, timeout=15)) as db, db:
            db.row_factory = sqlite3.Row
            yield db
        return
    with postgres_pool().connection() as db:
        db.execute("SET search_path TO cue_club, pg_catalog")
        yield PostgresConnection(db)


def initialize_postgres(db, schema):
    db.execute("CREATE SCHEMA IF NOT EXISTS cue_club")
    db.execute("REVOKE ALL ON SCHEMA cue_club FROM PUBLIC")
    # Current queries share SQLite SQL apart from this column collation.
    db.executescript(schema.replace(" COLLATE NOCASE", ""))
    db.execute(
        "ALTER TABLE seasons ADD COLUMN IF NOT EXISTS deadline_ms BIGINT NOT NULL DEFAULT 0"
    )
    for table in ("matches", "seasons", "games"):
        db.execute(
            f"ALTER TABLE {table} ADD COLUMN IF NOT EXISTS record_order BIGINT GENERATED ALWAYS AS IDENTITY"
        )
    db.execute(
        "CREATE TABLE IF NOT EXISTS profile_photos (filename TEXT PRIMARY KEY, content BYTEA NOT NULL)"
    )
