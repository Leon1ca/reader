#!/usr/bin/env python3
"""Run each standalone renderer regression with bounded time and retained evidence."""
import argparse
import concurrent.futures
import json
import os
from pathlib import Path
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--tests-dir', type=Path, default=Path(__file__).parent)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--workers', type=int, default=2)
parser.add_argument('--timeout', type=int, default=180)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
assert 1 <= args.workers <= 4
scripts = sorted(args.tests_dir.glob('renderer-*.cjs'))
assert scripts, 'No renderer tests found'

def run(script):
    start = time.monotonic()
    with (args.output / (script.stem + '.log')).open('w') as log:
        try:
            result = subprocess.run(['node', str(script.resolve())], stdout=log, stderr=subprocess.STDOUT,
                                    timeout=args.timeout, env=os.environ.copy())
            status = 'PASS' if result.returncode == 0 else 'FAIL'
        except subprocess.TimeoutExpired:
            status = 'TIMEOUT'
    record = dict(test=script.name, status=status, seconds=round(time.monotonic()-start, 2))
    print(json.dumps(record), flush=True)
    return record

with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
    records = list(executor.map(run, scripts))
(args.output / 'results.json').write_text(json.dumps(records, indent=2)+'\n')
raise SystemExit(0 if all(r['status']=='PASS' for r in records) else 1)
