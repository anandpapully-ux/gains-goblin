package com.healthvectors.fitness

import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.healthvectors.fitness.core.designsystem.ChoiceRow
import com.healthvectors.fitness.core.designsystem.NumField
import com.healthvectors.fitness.domain.model.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** UI tests for the building blocks of fast logging (no Hilt needed). */
class ComponentsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun choiceRowSelectsOption() {
        var picked = UnitSystem.METRIC
        rule.setContent {
            var s by remember { mutableStateOf(picked) }
            ChoiceRow(UnitSystem.entries, s, { if (it == UnitSystem.METRIC) "kg" else "lb" }) { s = it; picked = it }
        }
        rule.onNodeWithText("lb").performClick()
        assertEquals(UnitSystem.IMPERIAL, picked)
    }

    @Test fun numFieldRejectsLettersAndAcceptsDecimals() {
        var value = ""
        rule.setContent { var v by remember { mutableStateOf("") }; NumField(v, { v = it; value = it }, "Weight") }
        rule.onNodeWithText("Weight").performTextInput("abc")
        assertEquals("", value)
        rule.onNodeWithText("Weight").performTextInput("62.5")
        assertEquals("62.5", value)
        rule.onNodeWithText("Weight").assertIsDisplayed()
    }
}
