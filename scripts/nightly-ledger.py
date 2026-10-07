#!/usr/bin/env python3
"""Release transitions persisted with GitHub Contents API compare-and-swap."""
import base64
import copy
import json
import os
import re
import sys
import subprocess
from urllib.error import HTTPError, URLError
from urllib.request import Request, build_opener, HTTPRedirectHandler
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


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def github_request(method, url, token, payload=None):
    body = json.dumps(payload).encode() if payload is not None else None
    request = Request(url, data=body, method=method, headers={
        "Authorization": "Bearer " + token, "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28", "Content-Type": "application/json",
        "User-Agent": "zen-mode-release-ledger",
    })
    try:
        with build_opener(NoRedirects()).open(request, timeout=30) as response:
            return json.load(response)
    except HTTPError as error:
        raise ValueError(f"GitHub ledger {method} failed (HTTP {error.code}); reconcile before retrying") from None
    except (URLError, OSError, ValueError):
        raise ValueError(f"GitHub ledger {method} result is uncertain; reconcile before retrying") from None


def auth_token():
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        result = subprocess.run(["gh", "auth", "token", "--hostname", "github.com"],
                                capture_output=True, text=True, check=False)
        if result.returncode == 0:
            token = result.stdout.strip()
    if not token:
        raise ValueError("Set GH_TOKEN or GITHUB_TOKEN, or sign in with gh auth login")
    return token


def transact(app, action, args, token, request=github_request, now=None):
    if app != "zen-mode":
        raise ValueError("Expected zen-mode repository")
    if action not in ("status", "reserve", "finish"):
        raise ValueError("Expected status, reserve, or finish; ledger cutover is manual")
    url = "https://api.github.com/repos/MaximilianMauroner/zen-mode/contents/ledger.json"
    record = request("GET", url + "?ref=release-state", token)
    try:
        if record["encoding"] != "base64" or not re.fullmatch(r"[0-9a-f]{40}", record["sha"]):
            raise ValueError()
        state = json.loads(base64.b64decode(record["content"]).decode())
        if not isinstance(state, dict) or not state.get("attempts") or "active" not in state:
            raise ValueError()
    except (KeyError, TypeError, ValueError):
        raise ValueError("GitHub ledger is missing or invalid; manual cutover is required") from None
    original = copy.deepcopy(state)
    state, result = transition(state, action, args, now)
    if state != original:
        saved = request("PUT", url, token, {
            "message": f"release: {action} zen-mode", "branch": "release-state", "sha": record["sha"],
            "content": base64.b64encode((json.dumps(state, indent=2) + "\n").encode()).decode(),
        })
        if not isinstance(saved, dict) or not re.fullmatch(r"[0-9a-f]{40}", saved.get("content", {}).get("sha", "")):
            raise ValueError("GitHub ledger PUT result is uncertain; reconcile before retrying")
    return result


def main():
    app, action, *args = sys.argv[1:]
    print(json.dumps(transact(app, action, args, auth_token())))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, TypeError, KeyError, OSError) as error:
        # State and transport errors must never print API bodies or credentials.
        sys.exit(str(error) if isinstance(error, ValueError) else "Release ledger operation failed")
