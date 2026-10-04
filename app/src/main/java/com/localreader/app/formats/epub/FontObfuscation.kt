package com.localreader.app.formats.epub

import java.security.MessageDigest
import java.util.UUID

object FontObfuscation {
    fun key(identifier: String,algorithm: String): ByteArray = when(algorithm) {
        "http://www.idpf.org/2008/embedding" -> MessageDigest.getInstance("SHA-1").digest(identifier.filterNot { it==' ' || it=='\t' || it=='\r' || it=='\n' }.toByteArray(Charsets.UTF_8))
        "http://ns.adobe.com/pdf/enc#RC" -> {
            val value=identifier.filterNot { it==' ' || it=='\t' || it=='\r' || it=='\n' }.removePrefix("urn:uuid:")
            val uuid=UUID.fromString(value)
            require(uuid.toString().equals(value,ignoreCase=true)) { "EPUB Adobe 字体标识不是规范 UUID" }
            java.nio.ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
        }
        else -> error("不支持的字体加密算法")
    }
    fun decode(bytes: ByteArray,identifier: String,algorithm: String): ByteArray {
        val key=key(identifier,algorithm);val count=minOf(bytes.size,if(algorithm=="http://www.idpf.org/2008/embedding")1040 else 1024)
        return bytes.copyOf().also { result -> for(i in 0 until count)result[i]=(result[i].toInt() xor key[i%key.size].toInt()).toByte() }
    }
    fun validSignature(bytes: ByteArray): Boolean = bytes.size>=12 && (
        bytes.copyOfRange(0,4).contentEquals(byteArrayOf(0,1,0,0)) ||
        bytes.copyOfRange(0,4).toString(Charsets.US_ASCII) in setOf("OTTO","ttcf","wOFF","wOF2","true"))
}
