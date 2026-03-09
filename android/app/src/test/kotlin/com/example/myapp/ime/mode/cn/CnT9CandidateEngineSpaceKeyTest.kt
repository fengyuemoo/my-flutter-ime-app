package com.example.myapp.ime.mode.cn

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.example.myapp.dict.api.Dictionary
import com.example.myapp.dict.model.Candidate
import com.example.myapp.ime.compose.common.ComposingSession
import com.example.myapp.ime.keyboard.KeyboardController
import com.example.myapp.ime.keyboard.KeyboardHost
import com.example.myapp.ime.ui.ImeUi
import com.example.myapp.keyboard.core.IKeyboardMode
import com.example.myapp.keyboard.core.KeyboardRegistry
import com.example.myapp.keyboard.core.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CnT9CandidateEngineSpaceKeyTest {

    private lateinit var context: Context
    private lateinit var ui: ImeUi
    private lateinit var keyboardController: KeyboardController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        ui = ImeUi().apply {
            inflateWithIndex(LayoutInflater.from(context)) { }
        }

        val host = object : KeyboardHost {
            override fun onToolbarUpdate() = Unit
            override fun onClearComposingRequested() = Unit
        }

        val dummyKeyboard = object : IKeyboardMode {
            private val view = View(context)
            override fun getView(): View = view
            override fun onActivate() = Unit
            override fun applyTheme(themeMode: Int) = Unit
        }

        val registry = object : KeyboardRegistry {
            override fun get(type: KeyboardType): IKeyboardMode = dummyKeyboard
        }

        keyboardController = KeyboardController(
            bodyFrame = FrameLayout(context),
            host = host,
            registry = registry
        )

        keyboardController.setMainMode(isChinese = true, useT9Layout = true)
    }

    @Test
    fun handleSpaceKey_without_candidates_commits_plain_space() {
        val session = ComposingSession()
        val commits = mutableListOf<String>()
        var clearCalls = 0

        val engine = CnT9CandidateEngine(
            ui = ui,
            keyboardController = keyboardController,
            dictEngine = FakeDictionary(),
            session = session,
            commitRaw = { commits += it },
            clearComposing = {
                clearCalls++
                session.clear()
            },
            isRawCommitMode = { false }
        )

        engine.handleSpaceKey()

        assertEquals(listOf(" "), commits)
        assertEquals(0, clearCalls)
    }

    @Test
    fun handleSpaceKey_with_preferred_candidate_commits_candidate_and_clears() {
        val session = ComposingSession().apply {
            appendT9Digit("64")
        }

        val candidate = Candidate(
            word = "你",
            input = "ni",
            priority = 10,
            matchedLength = 2,
            pinyinCount = 1,
            pinyin = "ni",
            syllables = 1
        )

        val dict = FakeDictionary(
            pinyinByDigits = mapOf("64" to listOf("ni")),
            suggestionsByStack = mapOf(listOf("ni") to listOf(candidate)),
            suggestionsByInput = mapOf("ni" to listOf(candidate))
        )

        val commits = mutableListOf<String>()
        var clearCalls = 0

        val engine = CnT9CandidateEngine(
            ui = ui,
            keyboardController = keyboardController,
            dictEngine = dict,
            session = session,
            commitRaw = { commits += it },
            clearComposing = {
                clearCalls++
                session.clear()
            },
            isRawCommitMode = { false }
        )

        engine.updateCandidates()
        engine.handleSpaceKey()

        assertEquals(listOf("你"), commits)
        assertEquals(1, clearCalls)
        assertFalse(session.isComposing())
    }

    private class FakeDictionary(
        private var ready: Boolean = true,
        private val pinyinByDigits: Map<String, List<String>> = emptyMap(),
        private val suggestionsByStack: Map<List<String>, List<Candidate>> = emptyMap(),
        private val suggestionsByInput: Map<String, List<Candidate>> = emptyMap()
    ) : Dictionary {

        override val isLoaded: Boolean
            get() = ready

        override val debugInfo: String?
            get() = null

        override fun setReady(ready: Boolean, info: String?) {
            this.ready = ready
        }

        override fun getPinyinPossibilities(digits: String): List<String> {
            return pinyinByDigits[digits] ?: emptyList()
        }

        override fun getSuggestionsFromPinyinStack(
            pinyinStack: List<String>,
            rawDigits: String
        ): List<Candidate> {
            return suggestionsByStack[pinyinStack] ?: emptyList()
        }

        override fun getSuggestions(
            input: String,
            isT9: Boolean,
            isChineseMode: Boolean
        ): List<Candidate> {
            return suggestionsByInput[input] ?: emptyList()
        }
    }
}
