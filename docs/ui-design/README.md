# 设计稿源文件

由设计工具导出的 76 张画板（`*.dc.html`）、`canvas.json`（画板标题/尺寸/位置）、`BOARD_MAP.json`（画板 → 组 → UI 任务）。
`.dc.html` 是静态 HTML + 内联样式，px 即 dp/sp；`{{...}}` 与 `renderVals()` 里的是示例数据，不是需求。
页面引用的 Google Fonts（思源黑体/宋体）不使用，见 `docs/UI_REPLACEMENT_PRD.md` 4.2。只读，不要修改。
