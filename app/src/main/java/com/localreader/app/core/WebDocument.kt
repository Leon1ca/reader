package com.localreader.app.core

enum class WebContentKind { TXT, ARTICLE }
data class WebTypographyProfile(val id: String, val kind: WebContentKind, val fontFamily: String,
                                val mobileScale: Float = 1.25f, val lineHeight: Float = 1.6f,
                                val paragraphMarginEm: Float = 1f, val indentEm: Float = 2f,
                                val justify: Boolean = true)
object WebTypographyProfiles {
    private const val SANS = "Roboto, 'LXGW WenKai GB Screen', 'Noto Sans', 'Open Sans', 'PT Sans', Helvetica, 'Noto Sans SC', 'Noto Sans TC', 'MiSans L3', sans-serif"
    val readestTxtCjk = WebTypographyProfile("readest-0.12.10-txt-cjk", WebContentKind.TXT, "Arial, $SANS")
    val readestArticleCjk = WebTypographyProfile("readest-0.12.10-article-cjk", WebContentKind.ARTICLE,
        "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Oxygen-Sans', Ubuntu, Cantarell, 'Helvetica Neue', $SANS")
}
data class WebWindow(val start: Int, val endExclusive: Int, val total: Int, val blocks: List<TextBlock>)
object WebDocument {
    const val WINDOW_BLOCKS = 32
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val URL = "$ORIGIN/reader/document.html"
    fun escape(text: String): String = buildString(text.length) { text.forEach { append(when (it) {
        '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&#39;"; else -> it.toString()
    }) } }
    fun window(engine: ReaderEngine, locator: Locator.Text): WebWindow {
        val resolved = engine.resolve(locator)
        val start = (resolved.paragraph / WINDOW_BLOCKS) * WINDOW_BLOCKS
        val end = minOf(start + WINDOW_BLOCKS, engine.index.paragraphs)
        return WebWindow(start, end, engine.index.paragraphs, (start until end).map(engine::block))
    }
    /** Only our script/styles execute: user text is encoded as text nodes, never HTML. */
    fun html(window: WebWindow, target: ResolvedText, token: String, nonce: String, profile: WebTypographyProfile,
             fontSize: Float, theme: String, fontCss: String = ""): String {
        require(token.matches(Regex("[a-zA-Z0-9-]+")) && nonce.matches(Regex("[a-zA-Z0-9-]+")))
        require(fontSize in 12f..36f)
        val dark = theme == "dark"
        val background = when (theme) { "dark" -> "#19201c"; "paper" -> "#f6f1e7"; else -> "#ffffff" }
        val foreground = if (dark) "#dbe5dd" else "#272c28"
        val content = buildString {
            var openTag: String? = null
            window.blocks.forEach { block ->
                if (openTag == null || !block.continuation) {
                    openTag?.let { append("</$it>") }
                    openTag = if (block.heading) "h2" else "p"
                    append("<$openTag${if (block.continuation) " class=\"continuation\"" else ""}>")
                }
                append("<span data-paragraph=\"${block.index}\" data-length=\"${block.text.length}\">${escape(block.text.ifEmpty { "\u200B" })}</span>")
                if (!block.continues) { append("</$openTag>"); openTag = null }
            }
            openTag?.let { append("</$it>") }
        }
        return """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'self' 'unsafe-inline'; script-src 'nonce-$nonce'; font-src $ORIGIN; img-src 'none'; connect-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'">
            <link rel="stylesheet" href="$ORIGIN/assets/fonts/readest.css">
            <style>$fontCss
            :root {font-size:${fontSize * profile.mobileScale}px;color-scheme:${if (dark) "dark" else "light"}}
            html,body{margin:0;padding:0;background:$background;color:$foreground;overflow-wrap:break-word;}
            body{padding:44px 16px;font-family:${profile.fontFamily};font-weight:400;letter-spacing:0;word-spacing:0;}
            p{margin:${profile.paragraphMarginEm}em 0;line-height:${profile.lineHeight};text-indent:${profile.indentEm}em;text-align:${if (profile.justify) "justify" else "start"};hyphens:auto;white-space:pre-wrap;}
            p.continuation{text-indent:0;margin-top:0;}
            h2{font-size:1.5em;line-height:1.3;margin:1em 0;text-indent:0;}
            </style></head><body>$content
            <script nonce="$nonce">
            (()=>{'use strict';const token='$token';let ready=false,timer=0;
            const nodes=Array.from(document.querySelectorAll('[data-paragraph]'));
            function report(){if(!ready)return;const p=nodes.find(p=>p.getBoundingClientRect().bottom>0)||nodes[nodes.length-1];if(!p)return;
              const n=p.firstChild;let lo=0,hi=Number(p.dataset.length);if(n&&hi>0){while(lo<hi){const m=Math.floor((lo+hi)/2);const r=document.createRange();r.setStart(n,m);r.setEnd(n,Math.min(m+1,n.length));const box=r.getBoundingClientRect();if(box.bottom<=0)lo=m+1;else hi=m;}}
              ReaderPosition.position(token,Number(p.dataset.paragraph),lo);}
            addEventListener('scroll',()=>{clearTimeout(timer);timer=setTimeout(report,120);},{passive:true});
            function restore(){const p=nodes.find(p=>Number(p.dataset.paragraph)===${target.paragraph});if(p){const n=p.firstChild;const r=document.createRange();const o=Math.min(${target.character},Number(p.dataset.length));r.setStart(n,o);r.setEnd(n,Math.min(o+1,n.length));const rect=r.getBoundingClientRect();if(${target.paragraph}===${window.start}&&o===0)scrollTo(0,0);else scrollTo(0,scrollY+rect.top);}ready=true;report();}
            document.fonts.ready.then(()=>requestAnimationFrame(()=>requestAnimationFrame(restore)));
            })();</script></body></html>""".trimIndent()
    }
}
