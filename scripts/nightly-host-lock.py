#!/usr/bin/env python3
"""One Android release build at a time on each host."""
import fcntl
import os
from pathlib import Path
import subprocess
import sys

lock_path = Path.home() / ".local/state/lab4code-releases/local-build.lock"
lock_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
with open(lock_path, "a") as lock:
    os.chmod(lock_path, 0o600)
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        print("Skipped: another local Android release build is running")
        sys.exit(0)
    result = subprocess.run(sys.argv[1:], pass_fds=(lock.fileno(),), env={**os.environ, "NIGHTLY_HOST_LOCKED": "1"})
    sys.exit(result.returncode)
