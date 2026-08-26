package helium314.keyboard.keyboard

import android.content.Context
import android.view.View
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.measureRepeated
import androidx.core.view.isAttachedToWindow
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.android.inputmethod.latin.RichInputMethodManager
import com.android.inputmethod.latin.SubtypeSettings
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import org.junit.Rule
import org.junit.Test
import kotlin.coroutines.resume

/**
 * Frame time / rendering benchmarks for keyboard UI.
 * Measures keyboard rendering, layout inflation, and UI responsiveness.
 */
class KeyboardFrameTimeBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private lateinit var context: Context

    @Test
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        
        // Initialize keyboard subsystems
        RichInputMethodManager.init(context)
        SubtypeSettings.init(context)
    }

    @Test
    fun benchmark_keyboardLayoutInflation() {
        val latinIME = createLatinIME()
        
        benchmarkRule.measureRepeated {
            latinIME.onCreateInputView()
            val inputView = latinIME.inputView
            if (inputView != null) {
                inputView.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY)
                )
                inputView.layout(0, 0, 1080, 500)
            }
        }
    }

    @Test
    fun benchmark_keyboardLayoutInflation_withTheme() {
        val latinIME = createLatinIME()
        
        benchmarkRule.measureRepeated {
            latinIME.onCreateInputView()
            val inputView = latinIME.inputView
            if (inputView != null) {
                inputView.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY)
                )
                inputView.layout(0, 0, 1080, 500)
                // Force draw
                inputView.draw(android.graphics.Canvas(android.graphics.Picture().beginRecording(1080, 500)))
            }
        }
    }

    @Test
    fun benchmark_keySwitching() {
        val latinIME = createLatinIME()
        latinIME.onCreateInputView()
        
        val subtypes = SubtypeSettings.getEnabledSubtypes(true)
        if (subtypes.size < 2) return
        
        benchmarkRule.measureRepeated {
            val richImm = RichInputMethodManager.getInstance()
            subtypes.forEach { subtype ->
                richImm.switchToSubtype(subtype)
            }
        }
    }

    @Test
    fun benchmark_popupKeyboardInflation() {
        val latinIME = createLatinIME()
        latinIME.onCreateInputView()
        
        benchmarkRule.measureRepeated {
            // Simulate long press to show popup
            val inputView = latinIME.inputView
            if (inputView != null) {
                val key = inputView.findViewById<View>(R.id.key_a)
                if (key != null) {
                    key.performLongClick()
                }
            }
        }
    }

    @Test
    fun benchmark_gestureTrailDrawing() {
        val latinIME = createLatinIME()
        latinIME.onCreateInputView()
        
        benchmarkRule.measureRepeated {
            // Simulate gesture trail
            val inputView = latinIME.inputView
            if (inputView != null) {
                // Gesture trail drawing is done in onDraw
                val canvas = android.graphics.Canvas(android.graphics.Picture().beginRecording(1080, 500))
                inputView.draw(canvas)
            }
        }
    }

    @Test
    fun benchmark_suggestionStripInflation() {
        val latinIME = createLatinIME()
        latinIME.onCreateInputView()
        
        benchmarkRule.measureRepeated {
            // Force suggestion strip to show
            val inputView = latinIME.inputView
            if (inputView != null) {
                val suggestionStrip = inputView.findViewById<View>(R.id.suggestion_strip)
                if (suggestionStrip != null) {
                    suggestionStrip.visibility = View.VISIBLE
                    suggestionStrip.measure(
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
                    )
                    suggestionStrip.layout(0, 0, 1080, 100)
                }
            }
        }
    }

    private fun createLatinIME(): LatinIME {
        val service = LatinIME()
        service.attachBaseContext(context)
        service.onCreate()
        return service
    }
}