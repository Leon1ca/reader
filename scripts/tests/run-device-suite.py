#!/usr/bin/env python3
"""Run Android tests separately, prestarting both packages for OEM background limits."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time

p=argparse.ArgumentParser()
p.add_argument('--serial',required=True)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--class-name')
p.add_argument('--timeout',type=int,default=100)
a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
root=Path(__file__).resolve().parents[2]
adb=['adb','-s',a.serial]
def command(args,timeout=30):
 return subprocess.run(adb+args,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout)
tests=[]
for f in sorted((root/'app/src/androidTest/java').rglob('*.kt')):
 text=f.read_text();package=re.search(r'^package\s+(\S+)',text)
 cls=re.search(r'^(?:internal )?class (\w+)',text,re.M)
 if package and cls:
  name=package[1]+'.'+cls[1]
  if a.class_name and name!=a.class_name:continue
  if cls[1] in ('AcceptanceFixturesTest','DevicePerformanceTest','LayoutSnapshotTest'):continue
  tests.extend((name,m) for m in re.findall(r'@Test\s+(?:fun)\s+(\w+)\s*\(',text))
assert tests,'No selected tests found'
records=[]
for cls,method in tests:
 name=cls+'#'+method;start=time.monotonic();log=a.output/(cls.rsplit('.',1)[-1]+'-'+method+'.log')
 try:
  # Each instrument invocation starts a new process; the previous test's UI teardown
  # can otherwise trigger MIUI's cross-package background-launch restriction.
  setup=command(['shell','content','query','--uri','content://com.localreader.app.test.fixtures/preflight.txt'])
  launch=command(['shell','am','start','-W','-n','com.localreader.app/.MainActivity'])
  result=command(['shell','am','instrument','-w','-e','class',name,'com.localreader.app.test/androidx.test.runner.AndroidJUnitRunner'],a.timeout)
  log.write_text(setup.stdout+'\n'+launch.stdout+'\n'+result.stdout)
  status='PASS' if re.search(r'OK \(\d+ tests?\)',result.stdout) else 'FAIL'
 except subprocess.TimeoutExpired as error:
  raw=error.stdout or b'';log.write_bytes(raw if isinstance(raw,bytes) else raw.encode())
  command(['shell','am','force-stop','com.localreader.app']);status='TIMEOUT'
 record=dict(test=name,status=status,seconds=round(time.monotonic()-start,2));records.append(record)
 (a.output/'results.json').write_text(json.dumps(records,ensure_ascii=False,indent=2)+'\n')
 print(json.dumps(record,ensure_ascii=False),flush=True)
raise SystemExit(0 if all(r['status']=='PASS' for r in records) else 1)
