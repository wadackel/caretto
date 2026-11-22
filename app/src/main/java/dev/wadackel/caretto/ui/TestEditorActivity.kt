package dev.wadackel.caretto.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.wadackel.caretto.ui.testeditor.TestEditorScreen
import dev.wadackel.caretto.ui.theme.CarettoTheme

/** On-device playground for the checks: ASCII, Japanese composition, emoji, selection. */
class TestEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CarettoTheme {
                TestEditorScreen(onBack = { finish() })
            }
        }
    }
}
