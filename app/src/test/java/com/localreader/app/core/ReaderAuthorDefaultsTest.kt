package com.localreader.app.core

import com.localreader.app.formats.epub.EpubCss
import com.localreader.app.formats.epub.EpubStyleMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Publishes actual compiled CSS for the independent CSSOM browser check; source styles remain immutable. */
class ReaderAuthorDefaultsTest {
    @Test fun authoredAndCustomRenderCopiesKeepSourceAndExportRealCompilerVectors() {
        val author="font-family:'PackageFont',serif;font-size:24px;line-height:2;color:purple;background:rgb(230,240,250);text-indent:3em;text-align:right"
        val inputs=listOf(
            Triple("html","html{$author}",""),Triple("root",":root{$author}",""),Triple("body","body{$author}",""),
            Triple("inline","","$author"),Triple("important","p{font-size:24px!important;line-height:2!important;color:purple!important;font-family:'PackageFont',serif!important}",""),
            Triple("shorthand","p{font:italic 700 24px/2 'PackageFont',serif;color:purple}",""))
        val output=JSONArray()
        for((name,sheet,inline) in inputs)for(mode in EpubStyleMode.entries) {
            val source=sheet to inline
            val rules=ReaderRules(epubStyleMode=mode.name,fontFamily="sans",lineHeight=1.6)
            val compiled=RuleCompiler.compile(WebContentKind.EPUB,rules,"https://appassets.androidplatform.net/author/fonts/readest.css")
            val css=EpubCss({_,_->null})
            output.put(JSONObject().put("name","$name-${mode.name}").put("mode",mode.name)
                .put("before",compiled.beforeAuthor).put("after",compiled.afterAuthor).put("sheet",css.filter(sheet,mode).text).put("inline",css.inline(inline,mode))
                .put("expectedSize",if(mode==EpubStyleMode.AUTHOR)24 else 20).put("expectedLine",if(mode==EpubStyleMode.AUTHOR)48 else 32)
                .put("expectedColor",if(mode==EpubStyleMode.AUTHOR)"rgb(128, 0, 128)" else "rgb(23, 23, 23)")
                .put("packageFamily",mode==EpubStyleMode.AUTHOR).put("semanticEmphasis",name=="shorthand"))
            assertEquals(source,sheet to inline)
        }
        for(theme in listOf("white","dark")) {
            val rules=ReaderRules(theme=theme);val compiled=RuleCompiler.compile(WebContentKind.EPUB,rules,"https://appassets.androidplatform.net/author/fonts/readest.css")
            output.put(JSONObject().put("name","missing-$theme").put("mode","AUTHOR").put("before",compiled.beforeAuthor).put("after",compiled.afterAuthor).put("sheet","").put("inline","")
                .put("expectedSize",20).put("expectedLine",28).put("expectedColor",if(theme=="dark")"rgb(185, 178, 166)" else "rgb(23, 23, 23)").put("packageFamily",false).put("semanticEmphasis",false))
        }
        val report=File("build/reports/reader-author-rules.json")
        val directory=requireNotNull(report.parentFile)
        check(directory.mkdirs() || directory.isDirectory)
        report.writeText(output.toString(2))
        assertEquals(14,output.length())
    }
}
