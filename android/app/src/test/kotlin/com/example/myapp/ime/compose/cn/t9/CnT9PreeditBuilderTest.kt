package com.example.myapp.ime.compose.cn.t9

import com.example.myapp.ime.compose.common.ComposingSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CnT9PreeditBuilderTest {

    private val builder = CnT9PreeditBuilder()

    @Test
    fun merge_locked_prefix_with_planned_suffix_by_real_text_prefix() {
        val session = ComposingSession().apply {
            onPinyinSidebarClick("zhong", "94664")
        }

        val model = builder.build(
            session = session,
            composingPreviewOverride = "zhong'guo",
            enterCommitOverride = null,
            focusedSegmentIndex = 0
        )

        assertEquals("zhong'guo", model.coreText)
        assertEquals("zhong'guo", model.enterCommitText)
        assertEquals(listOf("zhong", "guo"), model.segments.map { it.text })

        assertTrue(model.segments[0].isLocked)
        assertTrue(model.segments[0].isFocused)
        assertFalse(model.segments[1].isLocked)
        assertFalse(model.segments[1].isFocused)
    }

    @Test
    fun normalize_apostrophe_for_display_and_enter_commit() {
        val session = ComposingSession()

        val model = builder.build(
            session = session,
            composingPreviewOverride = "ZHONG’GUO",
            enterCommitOverride = "ZHONG’GUO",
            focusedSegmentIndex = null
        )

        assertEquals("zhong'guo", model.coreText)
        assertEquals("zhong'guo", model.enterCommitText)
        assertEquals(listOf("zhong", "guo"), model.segments.map { it.text })
    }

    @Test
    fun normalize_v_and_u_umlaut_consistently() {
        val session = ComposingSession()

        val model = builder.build(
            session = session,
            composingPreviewOverride = "nv",
            enterCommitOverride = "nv",
            focusedSegmentIndex = 0
        )

        assertEquals("nü", model.coreText)
        assertEquals(listOf("nü"), model.segments.map { it.text })
        assertEquals("nv", model.enterCommitText)
        assertEquals(0, model.focusedSegmentIndex)
        assertTrue(model.segments[0].isFocused)
    }

    @Test
    fun enter_commit_prefers_merged_core_over_external_override() {
        val session = ComposingSession().apply {
            onPinyinSidebarClick("zhong", "94664")
        }

        val model = builder.build(
            session = session,
            composingPreviewOverride = "zhong'guo",
            enterCommitOverride = "zhong",
            focusedSegmentIndex = 0
        )

        assertEquals("zhong'guo", model.coreText)
        assertEquals("zhong'guo", model.enterCommitText)
        assertNotNull(model.text)
    }

    @Test
    fun enter_commit_falls_back_to_normalized_enter_override_when_core_missing() {
        val session = ComposingSession()

        val model = builder.build(
            session = session,
            composingPreviewOverride = null,
            enterCommitOverride = "LVE’XING",
            focusedSegmentIndex = null
        )

        assertEquals(null, model.coreText)
        assertEquals("lve'xing", model.enterCommitText)
        assertTrue(model.segments.isEmpty())
    }
}
