package helium314.keyboard.keyboard

import android.content.Context
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.measureRepeated
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.runOnIdle
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test

/**
 * Benchmarks for Compose-based keyboard UI components.
 * Measures KlipyTabBar rendering and interaction performance.
 */
class ComposeKeyboardBenchmark {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context

    @Test
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
    }

    @Test
    fun benchmark_klipyTabBar_initialComposition() {
        val colors = helium314.keyboard.latin.Settings.getValues().mColors
        
        benchmarkRule.measureRepeated {
            composeRule.setContent {
                KlipyTabBar(
                    selectedTab = helium314.keyboard.latin.database.KlipyHistoryDao.TYPE_GIF,
                    onTabSelected = {},
                    colors = colors,
                    isNight = false,
                    customFontFamily = helium314.keyboard.keyboard.KeyboardTypeface.customFontFamily()
                )
            }
            composeRule.runOnIdle {}
        }
    }

    @Test
    fun benchmark_klipyTabBar_recomposition() {
        val colors = helium314.keyboard.latin.Settings.getValues().mColors
        var selectedTab = helium314.keyboard.latin.database.KlipyHistoryDao.TYPE_GIF
        
        composeRule.setContent {
            KlipyTabBar(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                colors = colors,
                isNight = false,
                customFontFamily = helium314.keyboard.keyboard.KeyboardTypeface.customFontFamily()
            )
        }
        composeRule.runOnIdle {}
        
        benchmarkRule.measureRepeated {
            // Switch tabs to trigger recomposition
            selectedTab = if (selectedTab == helium314.keyboard.latin.database.KlipyHistoryDao.TYPE_GIF)
                helium314.keyboard.latin.database.KlipyHistoryDao.TYPE_STICKER
            else
                helium314.keyboard.latin.database.KlipyHistoryDao.TYPE_GIF
            
            composeRule.setContent {
                KlipyTabBar(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    colors = colors,
                    isNight = false,
                    customFontFamily = helium314.keyboard.keyboard.KeyboardTypeface.customFontFamily()
                )
            }
            composeRule.runOnIdle {}
        }
    }

    @Test
    fun benchmark_klipyTabBar_themeSwitch() {
        val colors = helium314.keyboard.latin.Settings.getValues().mColors
        var isNight = false
        
        composeRule.setContent {
            KlipyTabBar(
                selectedTab = helium314.keyboard.latin.database.KlipyHistoryDao.TYPE_GIF,
                onTabSelected = {},
                colors = colors,
                isNight = isNight,
                customFontFamily = helium314.keyboard.keyboard.KeyboardTypeface.customFontFamily()
            )
        }
        composeRule.runOnIdle {}
        
        benchmarkRule.measureRepeated {
            // Toggle theme to trigger recomposition
            isNight = !isNight
            
            composeRule.setContent {
                KlipyTabBar(
                    selectedTab = helium314.keyboard.latin.database.KlipyHistoryDao.TYPE_GIF,
                    onTabSelected = {},
                    colors = colors,
                    isNight = isNight,
                    customFontFamily = helium314.keyboard.keyboard.KeyboardTypeface.customFontFamily()
                )
            }
            composeRule.runOnIdle {}
        }
    }
}