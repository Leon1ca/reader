# 正文字体来源与许可

核验日期：2026-10-04。对应 Readest 0.12.10，官方 commit `8a56831af61841175b49cc2740bffcde57b470b0`。

本轮已独立从字体官方仓库/发布页取得五个字体文件，未从 Readest APK 复制任何字体或代码。实现角色已将它们放入 `app/src/main/assets/fonts/`；规划角色核对了五个打包文件与下载原件的 SHA-256 完全相同。机器可读完整清单为 [FONTS_MANIFEST.json](FONTS_MANIFEST.json)。

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

`lxgw-wenkai-screen-web@1.520.0` 的 package.json 标为 MIT，但字体文件的嵌入许可与来源为 OFL-1.1；本项目打包的是官方完整 TTF 与官方 OFL。

## 3. 使用范围

- 这五个字体只用于**正文渲染**（WebView）。应用自身界面（书架、设置、面板）的常用文字使用 `res/font/` 的 Noto Sans SC 子集（300/400/500），缺字回退系统字体，衬线标题使用系统字体；不加载这五个正文资产。界面子集的 OFL 许可随 APK 的 `NotoSansSC-OFL.txt` 保留。
- `app/src/main/assets/fonts/readest.css` 声明 `@font-face`（`font-display:block`）。Roboto/Bitter 的 `unicode-range` 等于原 Google 网页分包的有效覆盖；LXGW 不设范围。
- 字体以固定、与会话无关的地址 `https://appassets.androidplatform.net/fonts/<文件>` 提供，并带长期缓存头；APK 内以不压缩方式存放（`noCompress ttf`），渲染进程只需解码一次。
- 2026-10-14正文选择：默认英文Roboto＋中文LXGW屏幕版；宋体英文Bitter、黑体英文Roboto，中文按对应系统字体可用性回退LXGW；旧kai偏好兼容保留并在面板显示文楷。导入字体缺字回退默认栈，AUTHOR 与 CUSTOM 均保持用户所选正文字体，书内嵌字体及 monospace 保留，出版物命名的系统家族和 generic 家族重定向至所选字体，固定版式不强制重排。资产与许可未变；本次候选范围与实际检查见[当前交接](ai-handoff.md)及[0.6.0收尾记录](RELEASE_0_6_0.md)。
