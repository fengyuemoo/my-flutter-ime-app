package com.example.myapp

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.example.myapp.ime.ImeGraph
import com.example.myapp.ime.bootstrap.ImeBootstrapper
import com.example.myapp.ime.keyboard.KeyboardHost
import com.example.myapp.ime.prefs.KeyboardPrefs
import com.example.myapp.ime.ui.ImeUi
import java.util.Locale

class SimpleImeService : InputMethodService(), KeyboardHost {

    private lateinit var ui: ImeUi
    private lateinit var mainView: View
    private lateinit var bodyFrame: FrameLayout

    private lateinit var graph: ImeGraph
    private lateinit var bootstrapper: ImeBootstrapper

    // Index-based candidate click.
    private var onCandidateIndexClick: (Int) -> Unit = {}

    // --- floating preedit overlay (WindowManager) ---
    private var preeditOverlayView: TextView? = null
    private var preeditOverlayParams: WindowManager.LayoutParams? = null
    private val wm: WindowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    // cache for overlay typeface
    private val typefaceCache = HashMap<String, Typeface>()

    override fun onToolbarUpdate() {
        if (this::graph.isInitialized) graph.toolbarController.refresh()
    }

    override fun onClearComposingRequested() {
        if (this::graph.isInitialized) graph.dispatcher.clearComposing()
    }

    override fun onCreateInputView(): View {
        ui = ImeUi()

        // Use index-based inflate API.
        mainView = ui.inflateWithIndex(
            inflater = layoutInflater,
            onCandidateIndexClick = { index -> onCandidateIndexClick(index) }
        )
        bodyFrame = ui.bodyFrame

        ui.setComposingPreviewListener { text ->
            updateFloatingPreedit(text)
        }

        graph = ImeGraph.build(
            context = this,
            rootView = mainView,
            bodyFrame = bodyFrame,
            ui = ui,
            host = this,
            inputConnectionProvider = { currentInputConnection }
        )

        onCandidateIndexClick = { index -> graph.candidateController.commitCandidateAt(index) }

        graph.uiBinder.bind()

        bootstrapper = ImeBootstrapper(graph)
        bootstrapper.initFromPrefsAndEnsureDict()

        bindToolbarButtons()

        graph.themeController.load()
        graph.themeController.apply()

        updateFloatingPreedit(null)
        return mainView
    }

    private fun bindToolbarButtons() {
        ui.getThemeButton().setOnClickListener {
            if (this::graph.isInitialized) {
                graph.themeController.toggle()
                refreshFloatingPreeditStyle()
            }
        }

        ui.getLayoutButton().setOnClickListener {
            val cur = KeyboardPrefs.loadUseT9Layout(this)
            val next = !cur
            KeyboardPrefs.saveUseT9Layout(this, next)

            if (this::graph.isInitialized) {
                graph.keyboardController.setLayout(next)
            } else {
                onToolbarUpdate()
            }
        }
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)

        if (!this::graph.isInitialized) return

        bootstrapper.resetUiForNewInput()
        bootstrapper.reloadPrefsAndEnsureDict()

        graph.themeController.load()
        graph.themeController.apply()

        updateFloatingPreedit(null)
        refreshFloatingPreeditStyle()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        updateFloatingPreedit(null)
        removeFloatingPreedit()
    }

    override fun onDestroy() {
        removeFloatingPreedit()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun resolveTypefaceFromSpec(spec: String): Typeface {
        synchronized(typefaceCache) {
            typefaceCache[spec]?.let { return it }

            val tf = runCatching {
                if (spec.startsWith("asset:")) {
                    val path = spec.removePrefix("asset:")
                    Typeface.createFromAsset(assets, path)
                } else {
                    Typeface.create(spec, Typeface.NORMAL)
                }
            }.getOrElse {
                Typeface.DEFAULT
            }

            typefaceCache[spec] = tf
            return tf
        }
    }

    private fun applyPreeditOverlayFontFromPrefs(tv: TextView) {
        val familySpec = KeyboardPrefs.loadFontFamily(this)
        val scale = KeyboardPrefs.loadFontScale(this).coerceIn(0.7f, 1.4f)

        tv.typeface = resolveTypefaceFromSpec(familySpec)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * scale)
    }

    private fun applyPreeditOverlayThemeFromPrefs(tv: TextView) {
        val themeMode = KeyboardPrefs.loadThemeMode(this)
        if (themeMode == KeyboardPrefs.THEME_DARK) {
            tv.setTextColor(Color.WHITE)
            tv.setBackgroundColor(Color.parseColor("#CC333333"))
        } else {
            tv.setTextColor(Color.BLACK)
            tv.setBackgroundColor(Color.parseColor("#CCF5F5F5"))
        }
    }

    private fun applyPreeditOverlayStyleFromPrefs(tv: TextView) {
        applyPreeditOverlayFontFromPrefs(tv)
        applyPreeditOverlayThemeFromPrefs(tv)
    }

    private fun refreshFloatingPreeditStyle() {
        val tv = preeditOverlayView ?: return
        applyPreeditOverlayStyleFromPrefs(tv)
        val lp = preeditOverlayParams ?: return
        try {
            wm.updateViewLayout(tv, lp)
        } catch (_: Throwable) {
        }
    }

    private fun ensureFloatingPreedit() {
        if (preeditOverlayView != null) return

        val token = window?.window?.decorView?.windowToken ?: return

        val tv = TextView(this).apply {
            text = ""
            visibility = View.GONE
            setPadding(dp(8), dp(6), dp(8), dp(6))
            maxLines = 1
            elevation = 20f
            applyPreeditOverlayStyleFromPrefs(this)
        }

        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            this.token = token
            flags =
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.START or Gravity.TOP
            x = dp(8)
            y = 0
            title = "PreeditOverlay"
        }

        wm.addView(tv, params)
        preeditOverlayView = tv
        preeditOverlayParams = params
    }

    private fun updateFloatingPreedit(text: String?) {
        if (text.isNullOrEmpty()) {
            preeditOverlayView?.visibility = View.GONE
            preeditOverlayView?.text = ""
            return
        }

        ensureFloatingPreedit()
        val tv = preeditOverlayView ?: return
        val lp = preeditOverlayParams ?: return

        applyPreeditOverlayStyleFromPrefs(tv)

        tv.text = text
        tv.visibility = View.VISIBLE

        val loc = IntArray(2)
        mainView.getLocationOnScreen(loc)
        val keyboardTopOnScreenY = loc[1]

        lp.y = keyboardTopOnScreenY - dp(32)
        wm.updateViewLayout(tv, lp)
    }

    private fun removeFloatingPreedit() {
        val v = preeditOverlayView ?: return
        try {
            wm.removeViewImmediate(v)
        } catch (_: Throwable) {
        } finally {
            preeditOverlayView = null
            preeditOverlayParams = null
        }
    }
}
