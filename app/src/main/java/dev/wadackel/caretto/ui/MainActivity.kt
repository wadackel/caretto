package dev.wadackel.caretto.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.wadackel.caretto.core.KeyBindings
import dev.wadackel.caretto.service.CarettoService
import dev.wadackel.caretto.ui.main.CarettoApp
import dev.wadackel.caretto.ui.main.SettingsViewModel
import dev.wadackel.caretto.ui.theme.CarettoTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        KeyBindings.seedIfUnset(this)

        setContent {
            CarettoTheme {
                val lifecycleOwner = LocalLifecycleOwner.current
                // Neither the bound-service flag nor the event log can be observed, so they are the
                // one thing still on a timer while the screen is in front of the user.
                LaunchedEffect(lifecycleOwner) {
                    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        while (true) {
                            viewModel.refreshStatus()
                            viewModel.refreshLog()
                            delay(500)
                        }
                    }
                }

                CarettoApp(
                    viewModel = viewModel,
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onOpenTestEditor = {
                        startActivity(Intent(this, TestEditorActivity::class.java))
                    },
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Last line of defence, though a request now clears itself after one chord and expires
        // on its own, so a leak costs a single keystroke rather than the keyboard.
        CarettoService.endCapture()
    }
}
