#!/usr/bin/env python3
"""Strict real compiler-to-host aggregate oracle, including the single-frame boundary."""
import json, os, subprocess, sys, tempfile
from pathlib import Path
root=Path(__file__).resolve().parent.parent
host=str(Path(sys.argv[1]).resolve())
source=(root/'sample-suite/invocations.lms').read_text().replace('"__HOST__"',json.dumps(host))
with tempfile.TemporaryDirectory(prefix='jvm-multi-') as tmp:
 program=Path(tmp)/'invocations.lms'; program.write_text(source)
 p=subprocess.run([os.environ.get('LMSCRIPT_BIN','lmscript'),'--no-preflight',str(program),host],capture_output=True)
 if p.returncode or b'PASS real single/builder' not in p.stdout: raise AssertionError((p.returncode,p.stdout,p.stderr))
 if p.stdout.count(b'"host_summary":')!=8: raise AssertionError(('summary loss',p.stdout))
 if b'LMCODE_RETURN_BEGIN' in p.stdout or b'LMCODE_RETURN_END' in p.stdout: raise AssertionError(('stale frame leaked to script output',p.stdout))
 if b'{"ok":true,"result":null}\n' in p.stdout: raise AssertionError(('stale bare frame',p.stdout))
 direct=subprocess.run([host,'--lmcode',str(root/'sample-suite/bytecode/068-rule-entries.dslb')],capture_output=True)
 if direct.returncode: raise AssertionError(('direct host baseline',direct.returncode,direct.stdout,direct.stderr))
 lines=direct.stdout.splitlines()
 begins=[i for i,line in enumerate(lines) if line==b'LMCODE_RETURN_BEGIN']
 ends=[i for i,line in enumerate(lines) if line==b'LMCODE_RETURN_END']
 if len(begins)!=1 or len(ends)!=1 or ends[0]!=begins[0]+2: raise AssertionError(('direct host frame shape',direct.stdout))
 bare=b'{"ok":true,"result":null}'
 if any(line==bare for i,line in enumerate(lines) if i<begins[0] or i>ends[0]): raise AssertionError(('stale bare frame',direct.stdout))
 print('PASS public multi-program flow, one host, one frame per run')
