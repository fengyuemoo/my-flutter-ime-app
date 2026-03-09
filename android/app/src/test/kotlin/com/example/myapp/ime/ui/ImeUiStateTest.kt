package com.example.myapp.ime.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.example.myapp.R
import com.example.myapp.ime.compose.cn.t9.CnT9PreeditModel
import com.example.myapp.ime.compose.cn.t9.CnT9PreeditSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImeUiStateTest {

    private lateinit var ui: ImeUi

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        ui = ImeUi()
        ui.inflateWithIndex(LayoutInflater.from(context)) { }
    }

    @Test
    fun showIdleState_hides_preedit_strip_and_candidate_strip() {
        ui.setCnT9Preedit(
            CnT9PreeditModel(
                text = "zhong'guo",
                coreText = "zhong'guo",
                segments = listOf(
                    CnT9PreeditSegment(text = "zhong", isFocused = true, isLocked = true),
                    CnT9PreeditSegment(text = "guo", isFocused = false, isLocked = false)
                ),
                focusedSegmentIndex = 0,
                enterCommitText = "zhong'guo"
            )
        )
        ui.showComposingState(isExpanded = false)

        val preedit = ui.rootView.findViewById<HorizontalScrollView>(R.id.cnt9preeditscroll)
        val candidateStrip = ui.rootView.findViewById<LinearLayout>(R.id.candidatestrip)
        val toolbar = ui.rootView.findViewById<LinearLayout>(R.id.toolbarcontainer)

        assertEquals(View.VISIBLE, preedit.visibility)
        assertEquals(View.VISIBLE, candidateStrip.visibility)
        assertEquals(View.GONE, toolbar.visibility)

        ui.showIdleState()

        assertEquals(View.GONE, preedit.visibility)
        assertEquals(View.GONE, candidateStrip.visibility)
        assertEquals(View.VISIBLE, toolbar.visibility)
        assertEquals(0, ui.getSelectedCandidateIndex())
    }

    @Test
    fun setExpanded_false_when_not_composing_clears_transient_ui() {
        ui.setCnT9Preedit(
            CnT9PreeditModel(
                text = "ni'hao",
                coreText = "ni'hao",
                segments = listOf(
                    CnT9PreeditSegment(text = "ni", isFocused = true, isLocked = true),
                    CnT9PreeditSegment(text = "hao", isFocused = false, isLocked = false)
                ),
                focusedSegmentIndex = 0,
                enterCommitText = "ni'hao"
            )
        )
        ui.showComposingState(isExpanded = true)
        ui.setExpanded(expanded = false, isComposing = false)

        val preedit = ui.rootView.findViewById<HorizontalScrollView>(R.id.cnt9preeditscroll)
        val candidateStrip = ui.rootView.findViewById<LinearLayout>(R.id.candidatestrip)
        val expandedPanel = ui.rootView.findViewById<LinearLayout>(R.id.expandedcandidatespanel)
        val toolbar = ui.rootView.findViewById<LinearLayout>(R.id.toolbarcontainer)

        assertEquals(View.GONE, preedit.visibility)
        assertEquals(View.GONE, candidateStrip.visibility)
        assertEquals(View.GONE, expandedPanel.visibility)
        assertEquals(View.VISIBLE, toolbar.visibility)
        assertEquals(0, ui.getSelectedCandidateIndex())
    }

    @Test
    fun preedit_listener_receives_null_after_idle_cleanup() {
        var latestModel: CnT9PreeditModel? = CnT9PreeditModel(
            text = "placeholder",
            coreText = "placeholder",
            segments = emptyList(),
            focusedSegmentIndex = null,
            enterCommitText = "placeholder"
        )

        ui.setCnT9PreeditListener { latestModel = it }
        ui.setCnT9Preedit(
            CnT9PreeditModel(
                text = "lv'xing",
                coreText = "lü'xing",
                segments = listOf(
                    CnT9PreeditSegment(text = "lü", isFocused = true, isLocked = false),
                    CnT9PreeditSegment(text = "xing", isFocused = false, isLocked = false)
                ),
                focusedSegmentIndex = 0,
                enterCommitText = "lv'xing"
            )
        )

        assertNotNull(latestModel)

        ui.showIdleState()

        assertEquals(null, latestModel)
    }
}
