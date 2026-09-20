package com.vythera.relay.ui

import android.Manifest
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.vythera.relay.RelayController
import com.vythera.relay.relay
import com.vythera.relay.service.RelayService
import com.vythera.relay.ui.onboarding.OnboardingScreen
import com.vythera.relay.ui.setup.SetupSheet
import com.vythera.relay.ui.setup.SetupStep
import com.vythera.relay.ui.setup.batterySettingsIntent
import com.vythera.relay.ui.setup.pendingSetupSteps
import com.vythera.relay.data.settings.ColorPreference
import com.vythera.relay.designsystem.theme.RelayColorSource
import com.vythera.relay.designsystem.theme.RelayTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        relay.controllerState.value?.onLocalClipboardChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val container = relay
        container.warmUp()
        installSplashScreen().setKeepOnScreenCondition { container.controllerState.value == null }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val controller by container.controllerState.collectAsStateWithLifecycle()
            controller?.let { App(it) }
        }
    }

    @androidx.compose.runtime.Composable
    private fun App(controller: RelayController) {
        val settings by controller.settings.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

        RelayTheme(colorSource = if (settings.colors == ColorPreference.RELAY) RelayColorSource.Ember else RelayColorSource.Dynamic) {
            // Provides surface colour and, crucially, the matching content colour to every screen.
            androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
            AnimatedContent(
                targetState = settings.onboardingComplete,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "onboarding",
            ) { onboarded ->
                if (onboarded) {
                    RelayRoot(controller)
                    // Asked once, right after onboarding: both of these decide whether other
                    // devices can reach this phone at all.
                    var setupSteps by remember { mutableStateOf(emptyList<SetupStep>()) }
                    LifecycleResumeEffect(settings.setupAsked) {
                        setupSteps = if (settings.setupAsked) emptyList() else pendingSetupSteps(this@MainActivity)
                        onPauseOrDispose { }
                    }
                    if (setupSteps.isNotEmpty()) {
                        SetupSheet(
                            steps = setupSteps,
                            onAllowNotifications = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                            onAllowBattery = { runCatching { startActivity(batterySettingsIntent(this@MainActivity)) } },
                            onSkip = {
                                setupSteps = emptyList()
                                scope.launch { controller.markSetupAsked() }
                            },
                        )
                    }
                } else {
                    OnboardingScreen(defaultName = relay.defaultDeviceName()) { name ->
                        scope.launch {
                            relay.settings.completeOnboarding(name)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            RelayService.start(this@MainActivity)
                        }
                    }
                }
            }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            val controller = relay.controller()
            if (controller.settings.value.onboardingComplete) RelayService.start(this@MainActivity)
            controller.refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        getSystemService(ClipboardManager::class.java)?.addPrimaryClipChangedListener(clipboardListener)
        // Instant clipboard starts from here: Android asks about log access only while
        // Relay is on screen. Also picks up permissions granted while Relay was away.
        relay.controllerState.value?.let { controller ->
            val settings = controller.settings.value
            if (settings.ecosystem && settings.instantClipboard) controller.instantClipboard.start()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        // Android only lets the focused app read the clipboard, so this is the moment
        // to notice something copied while Relay was in the background.
        val controller = relay.controllerState.value ?: return
        controller.onLocalClipboardChanged()
        // The watcher itself is started in onResume: asking again on every focus change
        // would put the log-access prompt on screen each time.
    }

    override fun onPause() {
        getSystemService(ClipboardManager::class.java)?.removePrimaryClipChangedListener(clipboardListener)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        val controller = relay.controllerState.value ?: return
        val busy = controller.transfers.value.any { !it.status.isFinished }
        if (!controller.settings.value.stayAvailable && !busy && !isChangingConfigurations) RelayService.stop(this)
    }
}
