package com.mootmaker.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GreetingRobolectricTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun greetingIsDisplayed() {
        composeRule.setContent { Greeting() }
        composeRule.onNodeWithText("Mootmaker").assertIsDisplayed()
    }

    @Test
    fun greetingScreenshot() {
        composeRule.setContent { Greeting() }
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/greeting.png")
    }
}
