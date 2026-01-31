package com.example.myapp.keyboard.core

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.example.myapp.R
import com.example.myapp.ime.api.ImeActions

abstract class BaseKeyboard(
    protected val context: Context,
    protected val ime: ImeActions,
    layoutResId: Int
) : IKeyboardMode {

    protected val rootView: View = LayoutInflater.from(context).inflate(layoutResId, null)

    private val deleteHandler = Handler(Looper.getMainLooper())
    private val deleteRunnable = object : Runnable {
        override fun run() {
            ime.handleBackspace()
            deleteHandler.postDelayed(this, 50)
        }
    }

    init {
        bindClickEvents(rootView)
    }

    override fun getView(): View = rootView

    override fun onActivate() {}

    protected open fun bindClickEvents(view: View) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) bindClickEvents(view.getChildAt(i))
        } else if (view is Button) {
            if (isDeleteKey(view.id)) {
                view.setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            ime.handleBackspace()
                            deleteHandler.postDelayed(deleteRunnable, 400)
                            v.isPressed = true
                            true
                        }

                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            deleteHandler.removeCallbacks(deleteRunnable)
                            v.isPressed = false
                            true
                        }

                        else -> false
                    }
                }
            } else {
                view.setOnClickListener { handleKeyPress(view) }
            }
        }
    }

    /**
     * Delete(Backspace) key ids.
     *
     * 你当前工程的布局 id 是“无下划线”风格：
     * - Qwerty: btndelqwerty
     * - T9:     t9btndel
     * - Numeric:t9numdel
     */
    protected open fun isDeleteKey(id: Int): Boolean {
        return (id == R.id.btndelqwerty) ||
            (id == R.id.t9btndel) ||
            (id == R.id.t9numdel)
    }

    protected abstract fun handleKeyPress(button: Button)

    override fun applyTheme(themeMode: Int) {
        val bgLight = Color.parseColor("#DDDDDD")
        val bgDark = Color.parseColor("#222222")
        val keyBgLight = Color.WHITE
        val keyBgDark = Color.parseColor("#444444")
        val textLight = Color.BLACK
        val textDark = Color.WHITE

        fun setThemeRecursive(view: View) {
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) setThemeRecursive(view.getChildAt(i))
            } else if (view is Button) {
                if (shouldSkipTheme(view)) return
                val targetColor = if (themeMode == 1) keyBgDark else keyBgLight
                view.backgroundTintList = ColorStateList.valueOf(targetColor)
                view.setTextColor(if (themeMode == 1) textDark else textLight)
            }
        }

        setThemeRecursive(rootView)
        rootView.setBackgroundColor(if (themeMode == 1) bgDark else bgLight)
    }

    protected open fun shouldSkipTheme(view: View): Boolean = false
}
