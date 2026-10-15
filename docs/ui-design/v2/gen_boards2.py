#!/usr/bin/env python3
"""Reader settings boards, round 4: two separate bottom sheets that follow UiSpec.
界面设置 (toolbar 阅读): 亮度 / 界面风格 / 背景颜色 / 翻页方式 / 翻页效果.
排版设置 (toolbar 设置): 字号 / 字体 / 排版样式 / 行间距 / 段间距 / 页边距 / TXT 编码.
Styling follows docs/ui-design/UiSpec + Settings: grouped cards r16, rows, 12/300 captions,
segmented 40 r12/9, sheet r24 with 36x4 handle, max weight 500, primary blue.
Usage: python3 gen_boards4.py <out dir>"""
import os, sys

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)

LIGHT = dict(page="#F3ECDF", body="#2B2620", header="#5A5040", panel="#FBF8F2", card="#FFFFFF", cardLine="rgba(43,38,32,0.07)",
             input="#F0EADF", text="#2B2620", control="#3B342A", secondary="#6B604F", primary="#1F5FCC", primarySoft="#E9F0FC",
             chip="#FFFFFF", chipShadow="0 1px 2px rgba(43,38,32,0.12)", divider="#EFE9DE", handle="#DDD4C4",
             track="#E2D9C8", scrim="rgba(43,38,32,0.16)", sheetShadow="0 -8px 30px rgba(43,38,32,0.12)", ring="rgba(43,38,32,0.18)")
DARK = dict(page="#1A1917", body="#B9B2A6", header="#8E877B", panel="#272624", card="#2F2E2B", cardLine="rgba(255,255,255,0.05)",
            input="#3A3936", text="#D6D0C5", control="#C9C2B6", secondary="#8E877B", primary="#9CBFFF", primarySoft="#253751",
            chip="#4A4844", chipShadow="0 1px 2px rgba(0,0,0,0.35)", divider="#393835", handle="#4A4844",
            track="#45433F", scrim="rgba(0,0,0,0.30)", sheetShadow="0 -8px 30px rgba(0,0,0,0.4)", ring="rgba(255,255,255,0.18)")

THEMES = [("米黄", "#F3ECDF"), ("白色", "#FFFFFF"), ("浅灰", "#E6E6E3"), ("豆绿", "#DCE8D6"), ("深色", "#1E1D1B")]
TEXT = ["我冒了严寒，回到相隔二千余里，别了二十余年的故乡去。",
        "时候既然是深冬；渐近故乡时，天气又阴晦了，冷风吹进船舱中，呜呜的响，从篷隙向外一望，苍黄的天底下，远近横着几个萧索的荒村，没有一些活气。我的心禁不住悲凉起来了。",
        "阿！这不是我二十年来时时记得的故乡？"]
SANS = "'Noto Sans SC', sans-serif"
SERIF = "'Noto Serif SC', serif"
KAI = "'Kaiti SC', 'STKaiti', 'KaiTi', serif"


def svg(body, size=18, sw=1.6, vb=24):
    return (f'<svg width="{size}" height="{size}" viewBox="0 0 {vb} {vb}" fill="none" stroke="currentColor" stroke-width="{sw}" '
            f'stroke-linecap="round" stroke-linejoin="round">{body}</svg>')


I_X = '<svg width="12" height="12" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><path d="M2 2l8 8M10 2L2 10"></path></svg>'
I_CHEV = svg('<path d="M9.5 6l6 6-6 6"></path>', 14, 1.8)
I_SUN_S = svg('<circle cx="12" cy="12" r="4"></circle><path d="M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2"></path>', 14, 2)
I_SUN_L = svg('<circle cx="12" cy="12" r="4.5"></circle><path d="M12 1.5v2.5M12 20v2.5M1.5 12H4M20 12h2.5M4.6 4.6l1.8 1.8M17.6 17.6l1.8 1.8M4.6 19.4l1.8-1.8M17.6 6.4l1.8-1.8"></path>', 18, 1.7)
I_CURL = svg('<path d="M5 3.5h10l4 4V19a1.5 1.5 0 0 1-1.5 1.5h-12A1.5 1.5 0 0 1 4 19V5a1.5 1.5 0 0 1 1-1.5z"></path><path d="M19 13c-3 0-6 2.5-6.5 7.5"></path>', 16, 1.6)
I_COVER = svg('<rect x="8" y="3.5" width="12" height="17" rx="1.5"></rect><path d="M8 6.5H5.5A1.5 1.5 0 0 0 4 8v11a1.5 1.5 0 0 0 1.5 1.5H8"></path>', 16, 1.6)
I_SLIDE = svg('<rect x="3" y="4" width="18" height="16" rx="2"></rect><path d="M12 4v16M15 12h3M16.5 10.5L18 12l-1.5 1.5"></path>', 16, 1.6)
I_NONE = svg('<rect x="6" y="3.5" width="12" height="17" rx="1.5"></rect>', 16, 1.6)
I_LH = lambda gap: svg(f'<path d="M3 {12 - 1.5 * gap}h14M3 {12 - .5 * gap}h14M3 {12 + .5 * gap}h14M3 {12 + 1.5 * gap}h9"></path>', 20, 1.4, 20)
I_MG = lambda inset: svg(f'<rect x="2.5" y="3" width="15" height="14" rx="2"></rect><path d="M{inset} 7h{20 - 2 * inset}M{inset} 10h{20 - 2 * inset}M{inset} 13h{20 - 2 * inset}"></path>', 20, 1.4, 20)


def seg(c, items, selected, aria, cols=None, height=40, faded=False):
    cells = []
    for i, it in enumerate(items):
        on = i == selected
        st = (f'background: {c["chip"]}; box-shadow: {c["chipShadow"]}; color: {c["primary"]}; font-weight: 500' if on
              else f'background: transparent; color: {c["control"]}; font-weight: 400')
        cells.append(f'<button type="button" aria-pressed="{str(on).lower()}" style="border: none; border-radius: 9px; {st}; font: inherit; font-size: 13px; '
                     f'display: flex; align-items: center; justify-content: center; gap: 4px; padding: 0; min-width: 0; white-space: nowrap">{it}</button>')
    return (f'<div role="group" aria-label="{aria}" style="flex: 1; min-width: 0; height: {height}px; box-sizing: border-box; padding: 3px; border-radius: 12px; '
            f'background: {c["input"]}; display: grid; grid-template-columns: repeat({cols or len(items)}, minmax(0, 1fr)); gap: 3px{"; opacity: .45" if faded else ""}">{"".join(cells)}</div>')


def slider(c, frac, aria):
    return (f'<div role="slider" aria-label="{aria}" style="flex: 1; min-width: 0; height: 40px; box-sizing: border-box; padding: 0 12px; border-radius: 12px; background: {c["input"]}; '
            f'display: flex; align-items: center; gap: 12px; color: {c["secondary"]}">{I_SUN_S}'
            f'<div style="flex: 1; height: 4px; border-radius: 2px; background: {c["track"]}; position: relative"><div style="width: {frac}%; height: 100%; border-radius: 2px; background: {c["primary"]}"></div>'
            f'<div style="position: absolute; left: {frac}%; top: 50%; width: 20px; height: 20px; margin: -10px 0 0 -10px; border-radius: 50%; background: #FFFFFF; box-shadow: 0 1px 4px rgba(43,38,32,0.25)"></div></div>{I_SUN_L}</div>')


def chip(c, text, on):
    st = f'background: {c["primarySoft"]}; color: {c["primary"]}; font-weight: 500' if on else f'background: {c["input"]}; color: {c["control"]}; font-weight: 400'
    return f'<button type="button" aria-pressed="{str(on).lower()}" style="flex: none; height: 30px; padding: 0 12px; border: none; border-radius: 15px; {st}; font: inherit; font-size: 12px">{text}</button>'


def tag(c, text="开发中"):
    return f'<span style="margin-left: 6px; padding: 1px 6px; border-radius: 6px; background: {c["input"]}; color: {c["secondary"]}; font-size: 10px; font-weight: 400">{text}</span>'


def row(c, label, control, last=False, faded=False, height=56, label_extra=""):
    line = "" if last else f"border-bottom: 0.5px solid {c['divider']};"
    return (f'<div style="min-height: {height}px; padding: 8px 12px 8px 16px; box-sizing: border-box; display: flex; align-items: center; gap: 12px; {line}">'
            f'<span style="width: 60px; flex: none; font-size: 15px; font-weight: 400; color: {c["text"]}{"; opacity: .45" if faded else ""}; white-space: nowrap; display: flex; align-items: center">{label}{label_extra}</span>{control}</div>')


def nav_row(c, label, value, last=True):
    line = "" if last else f"border-bottom: 0.5px solid {c['divider']};"
    return (f'<div style="height: 50px; padding: 0 16px; display: flex; align-items: center; justify-content: space-between; {line}">'
            f'<span style="font-size: 15px; font-weight: 400; color: {c["text"]}">{label}</span>'
            f'<span style="font-size: 14px; font-weight: 300; color: {c["secondary"]}; display: flex; align-items: center; gap: 2px">{value}{I_CHEV}</span></div>')


def group(c, caption, rows, note=None):
    n = ""
    return (f'<div style="display: flex; flex-direction: column; gap: 6px"><span style="font-size: 12px; font-weight: 300; color: {c["secondary"]}; padding: 0 4px">{caption}</span>'
            f'<div style="border-radius: 16px; background: {c["card"]}; box-shadow: 0 0 0 0.5px {c["cardLine"]}">{"".join(rows)}</div>{n}</div>')


def swatches(c, selected):
    out = []
    for i, (name, bg) in enumerate(THEMES):
        on = i == selected
        ring = f'box-shadow: 0 0 0 2px {c["card"]}, 0 0 0 3.5px {c["primary"]}' if on else f'box-shadow: inset 0 0 0 0.5px {c["ring"]}'
        fg = "#B9B2A6" if name == "深色" else "#2B2620"
        out.append(f'<button type="button" aria-label="{name}{"（当前）" if on else ""}" style="border: none; padding: 0; background: transparent; display: flex; flex-direction: column; align-items: center; gap: 6px; font: inherit">'
                   f'<span style="width: 40px; height: 40px; border-radius: 20px; background: {bg}; {ring}; display: flex; align-items: center; justify-content: center; '
                   f'font-family: {SERIF}; font-size: 14px; color: {fg}">文</span>'
                   f'<span style="font-size: 11px; font-weight: {500 if on else 300}; color: {c["primary"] if on else c["secondary"]}">{name}</span></button>')
    return f'<div role="group" aria-label="背景颜色" style="padding: 14px 12px 12px; display: grid; grid-template-columns: repeat(5, minmax(0, 1fr))">{"".join(out)}</div>'


def stack(icon, label):
    return f'<span style="display: flex; flex-direction: column; align-items: center; gap: 2px">{icon}<span style="font-size: 11px; line-height: 1">{label}</span></span>'


def sheet(c, title, body, aria, footer=None):
    foot = (f'<div style="display: flex; justify-content: space-between; align-items: center; font-size: 12px; font-weight: 300; color: {c["secondary"]}; padding: 0 4px">{footer}</div>' if footer else "")
    return (f'<div style="position: absolute; inset: 0; background: {c["scrim"]}"></div>'
            f'<section aria-label="{aria}" style="position: absolute; left: 0; right: 0; bottom: 0; padding: 10px 16px 28px; border-radius: 24px 24px 0 0; background: {c["panel"]}; '
            f'box-shadow: {c["sheetShadow"]}; display: flex; flex-direction: column; gap: 14px">'
            f'<div style="width: 36px; height: 4px; border-radius: 2px; background: {c["handle"]}; align-self: center"></div>'
            f'<div style="display: flex; align-items: center; justify-content: space-between; padding: 0 4px; margin-top: -2px">'
            f'<h2 style="margin: 0; font-size: 17px; font-weight: 500; color: {c["text"]}">{title}</h2>'
            f'<button type="button" aria-label="关闭" style="width: 44px; height: 44px; margin: -7px -7px -7px 0; border: none; background: transparent; padding: 0; display: flex; align-items: center; justify-content: center">'
            f'<span style="width: 30px; height: 30px; border-radius: 15px; background: {c["input"]}; color: {c["control"]}; display: flex; align-items: center; justify-content: center">{I_X}</span></button></div>'
            f'{body}{foot}</section>')


def frame(c, bg, inner):
    return f'<div style="width: 390px; height: 844px; position: relative; overflow: hidden; background-color: {bg}; font-family: {SANS}; color: {c["text"]}">{inner}</div>'


def backdrop(c, bg, text=True, header="本章剩余 4 页"):
    paras = "" if not text else "".join(
        f'<p style="margin: 0 0 10px; text-indent: 2em; font-size: 18px; line-height: 1.75; text-align: justify">{t}</p>' for t in TEXT)
    return (f'<div style="position: absolute; top: 0; left: 0; right: 0; height: 76px; padding: 26px 64px 0; box-sizing: border-box; display: flex; align-items: center; justify-content: center">'
            f'<span style="font-size: 13px; letter-spacing: 1px; color: {c["header"]}">{header}</span></div>'
            f'<div style="position: absolute; top: 76px; left: 26px; right: 26px; font-family: {SERIF}; color: {c["body"]}">{paras}</div>')


def display_sheet(c, style_idx=0, theme=0, fixed=False):
    bright = row(c, "亮度", slider(c, 58, "亮度") + chip(c, "跟随系统", False))
    style = row(c, "界面风格", seg(c, ["跟随系统", "浅色", "深色"], style_idx, "界面风格"), last=True)
    groups = [group(c, "显示", [bright, style], "深色只临时调暗阅读界面，不改你选的背景颜色"),
              group(c, "背景颜色", [swatches(c, theme)])]
    if not fixed:
        flow = row(c, "翻页方式", seg(c, ["左右翻页", "上下滚动"], 0, "翻页方式"))
        effect = row(c, "翻页效果", seg(c, [stack(I_CURL, "仿真"), stack(I_COVER, "覆盖"), stack(I_SLIDE, "滑动"), stack(I_NONE, "无")], 1, "翻页效果", height=48), last=True)
        groups.append(group(c, "翻页", [flow, effect], "翻页效果只用于左右翻页的文字书"))
    link = "缩放与展开" if fixed else "排版设置"
    return sheet(c, "界面设置", "".join(groups), "界面设置",
                 f'<span></span><span style="color: {c["primary"]}; font-weight: 400; height: 32px; display: flex; align-items: center">{link} ›</span>')


def typography_sheet(c):
    size = (f'<div role="group" aria-label="字号" style="flex: 1; min-width: 0; height: 40px; box-sizing: border-box; padding: 3px; border-radius: 12px; background: {c["input"]}; '
            f'display: grid; grid-template-columns: 1fr 1.2fr 1fr; gap: 3px">'
            f'<button type="button" aria-label="减小字号" style="border: none; border-radius: 9px; background: transparent; font-family: {SERIF}; font-size: 13px; color: {c["control"]}">A</button>'
            f'<span style="display: flex; align-items: center; justify-content: center; font-size: 14px; font-weight: 500; color: {c["text"]}; font-variant-numeric: tabular-nums">18</span>'
            f'<button type="button" aria-label="增大字号" style="border: none; border-radius: 9px; background: transparent; font-family: {SERIF}; font-size: 19px; color: {c["control"]}">A</button></div>')
    font = seg(c, [f'<span style="font-family: {SERIF}">宋体</span>', "黑体", f'<span style="font-family: {KAI}">楷体</span>', "更多 ›"], 0, "字体")
    text = group(c, "文字", [row(c, "字号", size), row(c, "字体", font, last=True)], "“更多”用于导入字体，0.6 开放，当前显示开发中")
    mode = row(c, "样式", seg(c, ["原书样式", "自定义"], 1, "排版样式"))
    lh = row(c, "行间距", seg(c, [I_LH(2.5), I_LH(3.2), I_LH(4)], 1, "行间距"))
    ps = row(c, "段间距", seg(c, ["紧凑", "标准", "宽松"], 1, "段间距", faded=True), faded=True, label_extra="")
    mg = row(c, "页边距", seg(c, [I_MG(4.5), I_MG(6.5), I_MG(8)], 1, "页边距"), last=True)
    layout = group(c, "排版", [mode, lh, ps, mg], "“样式”只在 EPUB 书中显示；段间距 0.6 开放，当前置灰")
    txt = group(c, "TXT", [nav_row(c, "编码与分章", "GB18030")])
    return sheet(c, "排版设置", text + layout + txt, "排版设置",
                 f'<span></span><span style="color: {c["primary"]}; font-weight: 400; height: 32px; display: flex; align-items: center">界面设置 ›</span>')


def zoom_sheet(c):
    ticks = "".join(f'<span>{i}×</span>' for i in range(1, 6))
    zoom = (f'<div style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px">'
            f'<div style="height: 40px; box-sizing: border-box; padding: 0 14px; border-radius: 12px; background: {c["input"]}; display: flex; align-items: center">'
            f'<div style="flex: 1; height: 4px; border-radius: 2px; background: {c["track"]}; position: relative"><div style="position: absolute; left: 0; top: 50%; width: 26px; height: 26px; margin: -13px 0 0 -6px; border-radius: 50%; background: #FFFFFF; box-shadow: 0 1px 4px rgba(43,38,32,0.25); display: flex; align-items: center; justify-content: center; font-size: 10px; font-weight: 500; color: {c["text"]}">1×</div></div></div>'
            f'<div style="display: flex; justify-content: space-between; padding: 0 10px; font-size: 10px; font-weight: 300; color: {c["secondary"]}">{ticks}</div></div>')
    g1 = group(c, "缩放", [row(c, "适配", seg(c, ["适合整页", "适合宽度"], 0, "适配")), row(c, "倍数", zoom, last=True, height=72)])
    g2 = group(c, "展开", [row(c, "页面", seg(c, ["自动双页", "单页"], 0, "页面"), last=True)], "双指捏合缩放，双击还原；横屏或宽屏时自动显示双页")
    return sheet(c, "缩放与展开", g1 + g2, "缩放与展开",
                 f'<span></span><span style="color: {c["primary"]}; font-weight: 400; height: 32px; display: flex; align-items: center">界面设置 ›</span>')


L, D = LIGHT, DARK
FX = "#E9E6E0"
SPREAD = ('<div style="position: absolute; top: 96px; left: 0; right: 0; display: flex; justify-content: center; gap: 2px">' + "".join(
    f'<div style="width: 170px; height: 240px; background: #FFFFFF; box-shadow: 0 4px 14px rgba(0,0,0,0.10); padding: 18px 16px; box-sizing: border-box; display: flex; flex-direction: column; gap: 8px">'
    f'<div style="height: 100px; border-radius: 3px; background: {col}"></div>' + "".join(f'<div style="height: 5px; border-radius: 2px; width: {w}%; background: #DAD5CC"></div>' for w in (100, 92, 100, 70, 96, 84))
    + '</div>' for col in ("#C9D6CC", "#E8D3B9")) + '</div>')

BOARDS = {
    "ReaderSettingsV2": ("阅读器 · 界面设置", "工具栏“阅读”打开。亮度、界面风格一组，背景颜色一组，翻页方式和翻页效果一组。",
                         frame(L, L["page"], backdrop(L, L["page"]) + display_sheet(L))),
    "ReaderTypographyV2": ("阅读器 · 排版设置", "工具栏“设置”打开。文字（字号、字体）、排版（样式、行间距、段间距、页边距）、TXT 编码三组。",
                           frame(L, L["page"], backdrop(L, L["page"]) + typography_sheet(L))),
    "ReaderSettingsV2Dark": ("阅读器 · 界面设置（深色）", "界面风格为深色时，面板和卡片换成深色。",
                             frame(D, D["page"], backdrop(D, D["page"]) + display_sheet(D, style_idx=2))),
    "ReaderTypographyV2Dark": ("阅读器 · 排版设置（深色）", "深色下的排版设置。",
                               frame(D, D["page"], backdrop(D, D["page"]) + typography_sheet(D))),
    "ReaderSettingsV2Fixed": ("阅读器 · 界面设置（固定版式）", "固定版式书没有翻页分组，底部链接换成“缩放与展开”。",
                              frame(L, FX, backdrop(L, FX, text=False, header="第 12–13 页 / 64") + SPREAD + display_sheet(L, fixed=True))),
    "ReaderZoomV2Fixed": ("阅读器 · 缩放与展开（固定版式）", "固定版式书的工具栏“设置”打开这里，只对本书生效。",
                          frame(L, FX, backdrop(L, FX, text=False, header="第 12–13 页 / 64") + SPREAD + zoom_sheet(L))),
}

for name, (title, _, body) in BOARDS.items():
    with open(os.path.join(OUT, f"{name}.dc.html"), "w", encoding="utf-8") as f:
        f.write(f'''<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>{title}</title>
<script src="../support.js"></script>
</head>
<body>
<x-dc>
<helmet>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Noto+Sans+SC:wght@300;400;500;700&amp;family=Noto+Serif+SC:wght@400;500;600;700&amp;display=swap">
<style>
body{{margin:0;-webkit-font-smoothing:antialiased}}
</style>
</helmet>
{body}
</x-dc>
<script type="text/x-dc" data-dc-script data-props='{{"$preview":{{"width":390,"height":844}}}}'>
class Component extends DCLogic {{
  renderVals() {{ return {{}}; }}
}}
</script>
</body>
</html>
''')

with open(os.path.join(OUT, "_frames.html"), "w", encoding="utf-8") as f:
    for name, (title, cap, body) in BOARDS.items():
        f.write(f'<!--FRAME {name}|{title}|{cap}-->\n{body}\n')
print("ok")
