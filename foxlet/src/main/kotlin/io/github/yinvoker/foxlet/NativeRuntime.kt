package io.github.yinvoker.foxlet

/** Narrow JNI boundary; permits lifecycle tests without loading an Android shared library. */
internal interface NativeRuntime {
    fun createService(threads: Int, cacheSize: Int): Long
    fun destroyService(service: Long)
    fun loadModel(service: Long, config: String, prefixes: ByteArray?): Long
    fun releaseModel(service: Long, model: Long): Boolean
    fun translate(service: Long, model: Long, texts: Array<String>, html: Boolean): Array<String>
    fun translatePivot(service: Long, first: Long, second: Long, texts: Array<String>, html: Boolean): Array<String>
}

internal object JniRuntime : NativeRuntime {
    override fun createService(threads: Int, cacheSize: Int) = NativeBridge.createService(threads, cacheSize)
    override fun destroyService(service: Long) = NativeBridge.destroyService(service)
    override fun loadModel(service: Long, config: String, prefixes: ByteArray?) = NativeBridge.loadModel(service, config, prefixes)
    override fun releaseModel(service: Long, model: Long) = NativeBridge.releaseModel(service, model)
    override fun translate(service: Long, model: Long, texts: Array<String>, html: Boolean) = NativeBridge.translate(service, model, texts, html)
    override fun translatePivot(service: Long, first: Long, second: Long, texts: Array<String>, html: Boolean) =
        NativeBridge.translatePivot(service, first, second, texts, html)
}
