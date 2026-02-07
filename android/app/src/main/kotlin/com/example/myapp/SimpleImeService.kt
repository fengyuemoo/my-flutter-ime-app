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
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.ImeGraph
import com.example.myapp.ime.bootstrap.ImeBootstrapper
import com.example.myapp.ime.keyboard.KeyboardHost
import com.example.myapp.ime.prefs.KeyboardPrefs
import com.example.myapp.ime.ui.ImeUi

class SimpleImeService : InputMethodService(), KeyboardHost {

    private lateinit var ui: ImeUi
    private lateinit var mainView: View
    private lateinit var bodyFrame: FrameLayout

    private lateinit var graph: ImeGraph
    private lateinit var bootstrapper: ImeBootstrapper

    private var onCandidateClick: (Candidate) -> Unit = {}

    // --- floating preedit overlay (WindowManager) ---
    private var preeditOverlayView: TextView? = null
    private var preeditOverlayParams: WindowManager.LayoutParams? = null
    private val wm: WindowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    override fun onToolbarUpdate() {
        if (this::graph.isInitialized) graph.toolbarController.refresh()
    }

    override fun onClearComposingRequested() {
        if (this::graph.isInitialized) graph.dispatcher.clearComposing()
    }

    override fun onCreateInputView(): View {
        ui = ImeUi()
        mainView = ui.inflate(layoutInflater) { cand -> onCandidateClick(cand) }
        bodyFrame = ui.bodyFrame

        // 预览文本从 UI 统一回调到这里，交给浮层展示
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

        onCandidateClick = { cand -> graph.candidateController.commitCandidate(cand) }

        graph.uiBinder.bind()

        bootstrapper = ImeBootstrapper(graph)
        bootstrapper.initFromPrefsAndEnsureDict()

        bindToolbarButtons()

        graph.themeController.load()
        graph.themeController.apply()

        // 初始隐藏
        updateFloatingPreedit(null)

        return mainView
    }

    private fun bindToolbarButtons() {
        ui.getThemeButton().setOnClickListener {
            if (this::graph.isInitialized) {
                graph.themeController.toggle()
                // NEW: 主题切换后让悬浮 preedit 也立即同步
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
        // NEW: 确保新的输入场景下浮层样式也跟当前主题一致（即使浮层未显示也无害）
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

    private fun applyPreeditOverlayFontFromPrefs(tv: TextView) {
        val family = KeyboardPrefs.loadFontFamily(this)
        val scale = KeyboardPrefs.loadFontScale(this).coerceIn(0.7f, 1.4f)

        tv.typeface = Typeface.create(family, Typeface.NORMAL)
        // base=15sp（之前写死 15f 的等价意图），再乘缩放
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

            // NEW: 创建时就同步字体/字号 + 主题
            applyPreeditOverlayStyleFromPrefs(this)
        }

        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT

            // 这个 type 不需要“悬浮窗权限”，并且可以挂到 IME 自己的 token 上
            type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            this.token = token

            // 关键：不抢焦点、不拦截触摸，同时允许在 IME 之上显示
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

        // NEW: 每次更新时都同步一次（刚改完字体/字号/主题立刻生效）
        applyPreeditOverlayStyleFromPrefs(tv)

        tv.text = text
        tv.visibility = View.VISIBLE

        // 把浮层放到“键盘 top 再往上抬一点”
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
