#!/usr/bin/env python3
"""Run Android tests separately, with an optional controlled foreground launch for OEM restrictions."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import threading

p=argparse.ArgumentParser()
p.add_argument('--serial',required=True)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--class-name')
p.add_argument('--timeout',type=int,default=100)
p.add_argument('--foreground-launch',action='store_true',help='Launch once after instrumentation starts, for MIUI background-activity restrictions')
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
  tests.extend((name,m) for m in re.findall(r'@Test\s+(?:@\w+(?:\([^)]*\))?\s+)*fun\s+(\w+)\s*\(',text))
assert tests,'No selected tests found'
records=[]
for cls,method in tests:
 name=cls+'#'+method;start=time.monotonic();log=a.output/(cls.rsplit('.',1)[-1]+'-'+method+'.log')
 lines=[];timed_out=False
 setup=command(['shell','content','query','--uri','content://com.localreader.app.test.fixtures/preflight.txt'])
 process=subprocess.Popen(adb+['shell','am','instrument','-w','-r','-e','class',name,'com.localreader.app.test/androidx.test.runner.AndroidJUnitRunner'],
                          text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,bufsize=1)
 def collect():
  launched=False
  for line in process.stdout:
   lines.append(line)
   if a.foreground_launch and not launched and 'INSTRUMENTATION_STATUS_CODE: 1' in line:
    # Match ActivityScenario's launch identity, once, while that test is starting. Prelaunching before instrumentation
    # loses its foreground state when the runner replaces the process; repeated launches can interrupt the test itself.
    launched=True;time.sleep(.8)
    try:
     launch=command(['shell','am','start','-W','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER',
                     '-f','0x10008000','-n','com.localreader.app/.MainActivity'])
     lines.append(launch.stdout)
    except subprocess.TimeoutExpired:
     lines.append('Controlled foreground launch timed out\n')
 reader=threading.Thread(target=collect,daemon=True);reader.start()
 try:
  process.wait(timeout=a.timeout)
 except subprocess.TimeoutExpired:
  timed_out=True;process.kill();process.wait();command(['shell','am','force-stop','com.localreader.app'])
 reader.join(timeout=35)
 raw=setup.stdout+'\n'+''.join(lines);log.write_text(raw)
 # An SdkSuppress'd test on an older platform reports "OK (0 tests)": that is a skip, not a pass.
 status='TIMEOUT' if timed_out else 'PASS' if re.search(r'OK \([1-9]\d* tests?\)',raw) else 'SKIP' if re.search(r'OK \(0 tests?\)',raw) else 'FAIL'
 record=dict(test=name,status=status,seconds=round(time.monotonic()-start,2));records.append(record)
 (a.output/'results.json').write_text(json.dumps(records,ensure_ascii=False,indent=2)+'\n')
 print(json.dumps(record,ensure_ascii=False),flush=True)
print({k:sum(r['status']==k for r in records) for k in ('PASS','SKIP','FAIL','TIMEOUT')})
raise SystemExit(0 if all(r['status'] in ('PASS','SKIP') for r in records) else 1)
