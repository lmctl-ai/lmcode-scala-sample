#!/usr/bin/env python3
"""Vendor the reviewed Java SDK sources and the canonical suite, pinned by sha256; no network.

  scripts/sync.py sdk   [LMCODE_JAVA_DIR]   copy src/main/java + std schema + LICENSE into vendor/lmcode-java
  scripts/sync.py suite [LMCODE_CPP_DIR]    copy sample-suite/ from the canonical corpus
  scripts/sync.py check                     fail if any vendored byte differs from its lock

The default sibling checkouts are ../lmcode-java and ../lmcode-cpp. Vendored files are never edited by hand.
"""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent


def files(directory):
    return sorted(p for p in directory.rglob('*') if p.is_file() and '__pycache__' not in p.parts and p.suffix != '.pyc')


def hashes(directory):
    return {p.relative_to(directory).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in files(directory)}


def commit_of(source):
    return subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()


def clean(source, paths):
    if subprocess.check_output(['git', '-C', str(source), 'status', '--porcelain', '--', *paths], text=True).strip():
        sys.exit(f'commit the source before syncing: {source}')


def copy_tree(source, destination):
    if destination.exists():
        shutil.rmtree(destination)
    for path in files(source):
        target = destination / path.relative_to(source)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)


def write_lock(name, repo, commit, directory):
    lock = {'repo': repo, 'commit': commit, 'sha256': hashes(directory)}
    (ROOT / f'{name}.lock').write_text(json.dumps(lock, indent=2) + '\n', newline='\n')


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in ('sdk', 'suite', 'check'):
        sys.exit('usage: sync.py sdk|suite [SOURCE_DIR] | check')
    mode = sys.argv[1]
    if mode == 'check':
        for name, directory in [('sdk', ROOT / 'vendor/lmcode-java'), ('suite', ROOT / 'sample-suite')]:
            lock = json.loads((ROOT / f'{name}.lock').read_text())
            if hashes(directory) != lock['sha256']:
                sys.exit(f'{name} drift: sync reviewed upstream bytes; do not edit vendored files')
        print('PASS SDK and suite locks')
        return
    if mode == 'sdk':
        source = Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else ROOT.parent / 'lmcode-java'
        clean(source, ['src/main/java', 'std', 'LICENSE'])
        staging = ROOT / 'vendor' / 'lmcode-java'
        staging.parent.mkdir(exist_ok=True)
        if staging.exists():
            shutil.rmtree(staging)
        staging.mkdir(parents=True)
        copy_tree(source / 'src/main/java', staging)
        (staging / 'std').mkdir()
        shutil.copy2(source / 'std/lmcode-std.schema.json', staging / 'std/lmcode-std.schema.json')
        shutil.copy2(source / 'LICENSE', staging / 'LICENSE')
        write_lock('sdk', 'lmctl-ai/lmcode-java', commit_of(source), staging)
        print(f'Pinned sdk at {commit_of(source)}')
    else:
        # The corpus is pinned from committed HEAD, never from a working tree another session may be editing.
        source = Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else ROOT.parent / 'lmcode-cpp'
        commit = commit_of(source)
        staging = ROOT / 'sample-suite'
        if staging.exists():
            shutil.rmtree(staging)
        archive = subprocess.run(['git', '-C', str(source), 'archive', '--format=tar', commit, 'sample-suite'],
                                 capture_output=True, check=True).stdout
        subprocess.run(['tar', '-x', '-C', str(ROOT)], input=archive, check=True)
        write_lock('suite', 'lmctl-ai/lmcode-cpp', commit, staging)
        print(f'Pinned suite at {commit}')


if __name__ == '__main__':
    main()
