#!/usr/bin/env python3
"""Verify local engine/patch integrity; use --upstream for offline reproducible application."""
import argparse, hashlib, json, pathlib, subprocess, tempfile, urllib.request
root = pathlib.Path(__file__).resolve().parents[2]
vendor = root / 'app/src/main/assets/reader/vendor/foliate'
parser = argparse.ArgumentParser()
parser.add_argument('--upstream', type=pathlib.Path)
parser.add_argument('--upstream-dir', type=pathlib.Path)
args = parser.parse_args()
manifest = json.loads((vendor / 'manifest.json').read_text())
upstream = args.upstream.read_bytes() if args.upstream else (args.upstream_dir / 'paginator.js').read_bytes() if args.upstream_dir else urllib.request.urlopen(manifest['source'], timeout=30).read()
sha = lambda value: hashlib.sha256(value).hexdigest()
assert sha(upstream) == manifest['upstreamSha256'], 'upstream mismatch'
packaged = (vendor / 'paginator.js').read_bytes()
assert sha(packaged) == manifest['packagedSha256'], 'packaged file mismatch'
patch = vendor / 'paginator.patch'
assert sha(patch.read_bytes()) == manifest['patchSha256'], 'patch mismatch'
with tempfile.TemporaryDirectory() as directory:
    path = pathlib.Path(directory) / 'paginator.js'
    path.write_bytes(upstream)
    subprocess.run(['patch','--batch','-p1','-i',str(patch)],cwd=directory,check=True,capture_output=True)
    assert path.read_bytes() == packaged, 'patch application differs from packaged engine'
for module in manifest.get('modules',[]):
    upstream = (args.upstream_dir / module['file']).read_bytes() if args.upstream_dir else urllib.request.urlopen(module['source'],timeout=30).read()
    packaged = (vendor / module['file']).read_bytes(); patch = vendor / module['patch']
    assert sha(upstream)==module['upstreamSha256'] and sha(packaged)==module['packagedSha256'] and sha(patch.read_bytes())==module['patchSha256'], module['file']+' hash mismatch'
    with tempfile.TemporaryDirectory() as directory:
        source = pathlib.Path(directory) / module['file'];source.write_bytes(upstream)
        subprocess.run(['patch','--batch','-p1','-i',str(patch)],cwd=directory,check=True,capture_output=True)
        assert source.read_bytes()==packaged, module['file']+' replay differs'
print('Pinned MIT modules, hashes and replayable patches match.')
