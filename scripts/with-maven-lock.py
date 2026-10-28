#!/usr/bin/env python3
"""Serialize checkout-local Maven writes, including independent Nx invocations."""
import fcntl
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parent.parent
(root / '.build').mkdir(exist_ok=True)
with (root / '.build/maven.lock').open('a') as lock:
    fcntl.flock(lock, fcntl.LOCK_EX)
    sys.exit(subprocess.call([str(root / 'mvnw'), '-B', f'-Dmaven.repo.local={root / ".build/m2"}', *sys.argv[1:]]))
