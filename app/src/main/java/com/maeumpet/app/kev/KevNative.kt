package com.maeumpet.app.kev

/** libkev.so (native/kev): llama.cpp backbone. Handles are raw pointers; 0 means load failed. */
internal object KevNative {
    init {
        System.loadLibrary("kev")
    }

    @JvmStatic external fun load(path: String, nThreads: Int, nCtx: Int, nUbatch: Int, loadMode: Int, repack: Boolean): Long
    @JvmStatic external fun free(handle: Long)
    @JvmStatic external fun embdDim(handle: Long): Int

    /** utf8 must be real UTF-8 bytes (String.toByteArray(Charsets.UTF_8)). */
    @JvmStatic external fun tokenize(handle: Long, utf8: ByteArray, parseSpecial: Boolean): IntArray

    /** Hidden states at [read] positions of one fresh causal row, concatenated (read.size * embdDim); null on failure. */
    @JvmStatic external fun hidden(handle: Long, ids: IntArray, read: IntArray): FloatArray?
}
