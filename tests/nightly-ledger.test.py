import importlib.util
import json
import base64
import threading
from pathlib import Path
import sys
import unittest
from datetime import datetime
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch
from urllib.error import HTTPError
from io import BytesIO

sys.dont_write_bytecode = True

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/nightly-ledger.py"
spec = importlib.util.spec_from_file_location("ledger", SCRIPT)
ledger = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ledger)


def moment(day):
    return datetime.fromisoformat(day + "T21:00:00+00:00")


def seeded(day="2026-09-16"):
    state, _ = ledger.transition(None, "seed", ["0.1.5", "40", "a" * 40], moment(day))
    return state


class ReleaseLedgerTests(unittest.TestCase):
    def test_failed_revision_is_never_retried_and_next_change_consumes_next_patch(self):
        state = seeded()
        state, first = ledger.transition(state, "reserve", ["b" * 40], moment("2026-09-17"))
        self.assertEqual((first["version"], first["versionCode"]), ("0.1.6", 41))
        state, _ = ledger.transition(state, "finish", [first["id"], "failed"])
        state, retry = ledger.transition(state, "reserve", ["b" * 40], moment("2026-09-18"))
        self.assertFalse(retry["build"])
        state, next_attempt = ledger.transition(state, "reserve", ["c" * 40], moment("2026-09-18"))
        self.assertEqual((next_attempt["version"], next_attempt["versionCode"]), ("0.1.7", 42))
        self.assertEqual(state["latest"]["version"], "0.1.5")

    def test_new_commit_same_day_and_active_release_are_gated(self):
        state = seeded()
        state, first = ledger.transition(state, "reserve", ["b" * 40], moment("2026-09-17"))
        state, active = ledger.transition(state, "reserve", ["c" * 40], moment("2026-09-18"))
        self.assertEqual(active["reason"], "another release is still active")
        state, _ = ledger.transition(state, "finish", [first["id"], "succeeded"])
        state, same_day = ledger.transition(state, "reserve", ["c" * 40], moment("2026-09-17"))
        self.assertEqual(same_day["reason"], "daily attempt already used")
        self.assertEqual(state["latest"]["sha"], "b" * 40)

    def test_vienna_date_handles_winter_summer_and_midnight(self):
        for timestamp, expected in [("2026-09-16T22:30:00+00:00", "2026-09-17"),
                                    ("2026-12-16T22:30:00+00:00", "2026-12-16")]:
            state, _ = ledger.transition(None, "seed", ["0.1.5", "8", "a" * 40], datetime.fromisoformat(timestamp))
            self.assertEqual(state["lastAttemptDay"], expected)

    def test_version_code_limit_and_uninitialized_state_fail_closed(self):
        with self.assertRaises(ValueError):
            ledger.transition(None, "reserve", ["b" * 40])
        state = seeded()
        state["lastReserved"]["versionCode"] = 2100000000
        with self.assertRaises(ValueError):
            ledger.transition(state, "reserve", ["b" * 40], moment("2026-09-17"))

class GitHubLedgerTests(unittest.TestCase):
    def setUp(self):
        self.state = seeded("2020-01-01")
        self.sha = "1" * 40
        self.calls = []

    def request(self, method, url, token, payload=None):
        self.assertEqual(token, "secret-token")
        self.assertTrue(url.startswith("https://api.github.com/repos/MaximilianMauroner/zen-mode/contents/ledger.json"))
        self.calls.append(method)
        if method == "GET":
            self.assertTrue(url.endswith("?ref=release-state"))
            return {"encoding": "base64", "sha": self.sha,
                    "content": base64.b64encode(json.dumps(self.state).encode()).decode()}
        self.assertEqual(payload["branch"], "release-state")
        if payload["sha"] != self.sha:
            raise ValueError("GitHub ledger PUT failed (HTTP 409); reconcile before retrying")
        self.state = json.loads(base64.b64decode(payload["content"]))
        self.sha = "2" * 40
        return {"content": {"sha": self.sha}}

    def transact(self, action, args, request=None):
        return ledger.transact("zen-mode", action, args, "secret-token", request or self.request, moment("2026-09-17"))

    def test_status_and_duplicate_gates_never_write(self):
        self.assertEqual(self.transact("status", []), self.state)
        self.assertFalse(self.transact("reserve", ["a" * 40])["build"])
        self.assertEqual(self.calls, ["GET", "GET"])
        self.state["lastAttemptDay"] = "2026-09-17"
        self.assertEqual(self.transact("reserve", ["b" * 40])["reason"], "daily attempt already used")
        self.assertEqual(self.calls[-1], "GET")

    def test_changed_state_is_saved_and_idempotent_finish_does_not_write(self):
        result = self.transact("reserve", ["b" * 40])
        self.assertTrue(result["build"])
        self.assertEqual(self.state["active"], result["id"])
        self.assertEqual(self.calls, ["GET", "PUT"])
        self.transact("finish", [result["id"], "succeeded"])
        count = self.calls.count("PUT")
        self.transact("finish", [result["id"], "succeeded"])
        self.assertEqual(self.calls.count("PUT"), count)

    def test_two_readers_cannot_both_reserve_from_same_blob(self):
        barrier = threading.Barrier(2)
        lock = threading.Lock()
        def concurrent(method, url, token, payload=None):
            if method == "GET":
                snapshot = self.request(method, url, token)
                barrier.wait(timeout=5)
                return snapshot
            with lock:
                return self.request(method, url, token, payload)
        def reserve(sha):
            try:
                return self.transact("reserve", [sha * 40], concurrent)
            except ValueError as error:
                return str(error)
        with ThreadPoolExecutor(2) as pool:
            results = list(pool.map(reserve, ["b", "c"]))
        self.assertEqual(sum(isinstance(result, dict) and result["build"] for result in results), 1)
        self.assertEqual(self.calls.count("PUT"), 2)
        self.assertEqual(self.state["lastReserved"]["versionCode"], 41)
        self.assertTrue(any("409" in result for result in results if isinstance(result, str)))

    def test_lost_reservation_response_or_cancel_leaves_active_and_no_retry(self):
        def lost(method, url, token, payload=None):
            result = self.request(method, url, token, payload)
            if method == "PUT":
                raise ValueError("GitHub ledger PUT result is uncertain; reconcile before retrying")
            return result
        with self.assertRaisesRegex(ValueError, "uncertain"):
            self.transact("reserve", ["b" * 40], lost)
        self.assertEqual(self.calls, ["GET", "PUT"])
        self.assertEqual(self.transact("reserve", ["c" * 40])["reason"], "another release is still active")
        self.assertEqual(self.calls.count("PUT"), 1)

    def test_lost_finish_keeps_active_when_write_did_not_arrive(self):
        reservation = self.transact("reserve", ["b" * 40])
        def lost(method, url, token, payload=None):
            if method == "PUT":
                raise ValueError("GitHub ledger PUT result is uncertain; reconcile before retrying")
            return self.request(method, url, token)
        with self.assertRaisesRegex(ValueError, "uncertain"):
            self.transact("finish", [reservation["id"], "failed"], lost)
        self.assertEqual(self.state["active"], reservation["id"])

    def test_missing_invalid_state_and_seed_never_initialize(self):
        for state in [None, {}, {"attempts": {}, "active": None}]:
            self.state = state
            with self.assertRaisesRegex(ValueError, "cutover"):
                self.transact("reserve", ["b" * 40])
        self.assertNotIn("PUT", self.calls)
        with self.assertRaisesRegex(ValueError, "manual"):
            self.transact("seed", ["0.1.5", "40", "a" * 40])

    def test_http_errors_do_not_expose_body_token_or_retry(self):
        opener = unittest.mock.Mock()
        opener.open.side_effect = HTTPError("https://api.github.com", 409, "secret-token", {}, BytesIO(b"secret-body"))
        with patch.object(ledger, "build_opener", return_value=opener):
            with self.assertRaises(ValueError) as raised:
                ledger.github_request("PUT", "https://api.github.com", "secret-token", {})
        self.assertNotIn("secret", str(raised.exception))
        self.assertEqual(opener.open.call_count, 1)
        self.assertIsNone(ledger.NoRedirects().redirect_request(None, None, 302, None, None, "https://evil.test"))



if __name__ == "__main__":
    unittest.main()
