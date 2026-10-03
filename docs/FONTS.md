# 正文字体来源、许可与一致性核验

核验日期：2026-10-04。对应 Readest 0.12.10，官方 commit `8a56831af61841175b49cc2740bffcde57b470b0`。

本轮已独立从字体官方仓库/发布页取得五个字体文件，未从 Readest APK 复制任何字体或代码。实现角色已将它们放入 `app/src/main/assets/fonts/`；规划角色核对了五个打包文件与下载原件的 SHA-256 完全相同。机器可读完整清单为 [FONTS_MANIFEST.json](FONTS_MANIFEST.json)。

**已证实：来源、许可、版本、文件完整性，以及默认字重 400 下与本次取得的 Readest 公共字体分包的共有字形轮廓和 advance width 一致。尚未证实：手机 Readest 每个字形实际使用这些文件，或两应用的最终正文像素完全一致。**

## 1. 打包字体清单

Roboto/Bitter 固定 Google Fonts 官方仓库 commit：`9710da1eacb3be272583c3224dcb70f9da6eadbb`。源字体字节未修改，只把含轴名的文件名缩短。Google Fonts 当前 Roboto 是 OFL 分发，不要根据旧 Roboto 历史版本擅自附 Apache 许可证；本轮二进制内嵌 name table 和同目录官方 OFL 均核对为 OFL-1.1。

| 文件 | 字体内部版本 | 字节数 | SHA-256 |
| --- | --- | ---: | --- |
| Roboto-Regular-VF.ttf | 3.015; 2026 | 488584 | `d7598e12c5dbef095ff8272cfc55da0250bd07fbdecbac8a530b9b277872a134` |
| Roboto-Italic-VF.ttf | 3.015; 2026 | 530944 | `9725a847af6b460ffca162ae66d20dad48b01876137947180b42d7dcd7887182` |
| Bitter-Regular-VF.ttf | 3.021 | 328636 | `ef2b9a711fb02f1e5823b34da1b7450e0fc76793b7d733a8b41006e24916d4a7` |
| Bitter-Italic-VF.ttf | 3.021 | 317652 | `5e6e0af503171c9d7b4be7a22c16f474d7a638cf83a80051d825bcc58d664bc3` |
| LXGWWenKaiGBScreen.ttf | 1.520; June 18, 2025 | 24879158 | `7bad73877a13af4614bb6e43a99501a7e72b0ebf67b5757c92a67253d0003b09` |

固定下载链接：

- [Roboto regular 官方 TTF](https://raw.githubusercontent.com/google/fonts/9710da1eacb3be272583c3224dcb70f9da6eadbb/ofl/roboto/Roboto%5Bwdth%2Cwght%5D.ttf)
- [Roboto italic 官方 TTF](https://raw.githubusercontent.com/google/fonts/9710da1eacb3be272583c3224dcb70f9da6eadbb/ofl/roboto/Roboto-Italic%5Bwdth%2Cwght%5D.ttf)
- [Bitter regular 官方 TTF](https://raw.githubusercontent.com/google/fonts/9710da1eacb3be272583c3224dcb70f9da6eadbb/ofl/bitter/Bitter%5Bwght%5D.ttf)
- [Bitter italic 官方 TTF](https://raw.githubusercontent.com/google/fonts/9710da1eacb3be272583c3224dcb70f9da6eadbb/ofl/bitter/Bitter-Italic%5Bwght%5D.ttf)
- [LXGW GB Screen 官方 v1.520 TTF](https://github.com/lxgw/LxgwWenKai-Screen/releases/download/v1.520/LXGWWenKaiGBScreen.ttf)：SHA-256 同时与 GitHub 官方 release asset 的 digest 一致。[发布说明](https://github.com/lxgw/LxgwWenKai-Screen/releases/tag/v1.520)

## 2. 许可随字体一起打包

已核对并打包以下原始许可文本，不删版权声明：

- `Roboto-OFL.txt`：[固定官方来源](https://raw.githubusercontent.com/google/fonts/9710da1eacb3be272583c3224dcb70f9da6eadbb/ofl/roboto/OFL.txt)，版权为 Roboto Project Authors，OFL-1.1。
- `Bitter-OFL.txt`：[固定官方来源](https://raw.githubusercontent.com/google/fonts/9710da1eacb3be272583c3224dcb70f9da6eadbb/ofl/bitter/OFL.txt)，版权为 Bitter Project Authors，OFL-1.1，保留名称为 Bitter Pro。本轮未修改字体本身。
- `LXGW-OFL.txt`：[v1.520 对应固定官方来源](https://raw.githubusercontent.com/lxgw/LxgwWenKai-Screen/b2e2193c58b67ef499f257ca584946aeca73e9d3/OFL.txt)，版权包含 LXGW 与 Klee Project Authors，OFL-1.1。

OFL 允许字体随软件打包，需保留相应版权和许可；字体本身持续按 OFL 分发，不把本应用的许可套到字体文件上，不单独出售字体。若后续修改字体/子集化，应重新核对对应发行版本的名称与修改条件。本轮完整 TTF 字节均保持官方原件不变。

`lxgw-wenkai-screen-web@1.520.0` 的 package.json 标为 MIT，但字体文件的嵌入许可与来源为 OFL-1.1。不得只凭 npm 顶层 MIT 字段把字库认定为 MIT。本项目当前打包的是官方完整 TTF 与官方 OFL，避开这种元数据歧义。

Readest 应用源码的 AGPL 不因字体许可而变成 OFL 或 MIT；本轮项目 `readest.css` 是实现角色自行写的 `@font-face` 声明，不复制 Readest 的 style.ts。正文行为规格仍按 [READEST_PARITY.md](READEST_PARITY.md) 独立实现和验证。

## 3. 和 Readest 实际字体来源的绑定

固定版 Readest [fonts.ts](https://github.com/readest/readest/blob/8a56831af61841175b49cc2740bffcde57b470b0/apps/readest-app/src/styles/fonts.ts) 对 Roboto、Bitter 请求 Google Fonts CSS2 API，normal/italic 均请求 100–900 可变字重。API 返回内容会随日期、UA、上游版本变化；锁 Readest tag 并不会冻结远程字体字节。

本轮使用 Android Chrome 153 UA 在 2026-10-04 取得对应 API CSS 快照：Bitter URL 路径为 v42，内部版本 3.021；Roboto 内部版本 3.015;2026。这与此次固定官方仓库 TTF 内部版本一致。下载了全部 28 个 WOFF2 分包（Roboto 每种样式 9 个；Bitter 每种样式 5 个），并存下请求 CSS、每个分包的源 URL 与 SHA-256。**这仍不能证明手机现有 Readest 缓存的字体就是本日返回的这批文件。**

Readest 的 CJK 字体来源是 [cdnjs 固定 1.520.0 GB Screen CSS](https://cdnjs.cloudflare.com/ajax/libs/lxgw-wenkai-screen-web/1.520.0/lxgwwenkaigbscreen/result.css)。该 CSS 含 248 个 `unicode-range` 分包；注释里的字体版本为 1.520，常规字重 400。npm 对应包的 git commit 为 `65102e6a4a887152b196cfd800f4a52db3bf785a`，已验证 tarball 的 SHA-1 和 SHA-512 registry integrity，且其中 GB Screen CSS 与 cdnjs 返回 CSS **逐字节相同**。

字体选择顺序仍按 READEST_PARITY.md：手机一般无衬线正文首先 Roboto，然后 LXGW WenKai GB Screen 等 fallback；数字没有单独字体类别。TXT/剪藏文章有额外原始字体栈并经过 generic-family 重写，不能忽略 Arial 或系统前置候选。现有阅读器尚需实际字形检查。

## 4. 轮廓与度量核验结果

使用 fontTools 4.60.2 只读解析字体，对 WOFF2 与完整 TTF 的共有 cmap 码点逐个比较展开后的轮廓绘制命令和水平 advance width。Roboto/Bitter 取 `wght=400`，Roboto width 轴默认 100。结果见 [FONT_GLYPH_COMPARISON.json](FONT_GLYPH_COMPARISON.json)。

| 字体 | 网页分包数 | 独立 cmap 码点 | 比较次数（分包可能重复） | 轮廓/字宽差异 |
| --- | ---: | ---: | ---: | ---: |
| LXGW GB Screen | 248 | 46501 | 46509 | 0 |
| Roboto regular | 9 | 897 | 1190 | 0 |
| Roboto italic | 9 | 897 | 1190 | 0 |
| Bitter regular | 5 | 871 | 936 | 0 |
| Bitter italic | 5 | 871 | 936 | 0 |

比较不覆盖所有可变字重、hinting、OpenType shaping/kerning、脚本上下文或最终光栅化；这些需要浏览器与设备对照。它证明默认字形形状/advance 与当前公共分包一致，不能扩展成排版已达标。

### 完整字体比 Google 分包多出字符

完整 Roboto cmap 为 927 码点，比网页分包并集多 30；Bitter 977，比并集多 106。包括部分组合重音、空白或符号，可能让原本走 fallback 的字符改由本字体处理。详细码点与可用于 `@font-face unicode-range` 的实际网页覆盖并集见 [FONT_WEB_COVERAGE.json](FONT_WEB_COVERAGE.json)。

当前实现打包完整 TTF，并已为 Roboto/Bitter 的四个 `@font-face` 加入 FONT_WEB_COVERAGE.json 的精确有效 `unicode-range`，排除上述额外码点；规划角色已逐项核对声明与清单一致，并验证范围确实等于原始分包 cmap 与对应 Google CSS unicode-range 的交集并集。此项覆盖差异已处理；全可变字重、shaping/hinting 和实机一致性仍未验证。LXGW 完整 TTF 和其网页分包的码点并集均为 46501。

## 5. 实现注意事项与缩放证据

- variable fonts 的 `@font-face` 字重范围声明为 100–900，regular/italic 分开。Bitter 字体轴默认 100（内部家族名 Bitter Thin），使用时正文必须明确字重 400；不能因文件名 Regular 就省略权重控制。
- Roboto 的宽度轴默认为 100；不要默认启用窄体。LXGW 为固定常规 400，粗体/斜体的浏览器合成也要与 Readest 对照。
- 当前项目已将 `font-display` 对齐为 `swap`，实现角色说明在 `document.fonts.ready` 后恢复/报告位置；仍须设备测试验证字体加载后的重排行为，不以 fallback 首帧作成功证据。本地字体失败要报告，不悄悄给用户显示“已一致”。
- 手机记录：1200×2670、physical density 520、system font_scale 0.9，WebView `com.google.android.webview 153.0.8010.36`。Readest 未提供生产调试 socket，未连接系统浏览器的调试端口。
- 已检索固定 Readest Android 原生源代码，未找到 textZoom/fontScale 覆写；Cargo.lock 锁定 Wry 0.56.1，下载其原始 crate 并核对 lock SHA-256 `375becb4aded9913f736443cf88000c6311478db69814cc06070465e4cc44c98`，其 RustWebView Kotlin 也未设 textZoom。此证据支持保留平台默认缩放并实测；不应武断改成 100。
- Readest 自己的同 tag 历史诊断记录报告 Android 系统 font_scale 会通过 WebView text zoom 影响 CSS 字体；这是上游测试记录，不能当本手机实测值。默认 16×1.25=20 是 **CSS 声明值**，不是已经证明的本手机 computed font-size。应测项目默认 textZoom、computedStyle 和截图，再与 Readest 比较，防止重复乘 0.9 或忽略它。

## 6. 研究资源归档

完整下载与证据目录：`/Users/leon1ca/Documents/Codex/2026-10-04/ji-y/work/readest-fonts`。含字体原件/许可、官方 release 元数据、Google 仓库 commit、npm integrity、原始 CSS、原始 Google 28 分包/LXGW GB 248 分包、只读轮廓验证脚本、报告与 SHA256SUMS。该目录的 npm 包/工具源码仅用于核验，不需要全部打入 APK。

项目目前打包的字体/许可及原创声明哈希在 FONTS_MANIFEST.json 中；若实现角色继续改 `readest.css`，必须同步刷新 manifest 的 CSS hash。字体文件的任何替换都要重新做来源、许可、默认字形和设备回归核验。
