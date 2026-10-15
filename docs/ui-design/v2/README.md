# 阅读器面板 V2：界面设置与排版设置

两个独立的底部面板（不用标签页、不分层），风格按 `docs/ui-design/UiSpec.dc.html` 和 `Settings.dc.html`：

1. **界面设置**（工具栏“阅读”）`ReaderSettingsV2.dc.html`
   - 显示：亮度（滑杆 + 跟随系统）、界面风格（跟随系统 / 浅色 / 深色，即现有夜间模式）
   - 背景颜色：米黄、白色、浅灰、豆绿、深色
   - 翻页：翻页方式（左右翻页 / 上下滚动）、翻页效果（仿真 / 覆盖 / 滑动 / 无，仅左右翻页的文字书）
2. **排版设置**（工具栏“设置”）`ReaderTypographyV2.dc.html`
   - 文字：字号（12–36）、字体（宋体 / 黑体 / 楷体 / 更多，更多为 0.6）
   - 排版：样式（原书样式 / 自定义，仅 EPUB）、行间距、段间距（0.6，置灰）、页边距
   - TXT：编码与分章
3. 深色：`ReaderSettingsV2Dark.dc.html`、`ReaderTypographyV2Dark.dc.html`
4. 固定版式：`ReaderSettingsV2Fixed.dc.html`（无翻页分组）、`ReaderZoomV2Fixed.dc.html`（缩放与展开，只对本书生效）

面板内不放说明文字，底部只保留互相跳转的链接。

规格：底部弹层圆角 24、把手 36×4、标题 17/500；分组标题 12/300；卡片圆角 16、行高 56（导航行 50）、0.5 分隔线；分段控件高 40、圆角 12/9，选中白底蓝字；最重 500。
数据全部沿用现有 DisplayPreferences / ReaderRules，无新接口。生成脚本：`gen_boards2.py`。仓库副本：`docs/ui-design/v2/`。
