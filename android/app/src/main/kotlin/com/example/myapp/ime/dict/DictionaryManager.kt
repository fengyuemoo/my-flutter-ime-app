package com.example.myapp.ime.dict

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.api.DictionaryProvider
import com.example.myapp.dict.impl.SQLiteDictionaryEngine
import com.example.myapp.dict.install.DictionaryInstaller

class DictionaryManager(
    context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper())
) : DictionaryProvider {

    override val dictionary: Dictionary = SQLiteDictionaryEngine(context)

    // 兼容旧用法：老代码可能还在用 dictManager.engine
    val engine: Dictionary get() = dictionary

    private val installer: DictionaryInstaller = DictionaryInstaller(context)

    @Volatile
    private var installRunning: Boolean = false

    override val isReady: Boolean
        get() = dictionary.isLoaded

    override val debugInfo: String?
        get() = dictionary.debugInfo

    override fun ensureReadyAsync(force: Boolean, onDone: ((Boolean) -> Unit)?) {
        if (!force && dictionary.isLoaded) {
            onDone?.invoke(true)
            return
        }
        if (installRunning) return

        installRunning = true
        Thread {
            val ok = installer.ensureInstalled(force = force)
            dictionary.setReady(ok, installer.debugInfo)
            installRunning = false
            mainHandler.post { onDone?.invoke(ok) }
        }.start()
    }
}
