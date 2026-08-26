package helium314.keyboard.latin.dictionary

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.measureRepeated
import com.android.inputmethod.latin.Suggest
import com.android.inputmethod.latin.SuggestedWords
import com.android.inputmethod.latin.dictionary.Dictionary
import com.android.inputmethod.latin.dictionary.DictionaryCollection
import com.android.inputmethod.latin.dictionary.DictionaryFactory
import com.android.inputmethod.latin.dictionary.ReadOnlyBinaryDictionary
import com.android.inputmethod.latin.common.LocaleUtils
import com.android.inputmethod.latin.utils.DictionaryInfoUtils
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Benchmarks for suggestion latency measurement.
 * Run with: ./gradlew app:connectedBenchmarkAndroidTest
 */
class SuggestionLatencyBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private lateinit var dictionaryCollection: DictionaryCollection
    private lateinit var suggest: Suggest

    @Test
    fun setup() {
        // This runs once before benchmarks
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        
        // Create dictionary collection for en_US
        val locale = Locale.US
        dictionaryCollection = DictionaryFactory.createMainDictionaryCollection(
            context, locale, useEmojiDict = false
        )
        
        // Initialize Suggest with the dictionary
        suggest = Suggest(
            context,
            DictionaryInfoUtils.getCacheDirectoryForLocale(locale, context) ?: return,
            dictionaryCollection,
            locale,
            null
        )
    }

    @Test
    fun benchmark_getSuggestions_shortWord() {
        val typedWord = "hel"
        val codePoints = typedWord.toIntArray()
        
        benchmarkRule.measureRepeated {
            val suggestedWords = SuggestedWords()
            suggest.getSuggestions(
                codePoints,
                codePoints.size,
                0,
                0,
                null,
                0,
                suggestedWords,
                0,
                Dictionary.TYPE_MAIN,
                null
            )
        }
    }

    @Test
    fun benchmark_getSuggestions_mediumWord() {
        val typedWord = "hello"
        val codePoints = typedWord.toIntArray()
        
        benchmarkRule.measureRepeated {
            val suggestedWords = SuggestedWords()
            suggest.getSuggestions(
                codePoints,
                codePoints.size,
                0,
                0,
                null,
                0,
                suggestedWords,
                0,
                Dictionary.TYPE_MAIN,
                null
            )
        }
    }

    @Test
    fun benchmark_getSuggestions_longWord() {
        val typedWord = "keyboard"
        val codePoints = typedWord.toIntArray()
        
        benchmarkRule.measureRepeated {
            val suggestedWords = SuggestedWords()
            suggest.getSuggestions(
                codePoints,
                codePoints.size,
                0,
                0,
                null,
                0,
                suggestedWords,
                0,
                Dictionary.TYPE_MAIN,
                null
            )
        }
    }

    @Test
    fun benchmark_getSuggestions_withNgrams() {
        val typedWord = "the"
        val codePoints = typedWord.toIntArray()
        val prevWords = intArrayOf('t', 'h', 'e')
        
        benchmarkRule.measureRepeated {
            val suggestedWords = SuggestedWords()
            suggest.getSuggestions(
                codePoints,
                codePoints.size,
                0,
                0,
                prevWords,
                prevWords.size,
                suggestedWords,
                0,
                Dictionary.TYPE_MAIN,
                null
            )
        }
    }

    @Test
    fun benchmark_dictionaryLookup_only() {
        val typedWord = "hello"
        val codePoints = typedWord.toIntArray()
        
        // Direct dictionary lookup without Suggest overhead
        benchmarkRule.measureRepeated {
            dictionaryCollection.mainDicts.forEach { dict ->
                val outWords = SuggestedWords()
                dict.getWords(
                    codePoints,
                    codePoints.size,
                    0,
                    0,
                    outWords,
                    0,
                    null
                )
            }
        }
    }
}