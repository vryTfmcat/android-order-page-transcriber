from __future__ import annotations

import secrets
import time


ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz"


def _encode(value: int, length: int) -> str:
    chars = ["0"] * length
    for index in range(length - 1, -1, -1):
        chars[index] = ALPHABET[value & 31]
        value >>= 5
    return "".join(chars)


def new_ulid() -> str:
    timestamp_ms = int(time.time() * 1000)
    randomness = secrets.randbits(80)
    return _encode(timestamp_ms, 10) + _encode(randomness, 16)


def new_entity_id() -> str:
    return "ent_" + new_ulid()
