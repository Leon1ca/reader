#!/usr/bin/env python3
"""Deterministic direct-dependency notices plus the full bundled font/vendor licenses."""
from pathlib import Path
import argparse

parser = argparse.ArgumentParser()
parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
args = parser.parse_args()
root = args.root
source = (root / 'docs/DEPENDENCIES.md').read_text()
rows = [line for line in source.splitlines() if line.startswith('| ') and not line.startswith('| ---')]
parts = ['本地阅读器 · 第三方声明', '运行时 AndroidX / Kotlin：Apache License 2.0。Android 系统 WebView 与 SQLite 由设备提供。', '\n'.join(rows),
         '本清单标明直接依赖及打包字体、MIT 引擎。构建与测试专用依赖不会作为应用功能。']
for path in ('fonts/Roboto-OFL.txt', 'fonts/Bitter-OFL.txt', 'fonts/LXGW-OFL.txt', 'reader/vendor/foliate/LICENSE'):
    parts.append(path + '\n' + (root / 'app/src/main/assets' / path).read_text())
for name in ('jsoup-LICENSE.txt', 'Apache-2.0.txt'):
    parts.append(name + '\n' + (root / 'app/src/main/assets/notices' / name).read_text())

output = root / 'app/src/main/assets/licenses.txt'
data = '\n'.join(line.rstrip() for line in '\n\n'.join(parts).splitlines()).rstrip() + '\n'
if not output.exists() or output.read_text() != data:
    output.write_text(data)
