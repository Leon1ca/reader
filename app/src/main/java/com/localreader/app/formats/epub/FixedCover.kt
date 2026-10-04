package com.localreader.app.formats.epub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import java.io.File
import java.io.FileOutputStream

/** Import thumbnail only; the publication resource keeps its original full-resolution bytes. */
internal object FixedCover {
    // WEBP_LOSSY was introduced in API30. API29's WEBP is its lossless/lossy
    // predecessor; compress quality80 selects the required lossy encoding there.
    @Suppress("DEPRECATION")
    private fun format()=if(Build.VERSION.SDK_INT>=30)Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
    fun create(input: File,output: File): Boolean {
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(input.path,bounds)
        if(bounds.outWidth<=0 || bounds.outHeight<=0)return false
        var sample=1
        while(minOf(bounds.outWidth,bounds.outHeight)/sample>600 || bounds.outWidth.toLong()*bounds.outHeight/sample/sample>2_000_000)sample*=2
        val bitmap=BitmapFactory.decodeFile(input.path,BitmapFactory.Options().apply { inSampleSize=sample }) ?: return false
        try { FileOutputStream(output).use { stream -> check(bitmap.compress(format(),80,stream));stream.fd.sync() };return true }
        finally { bitmap.recycle() }
    }
}
