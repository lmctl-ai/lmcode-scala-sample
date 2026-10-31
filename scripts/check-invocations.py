#!/usr/bin/env python3
"""Strict real compiler-to-host aggregate oracle, including the single-frame boundary."""
import json, os, shlex, subprocess, sys, tempfile
from pathlib import Path
root=Path(__file__).resolve().parent.parent
host=str(Path(sys.argv[1]).resolve())
source=(root/'sample-suite/invocations.lms').read_text().replace('"__HOST__"',json.dumps(shlex.quote(host)))
with tempfile.TemporaryDirectory(prefix='jvm-multi-') as tmp:
 program=Path(tmp)/'invocations.lms'; program.write_text(source)
 p=subprocess.run([os.environ.get('LMSCRIPT_BIN','lmscript'),'--no-preflight',str(program),host],capture_output=True)
 if p.returncode or b'PASS real single/builder' not in p.stdout: raise AssertionError((p.returncode,p.stdout,p.stderr))
 if p.stdout.count(b'"host_summary":')!=8: raise AssertionError(('summary loss',p.stdout))
 if b'LMCODE_RETURN_BEGIN' in p.stdout or b'LMCODE_RETURN_END' in p.stdout: raise AssertionError(('stale frame leaked to script output',p.stdout))
 if b'{"ok":true,"result":null}\n' in p.stdout: raise AssertionError(('stale bare frame',p.stdout))
 print('PASS public multi-program flow, one host, one frame per run')
