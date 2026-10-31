#!/usr/bin/env python3
"""Build real JVM adapter faults in private copies; compile failures never count as catches."""
from pathlib import Path
import os, shutil, subprocess, sys, tempfile
repo=Path(__file__).resolve().parent.parent
source_rel=Path('src/main/scala/shop/Main.scala')
with tempfile.TemporaryDirectory(prefix='lmcode-jvm-mutants-') as tmp:
 work=Path(tmp)/'sample'; shutil.copytree(repo,work,ignore=shutil.ignore_patterns('.git','target','build','__pycache__'))
 source=work/source_rel; original=source.read_text(); host=work/'bin/lmcode-scala-sample'
 bash='bash'
 if os.name=='nt':
  bash=str(Path(os.environ.get('ProgramFiles',r'C:\Program Files'))/'Git'/'bin'/'bash.exe')
  host=work/'build/lmcode-scala-sample.exe'
 def build():
  p=subprocess.run([bash,'scripts/build-jvm.sh'],cwd=work,capture_output=True)
  if p.returncode: raise AssertionError(('mutant compile failed',p.stdout,p.stderr))
  if os.name=='nt':
   p=subprocess.run([bash,'scripts/build-native.sh'],cwd=work,capture_output=True)
   if p.returncode: raise AssertionError(('mutant native compile failed',p.stdout,p.stderr))
 def run(): return subprocess.run([sys.executable,str(work/'scripts/check-invocations.py'),str(host)],cwd=work,capture_output=True)
 build(); p=run(); assert p.returncode==0,(p.stdout,p.stderr)
 mutants=[('stale bare frame','results.writeFrame(System.out)','Protocol.line("{\\"ok\\":true,\\"result\\":null}"); results.writeFrame(System.out)'),('missing part','val files = pairs.map(_(1)).asJava','val files = pairs.dropRight(1).map(_(1)).asJava'),('duplicate part','val files = pairs.map(_(1)).asJava','val files = pairs.flatMap(p => List(p(1), p(1))).asJava'),('argv role loss','val priorities = pairs.map(p => if p.head == "--first" then 0 else if p.head == "--last" then 2 else 1)','val priorities = pairs.map(_ => 1)'),('reset shared host counter','val part = results.registrations().get(position)','val part = results.registrations().get(position); host.ledger.balance = 0; host.ledger.entries = 0')]
 for name,old,new in mutants:
  assert original.count(old)==1,(name,'anchor',original.count(old))
  source.write_text(original.replace(old,new)); build(); p=run()
  assert p.returncode!=0,(name,'mutant survived',p.stdout,p.stderr)
  print('CAUGHT '+name,flush=True); source.write_text(original)
 build()
print('PASS JVM invocation mutation controls')
