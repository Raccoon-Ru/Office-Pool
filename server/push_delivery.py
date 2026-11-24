"""Send opt-in match alerts to Apple Home Screen web apps."""

from __future__ import annotations

import base64
import binascii
import json
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlsplit

try:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from pywebpush import WebPushException, webpush
except ImportError:
    webpush = None

workers = ThreadPoolExecutor(max_workers=2, thread_name_prefix="cue-web-push")


def encode_key(raw):
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def keys(db):
    stored = db.execute(
        "SELECT private_key, public_key FROM web_push_identity WHERE id=1"
    ).fetchone()
    if stored:
        return stored["private_key"], stored["public_key"]
    if webpush is None:
        raise RuntimeError("Web Push dependencies are unavailable.")
    key = ec.generate_private_key(ec.SECP256R1())
    private = encode_key(
        key.private_bytes(
            serialization.Encoding.DER,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    public = encode_key(
        key.public_key().public_bytes(
            serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint
        )
    )
    db.execute(
        "INSERT INTO web_push_identity(id,private_key,public_key) VALUES(1,?,?)",
        (private, public),
    )
    return private, public


def subscription(data):
    if not isinstance(data, dict):
        raise ValueError("Invalid match alert subscription.")
    endpoint = data.get("endpoint")
    if not isinstance(endpoint, str) or not 1 <= len(endpoint) <= 2048:
        raise ValueError("Invalid match alert endpoint.")
    url = urlsplit(endpoint)
    if (
        not url.hostname
        or not url.hostname.endswith(".push.apple.com")
        or url.scheme != "https"
        or url.port not in (None, 443)
        or url.username
        or url.password
        or url.fragment
        or not url.path
        or any(character.isspace() for character in endpoint)
    ):
        raise ValueError("Match alerts require an Apple Home Screen subscription.")
    encryption = data.get("keys")
    if not isinstance(encryption, dict):
        raise ValueError("Invalid match alert encryption keys.")
    checked = {}
    for name, size in (("p256dh", 65), ("auth", 16)):
        value = encryption.get(name)
        try:
            if not isinstance(value, str) or len(value) > 100:
                raise ValueError
            raw = base64.b64decode(
                value + "=" * (-len(value) % 4), altchars=b"-_", validate=True
            )
            if len(raw) != size or (name == "p256dh" and raw[0] != 4):
                raise ValueError
        except (ValueError, binascii.Error) as exc:
            raise ValueError("Invalid match alert encryption keys.") from exc
        checked[name] = encode_key(raw)
    return endpoint, checked


def deliver(connect_db, db_lock, admin_email, recipients, message):
    if webpush is None or not recipients:
        return
    try:
        placeholders = ",".join("?" for _ in recipients)
        with db_lock, connect_db() as db:
            destinations = db.execute(
                f"""SELECT p.endpoint,p.encryption FROM web_push_subscriptions p
                    JOIN sessions s ON s.token_hash=p.session_hash AND s.uid=p.uid
                    WHERE p.uid IN ({placeholders}) AND datetime(s.created_at)>datetime('now','-60 days')""",
                tuple(recipients),
            ).fetchall()
            if not destinations:
                return
            private, _ = keys(db)
        for destination in destinations:
            endpoint = destination["endpoint"]
            info = {"endpoint": endpoint, "keys": json.loads(destination["encryption"])}
            try:
                webpush(
                    subscription_info=info,
                    data=json.dumps(message),
                    vapid_private_key=private,
                    vapid_claims={"sub": "mailto:" + admin_email},
                    ttl=1800,
                    timeout=8,
                    headers={"Urgency": "high"},
                )
            except WebPushException as error:
                code = (
                    error.response.status_code if error.response is not None else None
                )
                if code in (404, 410):
                    with db_lock, connect_db() as db:
                        db.execute(
                            "DELETE FROM web_push_subscriptions WHERE endpoint=? AND encryption=?",
                            (endpoint, destination["encryption"]),
                        )
                else:
                    print("Match alert delivery failed:", code)
            except Exception as error:
                print("Match alert delivery failed:", type(error).__name__)
    except Exception as error:
        print("Unable to prepare match alerts:", type(error).__name__)
