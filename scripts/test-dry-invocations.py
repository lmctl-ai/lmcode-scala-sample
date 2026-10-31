#!/usr/bin/env python3
"""Offline sample-host dry sentinel, preview-count, and real-mode parity control."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[1]
host = str(Path(sys.argv[1]).resolve())
program = (root / "sample-suite/bytecode/014-ledger-methods.dslb").resolve()
expected_real = (root / "sample-suite/expected/014-ledger-methods.out").read_bytes()
with tempfile.TemporaryDirectory(prefix="lmcode-jvm-dry-") as tmp:
    tmp = Path(tmp)
    sentinel = tmp / "ledger-record-sentinel"
    manifest = tmp / "invocation.json"

    def run(dry):
        manifest.write_text(json.dumps({
            "version": 1,
            "mode": "single",
            "dry": dry,
            "parts": [{"index": 0, "file": str(program)}],
        }), encoding="utf-8")
        env = dict(os.environ, LMCODE_INVOCATION_FILE=str(manifest), LMCODE_H6_SENTINEL=str(sentinel))
        result = subprocess.run([host, "--lmcode", str(program)], capture_output=True, env=env)
        if result.returncode != 0 or result.stderr:
            raise AssertionError((dry, result.returncode, result.stdout, result.stderr))
        lines = result.stdout.splitlines()
        if len(lines) < 4 or lines[-3] != b"LMCODE_RETURN_BEGIN" or lines[-1] != b"LMCODE_RETURN_END":
            raise AssertionError(("terminal frame", dry, result.stdout))
        envelope = json.loads(lines[-2])
        summaries = [json.loads(line)["host_summary"] for line in lines if line.startswith(b'{"host_summary":')]
        if len(summaries) != 1:
            raise AssertionError(("host summary", dry, result.stdout))
        return result.stdout, envelope, summaries[0]

    dry_stdout, dry_envelope, dry_summary = run(True)
    if sentinel.exists():
        raise AssertionError("dry ledger.record wrote the real callback sentinel")
    if dry_envelope != {"ok": True, "result": None, "dry": True}:
        raise AssertionError(("dry envelope", dry_envelope, dry_stdout))
    if (dry_summary.get("balance"), dry_summary.get("entries"), dry_summary.get("note"), dry_summary.get("dry_preview_calls")) != (0, 0, "", 1):
        raise AssertionError(("dry host state or preview count", dry_summary, dry_stdout))
    print("PASS dry skips the real ledger callback and calls the preview once", flush=True)

    sentinel.unlink(missing_ok=True)
    real_stdout, real_envelope, real_summary = run(False)
    if not sentinel.exists() or sentinel.read_bytes() != b"setup\nrecord\n":
        raise AssertionError("real ledger.record did not write its sentinel")
    if real_stdout != expected_real:
        raise AssertionError(("real byte/exit output changed", real_stdout, expected_real))
    if "dry" in real_envelope or real_envelope != {"ok": True, "result": None}:
        raise AssertionError(("real envelope changed", real_envelope))
    if (real_summary.get("balance"), real_summary.get("entries"), real_summary.get("note")) != (125, 1, "paid"):
        raise AssertionError(("real host state changed", real_summary))
    print("PASS real callback and byte envelope remain unchanged", flush=True)
