package app.autospace.ui

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.autospace.AppSettings
import app.autospace.BuildConfig
import app.autospace.CertProvider
import app.autospace.car.TestMode
import app.autospace.stats.UsageStats

private enum class Screen { INTRO, HOME, LOGS, SETTINGS, CAR_PREVIEW, CAR_PREVIEW_SETTINGS, CERT_SETUP, SETUP_GUIDE, USAGE_NOTES, HELP, WHATS_NEW, RECOMMENDED_APPS }

/** Three screens and a back stack of depth one: no navigation library needed. */
@Composable
fun GearslipApp(onDisconnect: () -> Unit, onRequestCallScreening: () -> Unit) {
    val context = LocalContext.current
    val needsCertSetup = remember {
        !AppSettings.hasSkippedCertSetup(context) && !CertProvider.hasAnyCert(context)
    }
    val needsSetupGuide = remember { !AppSettings.hasSeenPermissionsSetup(context) }
    // Where launch goes once the intro (if any) is done.
    val afterIntro = remember {
        when {
            needsCertSetup -> Screen.CERT_SETUP
            needsSetupGuide -> Screen.SETUP_GUIDE
            // Set up before usage notes existed: ask once, on the setup guide's own page.
            !UsageStats.asked(context) -> Screen.USAGE_NOTES
            else -> Screen.HOME
        }
    }
    var screen by rememberSaveable { mutableStateOf(if (Intro.due(context)) Screen.INTRO else afterIntro) }
    // Test mode banner state: collected here (above the screen
    // switch) so the banner renders on every phone screen while on.
    val testMode by TestMode.active.collectAsState()
    // A replay from About goes back to Settings; the launch one carries on into setup.
    var introReturn by rememberSaveable { mutableStateOf(afterIntro) }
    // Home's "it worked" card opens Settings at usage notes rather than at the top.
    var settingsAtUsageNotes by rememberSaveable { mutableStateOf(false) }
    // What's new and Connection help open from Home and from Settings; back returns to whichever.
    var backTo by rememberSaveable { mutableStateOf(Screen.SETTINGS) }
    BackHandler(enabled = screen != Screen.HOME && screen != Screen.INTRO) {
        screen = when (screen) {
            Screen.RECOMMENDED_APPS, Screen.LOGS, Screen.CAR_PREVIEW, Screen.CAR_PREVIEW_SETTINGS -> Screen.SETTINGS
            Screen.HELP, Screen.WHATS_NEW -> backTo
            else -> Screen.HOME
        }
    }

    // Home stays upright; every other screen turns with the phone.
    LaunchedEffect(screen) {
        (context as? Activity)?.requestedOrientation = if (screen == Screen.HOME) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // The guide asks for this now, but anyone who finished it before that never saw the page.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    // Reads the certificate once in the background, so Settings has it ready when opened.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { CertSummary.read(context) } }
    }
    LaunchedEffect(Unit) {
        if (!needsSetupGuide &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .consumeWindowInsets(WindowInsets.statusBars),
    ) {
        if (BuildConfig.DEBUG && screen != Screen.CAR_PREVIEW && screen != Screen.INTRO) DebugBuildBanner()
        // Test mode banner: always visible on every phone screen
        // while test mode is on — including the preview itself, so a
        // simulated session can never pass for a real one at a glance.
        // (The car screen is a separate render target and never shows
        // phone banners; the hotword loop logs test mode instead.)
        if (testMode) TestModeBanner()

        Column(Modifier.weight(1f)) {
            when (screen) {
                Screen.INTRO -> IntroScreen(onDone = { screen = introReturn })
                Screen.HOME -> HomeScreen(
                    onOpenSettings = { settingsAtUsageNotes = false; screen = Screen.SETTINGS },
                    onOpenWhatsNew = { backTo = Screen.HOME; screen = Screen.WHATS_NEW },
                    onOpenHelp = { backTo = Screen.HOME; screen = Screen.HELP },
                    onOpenUsageNotes = { settingsAtUsageNotes = true; screen = Screen.SETTINGS },
                    onDisconnect = onDisconnect,
                )
                Screen.WHATS_NEW -> WhatsNewScreen(onBack = { screen = backTo })
                Screen.LOGS -> LogsScreen(onBack = { screen = Screen.SETTINGS })
                Screen.SETTINGS -> SettingsScreen(
                    onBack = { screen = Screen.HOME },
                    onOpenRecommendedApps = { settingsAtUsageNotes = false; screen = Screen.RECOMMENDED_APPS },
                    onOpenWhatsNew = { settingsAtUsageNotes = false; backTo = Screen.SETTINGS; screen = Screen.WHATS_NEW },
                    onOpenHelp = { settingsAtUsageNotes = false; backTo = Screen.SETTINGS; screen = Screen.HELP },
                    onOpenCarPreview = { settingsAtUsageNotes = false; screen = Screen.CAR_PREVIEW },
                    onOpenCarPreviewSettings = { settingsAtUsageNotes = false; screen = Screen.CAR_PREVIEW_SETTINGS },
                    onOpenLogs = { settingsAtUsageNotes = false; screen = Screen.LOGS },
                    onReplayTutorial = { screen = Screen.SETUP_GUIDE },
                    onReplayIntro = { Intro.replay(context); introReturn = Screen.SETTINGS; screen = Screen.INTRO },
                    atUsageNotes = settingsAtUsageNotes,
                )
                Screen.RECOMMENDED_APPS -> RecommendedAppsScreen(onBack = { screen = Screen.SETTINGS })
                Screen.CAR_PREVIEW -> CarPreviewScreen(
                    onBack = { screen = Screen.SETTINGS },
                    onOpenPreviewSettings = { screen = Screen.CAR_PREVIEW_SETTINGS },
                )
                Screen.CAR_PREVIEW_SETTINGS -> PreviewSettingsScreen(
                    onBack = { screen = Screen.SETTINGS },
                    onOpenCarPreview = { screen = Screen.CAR_PREVIEW },
                )
                Screen.HELP -> HelpScreen(
                    onBack = { screen = backTo },
                    onRequestCallScreening = onRequestCallScreening,
                )
                Screen.CERT_SETUP -> CertSetupScreen(
                    onDone = {
                        screen = if (AppSettings.hasSeenPermissionsSetup(context)) Screen.HOME else Screen.SETUP_GUIDE
                    },
                )
                Screen.SETUP_GUIDE -> SetupGuideScreen(
                    onRequestCallScreening = onRequestCallScreening,
                    onDone = { screen = Screen.HOME },
                )
                Screen.USAGE_NOTES -> SetupGuideScreen(
                    onRequestCallScreening = onRequestCallScreening,
                    onDone = { screen = Screen.HOME },
                    onlyUsageNotes = true,
                )
            }
        }
        // A car keyboard is open: offer the phone's own, for a passenger.
        if (screen != Screen.CAR_PREVIEW) PhoneTypingBar()
    }
}

/**
 * Debug builds only ([BuildConfig.DEBUG]); a release build never composes this. Sits above every
 * phone screen, below the status bar - not the car screen, which is a separate render target
 * ([app.autospace.car.CarUi] carries its own corner badge) - so a debug install is
 * never mistaken for a release one.
 */
@Composable
/**
 * Test mode banner (Robert, 2026-10-25): always visible on every
 * phone screen while test mode is on, in a colour nothing else
 * uses, so a simulated car session can never pass for a real one
 * at a glance. It is deliberately NOT debug-only — test mode itself
 * is available in any build, so its banner must be too. Same size
 * and placement as the debug banner, which it sits below when both
 * are on.
 */
private fun TestModeBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth().height(28.dp),
        color = Color(0xFF7C4DFF),
        contentColor = Color.White,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "TEST MODE — simulated car session (not a real car)",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun DebugBuildBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth().height(28.dp),
        color = Color(0xFFF5C518),
        contentColor = Color.Black,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "DEBUG BUILD",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
