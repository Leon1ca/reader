#!/usr/bin/env python3
"""Drive an actual installed Release through adb; no app or instrumentation classes."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--title', required=True, help='Exact owned QA fixture title')
    parser.add_argument('--kind', choices=('reflowable', 'fixed'), default='reflowable')
    parser.add_argument('--apk', type=Path, help='Optional local APK; SHA must match installed package')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--adb', default=shutil.which('adb'))
    parser.add_argument('--enforce', action='store_true')
    parser.add_argument('--samples', type=int, default=20, help='20 for acceptance; fewer only verify the driver')
    args = parser.parse_args()
    if not 1 <= args.samples <= 100:
        parser.error('--samples must be 1..100')
    if args.enforce and args.samples != 20:
        parser.error('--enforce requires exactly 20 measured samples')
    if not re.fullmatch(r'QA-[A-Za-z0-9._-]+', args.title):
        parser.error('Only exact ASCII owned QA fixture titles are accepted')
    if not args.adb:
        props = Path(__file__).resolve().parents[2] / 'local.properties'
        if props.exists():
            match = re.search(r'^sdk.dir=(.+)$', props.read_text(), re.M)
            if match:
                args.adb = str(Path(match[1].replace('\\:', ':')) / 'platform-tools/adb')
    if not args.adb or not Path(args.adb).exists():
        parser.error('Pass --adb /path/to/platform-tools/adb')
    package = 'com.localreader.app'
    dump_path = '/data/local/tmp/reader-release-qa-' + uuid.uuid4().hex + '.xml'
    report = {'scope': 'Actual installed Release, native accepted OPEN/PAGE endpoints',
              'ownedFixtureTitle': args.title, 'kind': args.kind, 'buildVariant': 'release',
              'firstOpenScope': 'First open in this runner; process, source, and font caches may already be warm. This is not a cold-start measurement.',
              'warmupCount': 1, 'requiredSamples': args.samples, 'measurementPurpose': 'acceptance' if args.samples == 20 else 'driver smoke only; not performance evidence'}
    opens, pages = [], []
    original_filter = None
    query_changed = False
    failure = None
    original_stay_awake = None

    def adb(*parts, timeout=30):
        result = subprocess.run([args.adb, '-s', args.serial, *map(str, parts)],
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        if result.returncode:
            raise RuntimeError('adb failed: ' + result.stderr.decode(errors='replace').strip()[:300])
        return result.stdout.decode(errors='replace')

    def shell(command, timeout=30):
        return adb('shell', command, timeout=timeout)

    def hierarchy():
        for attempt in range(3):
            try:
                shell('uiautomator dump --compressed ' + shlex.quote(dump_path), timeout=20)
                return ET.fromstring(adb('exec-out', 'cat', dump_path))
            except (RuntimeError, ET.ParseError, subprocess.TimeoutExpired):
                if attempt == 2:
                    raise
                time.sleep(.15)

    def bounds(node):
        values = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
        return tuple(values) if len(values) == 4 else (0, 0, 0, 0)

    def center(node):
        left, top, right, bottom = bounds(node)
        if right <= left or bottom <= top:
            raise RuntimeError('Visible node has empty bounds')
        return (left + right) // 2, (top + bottom) // 2

    def tap(node):
        x, y = center(node)
        shell(f'input tap {x} {y}')

    def clickable(node, root):
        parents = {child: parent for parent in root.iter() for child in parent}
        while node is not None and node.get('clickable') != 'true':
            node = parents.get(node)
        return node

    def by_label(root, label):
        return next((n for n in root.iter('node') if n.get('text') == label or n.get('content-desc') == label), None)

    def scroll(root, forward):
        nodes = [n for n in root.iter('node') if n.get('scrollable') == 'true']
        if not nodes:
            return False
        # The largest vertical surface is the shelf grid; never pick Continue's horizontal rail.
        node = max(nodes, key=lambda n: bounds(n)[3] - bounds(n)[1])
        left, top, right, bottom = bounds(node)
        x = (left + right) // 2
        low, high = top + (bottom - top) * 3 // 4, top + (bottom - top) // 4
        start, end = (low, high) if forward else (high, low)
        shell(f'input swipe {x} {start} {x} {end} 180')
        return True

    def shelf_visible(root):
        return any('主导航' in n.get('content-desc', '') for n in root.iter('node')) or (by_label(root, '书架') is not None and any(n.get('class', '').endswith('EditText') for n in root.iter('node')))

    def back_to_shelf():
        backs = 0
        for attempt in range(15):
            root = hierarchy()
            if shelf_visible(root):
                return root
            if any('PasswordTextView' in n.get('class', '') for n in root.iter('node')):
                raise RuntimeError('Device requires user unlock; no credential input was attempted')
            labels = [n for n in root.iter('node') if n.get('text') or n.get('content-desc')]
            # Blank startup/transition frames are not evidence that Back should be sent.
            if labels and backs < 3:
                shell('input keyevent 4')
                backs += 1
            time.sleep(.5)
        raise RuntimeError('Back did not reach the shelf; stopped before sending further input')

    def set_query(value):
        for _ in range(12):
            root = hierarchy()
            node = next((n for n in root.iter('node') if n.get('class', '').endswith('EditText')), None)
            if node is not None:
                tap(node)
                previous = node.get('text', '')[:500]
                shell('input keyevent ' + ' '.join(['123'] + ['67'] * len(previous)))
                if value:
                    for character in value:
                        shell('input text ' + shlex.quote(character))
                        time.sleep(.04)
                ime = shell('dumpsys input_method')
                if re.search(r'(mInputShown|isInputViewShown|mIsInputViewShown)=true', ime):
                    shell('input keyevent 4')  # Only dismiss an actually shown IME.
                time.sleep(.15)
                return
            if not scroll(root, False):
                break
        raise RuntimeError('Shelf search field was not accessible')

    def choose_filter(label):
        for _ in range(8):
            root = hierarchy()
            node = by_label(root, label)
            if node is not None:
                action = clickable(node, root)
                tap(action if action is not None else node)
                time.sleep(.15)
                return
            if not scroll(root, True):
                break
        raise RuntimeError('Shelf format filter was not accessible')

    def click_owned_title():
        for _ in range(8):
            root = hierarchy()
            node = next((n for n in root.iter('node') if n.get('text') == args.title or
                         args.title in n.get('content-desc', '')), None)
            if node is not None:
                action = clickable(node, root)
                if '更多' in node.get('content-desc', ''):
                    parents = {child: parent for parent in root.iter() for child in parent}
                    action = clickable(parents.get(action), root) if action is not None else None
                if action is not None:
                    tap(action)
                    return
            if not scroll(root, True):
                break
        raise RuntimeError('Exact owned QA title was not visible; no other book was opened')

    def accepted(kind):
        lines = shell(f'logcat -d --pid {pid} -s ReaderTiming:I', timeout=15).splitlines()
        result = []
        for line in lines:
            if 'end id=' not in line or f'kind={kind} ' not in line or 'outcome=ACCEPTED ' not in line:
                continue
            if kind == 'OPEN' and 'targetReached=true' not in line:
                continue
            operation = re.search(r'end id=(\d+)', line)
            duration = re.search(r'durationNanos=(\d+)', line)
            if operation and duration:
                result.append((int(operation.group(1)), int(duration.group(1)) / 1e6))
        return result

    def await_accepted(kind, after):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            values = [item for item in accepted(kind) if item[0] > after]
            if values:
                return values[-1]
            time.sleep(.12)
        raise RuntimeError(f'No accepted native {kind} after {after}; check owned-book paginated mode and renderer errors')

    def summary(values, limit):
        data = {'sampleCount': len(values), 'samplesMs': values, 'limitMs': limit}
        if len(values) == args.samples:
            ordered = sorted(values)
            data.update(medianMs=(ordered[(len(ordered) - 1) // 2] + ordered[len(ordered) // 2]) / 2,
                        p95Ms=ordered[math.ceil(len(ordered) * .95) - 1])
            if args.samples == 20:
                data['passed'] = data['p95Ms'] <= limit
            else:
                data['acceptanceEligible'] = False
        return data

    try:
        original_stay_awake = shell('settings get global stay_on_while_plugged_in').strip()
        shell('settings put global stay_on_while_plugged_in 3')
        report['sdk'] = shell('getprop ro.build.version.sdk').strip()
        report['device'] = shell('getprop ro.product.model').strip()
        report['fingerprint'] = shell('getprop ro.build.fingerprint').strip()
        info = shell('dumpsys package ' + package)
        if re.search(r'\bDEBUGGABLE\b', info):
            raise RuntimeError('Installed target is debuggable; this runner requires actual Release')
        path = next(line.split(':', 1)[1] for line in shell('pm path ' + package).splitlines() if line.startswith('package:') and line.endswith('/base.apk'))
        report['apkSha256'] = shell('sha256sum ' + shlex.quote(path)).split()[0]
        if args.apk:
            digest = hashlib.sha256()
            with args.apk.open('rb') as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                    digest.update(chunk)
            local = digest.hexdigest()
            if local != report['apkSha256']:
                raise RuntimeError('Local APK SHA does not match the installed Release')
        report['webView'] = next((line.strip() for line in shell('dumpsys webviewupdate').splitlines() if 'Current WebView package' in line), 'unavailable')
        component = shell('cmd package resolve-activity --brief -c android.intent.category.LAUNCHER ' + package).strip().splitlines()[-1]
        if '/' not in component:
            raise RuntimeError('Reader launcher activity was not found')
        shell('input keyevent 224')
        shell('wm dismiss-keyguard')
        shell('am start -n ' + shlex.quote(component))
        time.sleep(1)
        back_to_shelf()
        pid = int(shell('pidof -s ' + package).strip())
        report['pid'] = pid
        root = hierarchy()
        parents = {child: parent for parent in root.iter() for child in parent}
        for label in ('全部', 'EPUB', 'TXT'):
            node = by_label(root, label)
            for _ in range(3):
                if node is None:
                    break
                if node.get('selected') == 'true' or node.get('checked') == 'true':
                    original_filter = label
                    break
                node = parents.get(node)
            if original_filter:
                break
        report['originalFormatFilter'] = original_filter
        choose_filter('全部')
        query_changed = True
        set_query(args.title[-12:] if re.fullmatch(r'QA-[0-9a-f-]{36}-[0-9]{4}', args.title) else (args.title[40:] if re.match(r'QA-[0-9a-f-]{36}-', args.title) else args.title))
        last_open = max((item[0] for item in accepted('OPEN')), default=0)
        for index in range(args.samples + 1):
            click_owned_title()
            last_open, open_ms = await_accepted('OPEN', last_open)
            if index == 0:
                report['firstOpenMs'] = open_ms
            last_page = max((item[0] for item in accepted('PAGE')), default=0)
            screen = hierarchy()
            rectangles = [bounds(n) for n in screen.iter('node') if n.get('package') == package]
            width = max(r[2] for r in rectangles)
            height = max(r[3] for r in rectangles)
            shell(f'input tap {round(width * .85)} {round(height * .5)}')
            _, page_ms = await_accepted('PAGE', last_page)
            if index:
                opens.append(open_ms)
                pages.append(page_ms)
            else:
                report['firstOpenMs'], report['firstPageMs'] = open_ms, page_ms
            print(f'{args.kind} sample={index}/{args.samples} OPEN={open_ms:.3f}ms PAGE={page_ms:.3f}ms', file=sys.stderr, flush=True)
            back_to_shelf()
    except Exception as error:
        failure = str(error)
        report['error'] = failure
    finally:
        if original_stay_awake is not None:
            try:
                shell('settings delete global stay_on_while_plugged_in' if original_stay_awake == 'null' else 'settings put global stay_on_while_plugged_in ' + shlex.quote(original_stay_awake))
                report['stayAwakeRestored'] = True
            except Exception as error:
                report['stayAwakeRestored'] = False
                report['stayAwakeRestoreError'] = str(error)
        if query_changed:
            try:
                back_to_shelf()
                set_query('')
                if original_filter:
                    choose_filter(original_filter)
                report['shelfRestored'] = True
            except Exception as error:
                report['shelfRestored'] = False
                report['cleanupError'] = str(error)
        try:
            shell('rm -f ' + shlex.quote(dump_path))
        except Exception:
            pass
        report['open'] = summary(opens, 800)
        report['page'] = summary(pages, 250 if args.kind == 'fixed' else 200)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2))
        print(str(args.output), flush=True)
    if failure or len(opens) != args.samples or len(pages) != args.samples:
        return 1
    if args.enforce and (not report['open']['passed'] or not report['page']['passed']):
        return 2
    return 0


if __name__ == '__main__':
    sys.exit(main())
