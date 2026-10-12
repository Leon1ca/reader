package com.localreader.app.ui.shelf

/** Decoder rounding is upwards even when one dimension is thinner than the sample. */
internal object CoverSampling {
    fun sampleSize(width: Int,height: Int,maxPixels: Long=131072): Int {
        require(width>0 && height>0 && maxPixels>0)
        var sample=1
        fun pixels()=((width.toLong()+sample-1)/sample)*((height.toLong()+sample-1)/sample)
        while(pixels()>maxPixels || maxOf((width.toLong()+sample-1)/sample,(height.toLong()+sample-1)/sample)>360)sample=Math.multiplyExact(sample,2)
        return sample
    }
}
