#!/usr/bin/env python3
"""Atomic release reservations shared by the Mac and coding host."""
import fcntl
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from datetime import datetime, timezone
from uuid import uuid4
from zoneinfo import ZoneInfo


def identity(version, code):
    if not re.fullmatch(r"0\.1\.\d+", version):
        raise ValueError("Release version must be 0.1.patch")
    if not isinstance(code, int) or isinstance(code, bool) or not 1 <= code <= 2100000000:
        raise ValueError("versionCode must be an integer between 1 and 2100000000")
    return {"version": version, "versionCode": code}


def transition(state, action, args, now=None):
    now = now or datetime.now(timezone.utc)
    day = now.astimezone(ZoneInfo("Europe/Vienna")).date().isoformat()
    if action == "status":
        return state, state or {"initialized": False}
    if action == "seed":
        version, code, sha = args
        baseline = identity(version, int(code))
        validate_sha(sha)
        if state is not None:
            if state["latest"] == {**baseline, "sha": sha}:
                return state, {"seeded": False, "reason": "already initialized"}
            raise ValueError("Ledger already initialized; reconcile it with Play before changing it")
        attempt = {**baseline, "sha": sha, "day": day, "status": "succeeded"}
        state = {"lastReserved": baseline, "latest": {**baseline, "sha": sha},
                 "lastAttemptDay": day, "attempts": {sha: attempt}, "active": None}
        return state, {"seeded": True, **baseline}
    if state is None:
        raise ValueError("Seed the ledger from the latest Play release before building")
    if action == "reserve":
        sha, = args
        validate_sha(sha)
        if sha in state["attempts"]:
            return state, {"build": False, "reason": "source revision already attempted"}
        if state["active"]:
            return state, {"build": False, "reason": "another release is still active"}
        if day <= state["lastAttemptDay"]:
            return state, {"build": False, "reason": "daily attempt already used"}
        previous = state["lastReserved"]
        version = "0.1." + str(int(previous["version"].split(".")[2]) + 1)
        reserved = identity(version, previous["versionCode"] + 1)
        attempt = {**reserved, "sha": sha, "day": day, "status": "running", "id": str(uuid4())}
        state["lastReserved"] = reserved
        state["lastAttemptDay"] = day
        state["attempts"][sha] = attempt
        state["active"] = attempt["id"]
        return state, {"build": True, **attempt}
    if action == "finish":
        reservation, status = args
        if status not in ("failed", "succeeded"):
            raise ValueError("Finish status must be failed or succeeded")
        attempt = next((x for x in state["attempts"].values() if x.get("id") == reservation), None)
        if attempt is None:
            raise ValueError("Unknown reservation")
        if attempt["status"] == status:
            return state, {"finished": True, "status": status}
        if state["active"] != reservation or attempt["status"] != "running":
            raise ValueError("Reservation is no longer active")
        attempt["status"] = status
        state["active"] = None
        if status == "succeeded":
            state["latest"] = {key: attempt[key] for key in ("version", "versionCode", "sha")}
        return state, {"finished": True, "status": status}
    raise ValueError("Expected status, seed, reserve, or finish")


def validate_sha(sha):
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Expected a full Git SHA")


def main():
    app, action, *args = sys.argv[1:]
    if app not in ("moodinator", "zen-mode"):
        raise ValueError("Unknown app")
    directory = Path.home() / ".local/state/lab4code-releases"
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(directory, 0o700)
    path = directory / (app + ".json")
    with open(directory / (app + ".lock"), "a") as lock:
        os.chmod(lock.name, 0o600)
        fcntl.flock(lock, fcntl.LOCK_EX)
        state = json.loads(path.read_text()) if path.exists() else None
        state, result = transition(state, action, args)
        if action != "status" and state is not None:
            descriptor, temporary = tempfile.mkstemp(dir=directory)
            try:
                with os.fdopen(descriptor, "w") as output:
                    json.dump(state, output, indent=2)
                    output.write("\n")
                    output.flush()
                    os.fsync(output.fileno())
                os.replace(temporary, path)
            finally:
                if os.path.exists(temporary):
                    os.unlink(temporary)
        print(json.dumps(result))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, TypeError) as error:
        sys.exit(str(error))
