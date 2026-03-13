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
import com.example.myapp.ime.compose.cn.t9.PreeditDisplay
import com.example.myapp.ime.compose.cn.t9.PreeditSegment
import com.example.myapp.ime.keyboard.KeyboardHost
import com.example.myapp.ime.prefs.KeyboardPrefs
import com.example.myapp.ime.ui.ImeUi
import com.example.myapp.ime.ui.PreeditSpanBuilder

class SimpleImeService : InputMethodService(), KeyboardHost {

    private lateinit var ui: ImeUi
    private lateinit var mainView: View
    private lateinit var bodyFrame: FrameLayout

    private lateinit var graph: ImeGraph
    private lateinit var bootstrapper: ImeBootstrapper

    private var onCandidateIndexClick: (Int) -> Unit = {}

    // ── 悬浮 Preedit Overlay（WindowManager + 原生 TextView）────────
    private var preeditOverlayView: TextView? = null
    private var preeditOverlayParams: WindowManager.LayoutParams? = null
    private val wm: WindowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    private val typefaceCache = HashMap<String, Typeface>()

    override fun onToolbarUpdate() {
        if (this::graph.isInitialized) graph.toolbarController.refresh()
    }

    override fun onClearComposingRequested() {
        if (this::graph.isInitialized) graph.dispatcher.clearComposing()
    }

    override fun onCreateInputView(): View {
        ui = ImeUi()

        mainView = ui.inflateWithIndex(
            inflater = layoutInflater,
            onCandidateIndexClick = { index -> onCandidateIndexClick(index) }
        )
        bodyFrame = ui.bodyFrame

        // ── R-P04：使用带段样式的 PreeditDisplay 监听器 ──────────────
        // 完全在原生 Android 端渲染，通过 PreeditSpanBuilder 生成 SpannableString
        // 直接设置到悬浮 TextView，无任何 Flutter/跨框架回调。
        ui.setPreeditDisplayListener { display ->
            updateFloatingPreeditDisplay(display)
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

        updateFloatingPreeditDisplay(null)
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

    override fun onStartInputView(
        info: android.view.inputmethod.EditorInfo?,
        restarting: Boolean
    ) {
        super.onStartInputView(info, restarting)

        if (!this::graph.isInitialized) return

        bootstrapper.resetUiForNewInput()
        bootstrapper.reloadPrefsAndEnsureDict()

        graph.themeController.load()
        graph.themeController.apply()

        updateFloatingPreeditDisplay(null)
        refreshFloatingPreeditStyle()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        updateFloatingPreeditDisplay(null)
        removeFloatingPreedit()
    }

    override fun onDestroy() {
        removeFloatingPreedit()
        super.onDestroy()
    }

    // ── 工具 ───────────────────────────────────────────────────────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun isDarkTheme(): Boolean =
        KeyboardPrefs.loadThemeMode(this) == KeyboardPrefs.THEME_DARK

    private fun resolveTypefaceFromSpec(spec: String): Typeface {
        synchronized(typefaceCache) {
            typefaceCache[spec]?.let { return it }
            val tf = runCatching {
                if (spec.startsWith("asset:")) {
                    Typeface.createFromAsset(assets, spec.removePrefix("asset:"))
                } else {
                    Typeface.create(spec, Typeface.NORMAL)
                }
            }.getOrElse { Typeface.DEFAULT }
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
        if (isDarkTheme()) {
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
        try { wm.updateViewLayout(tv, lp) } catch (_: Throwable) {}
    }

    // ── 悬浮 Preedit：核心渲染逻辑 ────────────────────────────────────

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

    /**
     * R-P04：使用 PreeditSpanBuilder 将 PreeditDisplay 各段的 Style
     * 渲染为 SpannableString，直接设置到悬浮 TextView。
     *
     * 样式对应关系：
     *   LOCKED           → 深蓝 + 粗体（已锁定/物化音节）
     *   FOCUSED          → 深橙 + 下划线 + 粗体（当前焦点音节）
     *   NORMAL           → 默认颜色（未物化待输入音节）
     *   FALLBACK         → 灰色斜体（词库未加载时的键位占位符）
     *   COMMITTED_PREFIX → 深绿（已上屏的汉字前缀）
     *
     * 完全在原生 Android 端完成，无任何跨框架调用。
     */
    private fun updateFloatingPreeditDisplay(display: PreeditDisplay?) {
        // Idle 或空 preedit：隐藏悬浮条
        if (display == null || display.plainText.isEmpty()) {
            preeditOverlayView?.visibility = View.GONE
            preeditOverlayView?.text = ""
            return
        }

        ensureFloatingPreedit()
        val tv = preeditOverlayView ?: return
        val lp = preeditOverlayParams ?: return

        // 重新应用字体/主题（处理主题切换后样式同步）
        applyPreeditOverlayStyleFromPrefs(tv)

        // ── 核心：用 PreeditSpanBuilder 生成带高亮/下划线的 SpannableString ──
        val spannable = PreeditSpanBuilder.build(display, darkTheme = isDarkTheme())
        tv.text = spannable

        tv.visibility = View.VISIBLE

        // 定位到键盘顶部上方
        val loc = IntArray(2)
        mainView.getLocationOnScreen(loc)
        lp.y = loc[1] - dp(32)
        wm.updateViewLayout(tv, lp)
    }

    private fun removeFloatingPreedit() {
        val v = preeditOverlayView ?: return
        try { wm.removeViewImmediate(v) } catch (_: Throwable) {}
        finally {
            preeditOverlayView = null
            preeditOverlayParams = null
        }
    }
}
