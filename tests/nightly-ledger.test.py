import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timezone

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

    def test_two_hosts_cannot_reserve_duplicate_versions(self):
        with tempfile.TemporaryDirectory() as home:
            directory = Path(home) / ".local/state/lab4code-releases"
            directory.mkdir(parents=True)
            (directory / "zen-mode.json").write_text(json.dumps(seeded("2020-01-01")))
            env = {**os.environ, "HOME": home}
            processes = [subprocess.Popen(["python3", str(SCRIPT), "zen-mode", "reserve", sha * 40],
                         env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True) for sha in ("b", "c")]
            results = []
            for process in processes:
                output, error = process.communicate(timeout=10)
                self.assertEqual(process.returncode, 0, error)
                results.append(json.loads(output))
            self.assertEqual(sum(result["build"] for result in results), 1)
            saved = json.loads((directory / "zen-mode.json").read_text())
            self.assertEqual(saved["lastReserved"]["versionCode"], 41)
            self.assertEqual(len(saved["attempts"]), 2)


if __name__ == "__main__":
    unittest.main()
