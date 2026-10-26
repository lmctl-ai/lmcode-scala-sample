#!/usr/bin/env python3
"""Recompile with the selected lmscript; never rewrite the checked-in oracle.

Cases that need a compiler feature this lmscript lacks are excluded explicitly (the corpus README's partial
reproduction contract). Their pinned bytecode still runs in the corpus check; only recompilation is skipped.
"""
from pathlib import Path
import json
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parent.parent
FEATURE = 'lmcode-literals-v1'
excluded = {c['name'] for c in json.loads((root / 'sample-suite/manifest.json').read_text())['cases']
            if FEATURE in c.get('compiler_features', [])}

with tempfile.TemporaryDirectory(prefix='sample-recompile-') as temporary:
    out = Path(temporary)
    subprocess.run([sys.executable, str(root / 'sample-suite/compile.py'), '--output', str(out),
                    '--exclude-feature', FEATURE], check=True)
    expected = {p.stem: p for p in (root / 'sample-suite/bytecode').glob('*.dslb')}
    produced = {p.stem: p for p in out.glob('*.dslb')}
    if set(produced) != set(expected) - excluded:
        sys.exit('compiler produced a different case set')
    for name, source in produced.items():
        if source.read_bytes() != expected[name].read_bytes():
            sys.exit('compiler bytecode drift: ' + name + '.dslb')
print(f'PASS compiler reproduces {len(produced)} pinned bytecode cases')
if excluded:
    print(f'NOTE {len(excluded)} literal cases need {FEATURE} (lmctl-mono 0ce49614); not recompiled here: ' + ', '.join(sorted(excluded)))
