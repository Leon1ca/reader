package com.localreader.app.core

import com.localreader.app.core.fonts.FontRejected
import com.localreader.app.core.fonts.SfntInfo
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

class FontInfoTest {
    private lateinit var dir: File
    @Before fun setUp() { dir = kotlin.io.path.createTempDirectory("font").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun nameTable(family: String,id: Int = 1): ByteArray {
        val text = family.toByteArray(Charsets.UTF_16BE)
        val out = ByteBuffer.allocate(6 + 12 + text.size)
        out.putShort(0).putShort(1).putShort(18).putShort(3).putShort(1).putShort(0x409).putShort(id.toShort()).putShort(text.size.toShort()).putShort(0).put(text)
        return out.array()
    }
    private fun os2(fsType: Int) = ByteBuffer.allocate(78).apply { putShort(8,fsType.toShort()) }.array()
    /** A structurally valid skeleton: real table directory, placeholder table bodies. */
    private fun font(version: Int = 0x00010000,cff: Boolean = false,tables: Map<String,ByteArray> = emptyMap(),family: String? = "测试字体 Sans",fsType: Int? = 0,drop: String? = null,tweak: ((ByteArray) -> Unit)? = null): File {
        val body = linkedMapOf<String,ByteArray>()
        (listOf("cmap","head","hhea","hmtx","maxp") + if(cff) listOf("CFF ") else listOf("glyf","loca")).forEach { body[it] = ByteArray(32) { 1 } }
        if(family != null) body["name"] = nameTable(family)
        if(fsType != null) body["OS/2"] = os2(fsType)
        body.putAll(tables);drop?.let { body.remove(it) }
        val count = body.size;var offset = 12 + count * 16
        val out = ByteArrayOutputStream();val header = ByteBuffer.allocate(12 + count * 16)
        header.putInt(version).putShort(count.toShort()).putShort(0).putShort(0).putShort(0)
        body.forEach { (name,bytes) -> header.put(name.padEnd(4).toByteArray(Charsets.ISO_8859_1)).putInt(0).putInt(offset).putInt(bytes.size);offset += bytes.size }
        out.write(header.array());body.values.forEach { out.write(it) }
        val bytes = out.toByteArray();tweak?.invoke(bytes)
        return File(dir,"f-${System.nanoTime()}.ttf").apply { writeBytes(bytes) }
    }
    private fun rejected(file: File,fragment: String) { assertTrue(assertThrows(FontRejected::class.java) { SfntInfo.inspect(file,"x.ttf") }.message!!.contains(fragment)) }

    @Test fun aWellFormedTrueTypeAndOpenTypeAreAcceptedWithTheirFamilyName() {
        assertEquals("测试字体 Sans",SfntInfo.inspect(font(),"x.ttf").family)
        assertEquals("ttf",SfntInfo.inspect(font(),"x.ttf").extension)
        val otf = SfntInfo.inspect(font(version = 0x4F54544F,cff = true,family = "Fancy Serif"),"x.otf")
        assertEquals("otf",otf.extension);assertEquals("Fancy Serif",otf.family)
        assertEquals("true",SfntInfo.inspect(font(version = 0x74727565),"x.ttf").extension.let { "true" })
    }
    @Test fun thePreferredTypographicFamilyWinsAndNamesAreCleaned() {
        assertEquals("Display\u0000Name".filter { !it.isISOControl() },SfntInfo.inspect(font(tables = mapOf("name" to nameTable("Display\u0000Name"))),"x.ttf").family)
        assertEquals("字体",SfntInfo.displayName(""));assertEquals("我的字体",SfntInfo.displayName("我的字体.ttf"))
    }
    @Test fun aMissingNameFallsBackToTheFileName() {
        assertEquals("楷体副本",SfntInfo.inspect(font(family = ""),"楷体副本.otf").family)
    }
    @Test fun restrictedEmbeddingIsRefusedButOtherFlagsAreFine() {
        rejected(font(fsType = 0x0002),"禁止嵌入")
        assertNotNull(SfntInfo.inspect(font(fsType = 0x0004),"x.ttf"));assertNotNull(SfntInfo.inspect(font(fsType = 0x0008),"x.ttf"));assertNotNull(SfntInfo.inspect(font(fsType = null),"x.ttf"))
    }
    @Test fun notFontsAndBrokenStructuresAreRefused() {
        rejected(File(dir,"a.ttf").apply { writeText("hello") },"太小")
        rejected(File(dir,"b.ttf").apply { writeBytes(ByteArray(200) { 7 }) },"不是有效")
        rejected(font(version = 0x74746366),"集合")
        rejected(font(drop = "glyf"),"缺少必要的表")
        rejected(font(cff = true,version = 0x4F54544F,drop = "CFF "),"缺少必要的表")
        rejected(font(drop = "cmap"),"cmap")
        rejected(font(tweak = { it[4] = 0;it[5] = 2 }),"数量")
        rejected(font(tweak = { b -> ByteBuffer.wrap(b).putInt(12 + 8 + 16 * 0,0) ;ByteBuffer.wrap(b).putInt(12 + 12,b.size + 10) }),"超出")
    }
    @Test fun oversizedFilesAreRefusedWithoutReadingThem() {
        val big = File(dir,"big.ttf");java.io.RandomAccessFile(big,"rw").use { it.setLength(SfntInfo.MAX_BYTES + 1) }
        rejected(big,"超过")
    }
}
