package com.localreader.app.core

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Derived display slices only. The original decoder/index and every persisted coordinate stay intact. */
class TxtDisplayProjection private constructor(private val file: RandomAccessFile,private val first: Long,private val count: Int) : Closeable {
    data class Slice(val sourceOffset: Long,val length: Int,val start: Int,val end: Int,val retainedFirst: Int,val retainedLast: Int,val emptyUnit: Boolean,val headingCode: Int) {
        val generatedHeading get()=headingCode.coerceAtLeast(0)
        val sourceHeading get()=headingCode == -1
        val displayedLength get()=end-start
        fun sourceCharacter(display: Int): Int { require(display in 0..displayedLength);return start+display }
    }
    private val mapped=file.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY,first,count*RECORD_BYTES)
    private val cache=object: LinkedHashMap<Int,Slice>(64,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int,Slice>?)=size>64
    }
    @Synchronized fun slice(record: Int): Slice {
        require(record in 0 until count)
        cache[record]?.let {return it}
        val bytes=mapped.asReadOnlyBuffer();bytes.position((record*RECORD_BYTES).toInt())
        val value=Slice(bytes.long,bytes.int,bytes.int,bytes.int,bytes.int,bytes.int,bytes.int!=0,bytes.int)
        require(value.length in 0..TxtParser.MAX_BLOCK+1 && value.start in 0..value.end && value.end<=value.length && value.retainedFirst in 0 until count && value.retainedLast in value.retainedFirst until count)
        return value.also {cache[record]=it}
    }
    fun target(resolved: ResolvedText): ResolvedText {
        val own=slice(resolved.paragraph)
        if(own.displayedLength>0 && resolved.character in own.start..own.end)
            return resolved.copy(character=resolved.character-own.start)
        if(own.displayedLength>0)return resolved.copy(character=(resolved.character-own.start).coerceIn(0,own.displayedLength),nearby=true)
        if(own.emptyUnit) {
            val previous=slice(own.retainedFirst);val next=slice(own.retainedLast)
            val offset=own.sourceOffset+resolved.character
            val previousEnd=previous.sourceOffset+previous.end
            val nextStart=next.sourceOffset+next.start
            val forward=own.retainedFirst==own.retainedLast && own.retainedFirst>resolved.paragraph ||
                own.retainedLast>resolved.paragraph && (own.retainedFirst>=resolved.paragraph || nextStart-offset<=offset-previousEnd)
            return if(forward)ResolvedText(own.retainedLast,0,true) else ResolvedText(own.retainedFirst,previous.displayedLength,true)
        }
        // Trimming removes a single prefix/suffix of a physical paragraph.
        // Its boundary records are stored directly, so an extreme paragraph
        // never requires a linear walk for each restore/search target.
        val record=if(resolved.paragraph<=own.retainedFirst)own.retainedFirst else own.retainedLast
        val boundary=slice(record)
        val character=if(resolved.paragraph<=own.retainedFirst)0 else boundary.displayedLength
        return ResolvedText(record,character,true)
    }
    @Synchronized override fun close() {cache.clear();file.close()}
    companion object {
        const val ALGORITHM="txt-display-trim-v4"
        private const val RECORD_BYTES=36L
        private const val MAGIC=0x54504432
        private val publishLock=Any()
        /** ECMAScript trim set, independent of Kotlin's broader whitespace classification. */
        internal fun trimCharacter(value: Char)=value in "\u0009\u000A\u000B\u000C\u000D\u0020\u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF"
        fun prepare(source: TxtTextSource,directory: File,checkpoint: ()->Unit={}): TxtDisplayProjection = synchronized(publishLock) {
            require(source.index.paragraphs in 1..TxtParser.MAX_PARAGRAPHS)
            check(directory.isDirectory)
            directory.listFiles()?.filter { it.name.matches(Regex("\\.$ALGORITHM-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.stage")) }?.forEach {check(it.delete()) {"无法清理未完成文字显示派生文件"}}
            val installed=File(directory,"$ALGORITHM.bin")
            fun open(): TxtDisplayProjection {
                val input=RandomAccessFile(installed,"r")
                try {
                    require(input.readInt()==MAGIC && input.readUTF()==source.version && input.readUTF()==source.index.encoding)
                    require(input.readInt()==source.index.paragraphs && input.readLong()==source.index.totalCharacters)
                    val first=input.filePointer
                    require(input.length()==first+source.index.paragraphs*RECORD_BYTES+8)
                    val expectedPosition=input.length()-8
                    val body=input.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY,0,expectedPosition)
                    val checksum=java.util.zip.CRC32();val buffer=ByteArray(65536)
                    while(body.hasRemaining()) { checkpoint();val size=minOf(body.remaining(),buffer.size);body.get(buffer,0,size);checksum.update(buffer,0,size) }
                    input.seek(expectedPosition);require(input.readLong()==checksum.value) { "TXT 显示投影校验失败" }
                    // Validate fixed-size records before publication without decoding text.
                    val records=input.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY,first,source.index.paragraphs*RECORD_BYTES)
                    for(record in 0 until source.index.paragraphs) {
                        if(record%1024==0)checkpoint()
                        val offset=records.long;val length=records.int;val start=records.int;val end=records.int
                        val retainedFirst=records.int;val retainedLast=records.int;val empty=records.int;val heading=records.int
                        require(offset==source.metadata(record).sourceOffset && length in 0..TxtParser.MAX_BLOCK+1 &&
                            start in 0..end && end<=length && retainedFirst in 0 until source.index.paragraphs && retainedLast in retainedFirst until source.index.paragraphs && empty in 0..1 && heading>=-1)
                    }
                    return TxtDisplayProjection(input,first,source.index.paragraphs)
                } catch(error: Throwable) {try {input.close()} catch(close: Throwable) {error.addSuppressed(close)};throw error}
            }
            if(installed.isFile)try {return@synchronized open()} catch(_: java.io.IOException) {} catch(_: IllegalArgumentException) {}
            val stage=File(directory,".$ALGORITHM-${UUID.randomUUID()}.stage")
            var failure: Throwable?=null
            try {
                RandomAccessFile(stage,"rw").use {output ->
                    output.writeInt(MAGIC);output.writeUTF(source.version);output.writeUTF(source.index.encoding)
                    output.writeInt(source.index.paragraphs);output.writeLong(source.index.totalCharacters)
                    val first=output.filePointer
                    output.setLength(first+source.index.paragraphs*RECORD_BYTES)
                    val rows=output.channel.map(java.nio.channels.FileChannel.MapMode.READ_WRITE,first,source.index.paragraphs*RECORD_BYTES)
                    var visibleUnits=0;var visibleRecords=0;var unitHeading=false;var hasHeading=false
                    var unit=0;var keptFirst=Long.MAX_VALUE;var keptEnd=Long.MIN_VALUE;var retainedFirst=0;var retainedLast=0
                    fun finish(end: Int) {
                        val empty=keptFirst==Long.MAX_VALUE
                        val title=if(unitHeading)-1 else if(!empty && visibleUnits%100==0)visibleUnits/100+1 else 0
                        if(!empty) {visibleUnits++;visibleRecords++}
                        for(record in unit until end) {
                            if(record%1024==0)checkpoint()
                            rows.position((record*RECORD_BYTES).toInt())
                            val offset=rows.long;val length=rows.int
                            val start=if(keptFirst==Long.MAX_VALUE)0 else (keptFirst-offset).coerceIn(0,length.toLong()).toInt()
                            val stop=if(keptEnd==Long.MIN_VALUE)0 else (keptEnd-offset).coerceIn(start.toLong(),length.toLong()).toInt()
                            rows.putInt(start);rows.putInt(stop);rows.putInt(if(empty)record else retainedFirst);rows.putInt(if(empty)record else retainedLast);rows.putInt(if(empty)1 else 0);rows.putInt(if(unitHeading)-1 else if(record==retainedFirst)title else 0)
                        }
                        unit=end;unitHeading=false;keptFirst=Long.MAX_VALUE;keptEnd=Long.MIN_VALUE
                    }
                    for(record in 0 until source.index.paragraphs) {
                        checkpoint();val block=source.block(record)
                        if(record>unit && !block.continuation)finish(record)
                        rows.position((record*RECORD_BYTES).toInt())
                        rows.putLong(block.sourceOffset);rows.putInt(block.text.length)
                        repeat(6) {rows.putInt(0)}
                        if(record==unit)unitHeading=block.heading
                        if(block.heading)hasHeading=true
                        block.text.forEachIndexed {offset,value ->
                            if(!trimCharacter(value)) {
                            if(keptFirst==Long.MAX_VALUE)retainedFirst=record
                            retainedLast=record;keptFirst=minOf(keptFirst,block.sourceOffset+offset);keptEnd=block.sourceOffset+offset+1
                        }}
                    }
                    finish(source.index.paragraphs);require(visibleRecords>0) {"TXT 没有可显示正文；原文件已保留"}
                    // Existing source headings remain explicit. We do not
                    // imitate an external language detector's misclassification
                    // or let a distant long paragraph change title typography.
                    // Numeric headings decorate only books without source titles.
                    if(hasHeading)for(record in 0 until source.index.paragraphs) {
                        if(record%1024==0)checkpoint()
                        rows.position((record*RECORD_BYTES+32).toInt())
                        if(rows.int>0) {rows.position((record*RECORD_BYTES+32).toInt());rows.putInt(0)}
                    }
                    // Empty natural paragraphs consume no displayed rows. Two
                    // sequential passes bind their historical anchors to the
                    // surrounding retained source, without an O(n) restore.
                    var previous=-1
                    for(record in 0 until source.index.paragraphs) {
                        if(record%1024==0)checkpoint()
                        rows.position((record*RECORD_BYTES+12).toInt())
                        val start=rows.int;val end=rows.int;rows.position(rows.position()+8);val empty=rows.int!=0
                        if(end>start)previous=record
                        if(empty) {rows.position((record*RECORD_BYTES+20).toInt());rows.putInt(previous)}
                    }
                    var next=-1
                    for(record in source.index.paragraphs-1 downTo 0) {
                        if(record%1024==0)checkpoint()
                        rows.position((record*RECORD_BYTES+12).toInt())
                        val start=rows.int;val end=rows.int;val before=rows.int;rows.int;val empty=rows.int!=0
                        if(end>start)next=record
                        if(empty) {rows.position((record*RECORD_BYTES+20).toInt());rows.putInt(if(before<0)next else before);rows.putInt(if(next<0)before else next)}
                    }
                    checkpoint();rows.force()
                    val body=output.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY,0,output.length())
                    val checksum=java.util.zip.CRC32();val buffer=ByteArray(65536)
                    while(body.hasRemaining()) { checkpoint();val size=minOf(body.remaining(),buffer.size);body.get(buffer,0,size);checksum.update(buffer,0,size) }
                    output.seek(output.length());output.writeLong(checksum.value);output.fd.sync()
                }
                Files.move(stage.toPath(),installed.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
                open()
            } catch(error: Throwable) {failure=error;throw error}
            finally {if(stage.exists())try {check(stage.delete()) {"无法清理文字显示派生文件"}} catch(error: Throwable) {failure?.addSuppressed(error) ?: throw error}}
        }
    }
}
