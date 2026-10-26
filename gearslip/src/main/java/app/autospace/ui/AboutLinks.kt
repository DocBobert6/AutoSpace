package app.autospace.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext

/** Where AutoSpace lives outside the app: the code, and the project it forked from. */
internal object GearslipLinks {
    const val BUY_ME_A_COFFEE = "https://buymeacoffee.com/seb3thehacker"
    const val LIBERAPAY = "https://liberapay.com/Seb3thehacker"
    /** A card payment page for giving once, any amount. Its row stays hidden while this is empty. */
    const val PAYMENT_LINK = ""
    /** Gearslip's Monero address. Its row stays hidden while this is empty. */
    const val MONERO = "44gTVxQJY3cirBJ41J5i1bPUVnSkTMhn9esSp1kg9MHwJ3TUz8tkkigUTKwiok5zA5RZvNxyM4pqkAfPs79pPLfaNG21pCn"
    const val MATRIX = "https://matrix.to/#/!mf0_mgBd5KqKpc9FfX_wHp1jFCcwAtPrndIn_oGIyMk?via=matrix.org"
    const val SOURCE = "https://github.com/DocBobert6/AutoSpace"
    /** Upstream Gearslip, credited in About; the CARS.md link lived here until 2026-10-25. */
    const val UPSTREAM = "https://github.com/twinksoftware/Gearslip"

    fun open(context: Context, url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Opens a Monero wallet app on the address; says so when none is installed. */
    fun openMoneroWallet(context: Context) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("monero:$MONERO")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            Toast.makeText(context, "No Monero wallet app found. Copy the address instead.", Toast.LENGTH_LONG).show()
        }
    }

    fun copyMonero(context: Context) {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Monero address", MONERO))
        Toast.makeText(context, "Monero address copied", Toast.LENGTH_SHORT).show()
    }
}

/** The ways to support Gearslip: Liberapay first, Monero last, card once it's set up. */
// AutoSpace: donate rows removed — no donation recipient is configured for this fork.
@Composable
internal fun DonateRows() {
}
// Audit fix (2026-10-25, B7/R2): MoneroDialog deleted —
// it had no caller since DonateRows() was emptied.
/** The About section's links: the chat room, the source code, and credits. */
@Composable
internal fun AboutLinkRows() {
    val context = LocalContext.current
    val arrow = @Composable {
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // Audit fix (2026-10-25, B5/R1): the upstream Matrix row is
    // removed with the intro actions — no replacement link is
    // invented; one can be added when Robert supplies his own.
    SettingsRow(
        "Source code",
        Modifier.clickable { GearslipLinks.open(context, GearslipLinks.SOURCE) },
        subtitle = "Read or change AutoSpace's code under the AGPL",
        icon = Icons.Filled.Build,
        trailing = arrow,
    )
    SettingsDivider()
    // Credits audit (2026-10-25): the fork credit was only in
    // repo docs, never in the app itself. This row names the
    // upstream project and its licence where a driver can see it.
    SettingsRow(
        "Based on Gearslip",
        Modifier.clickable { GearslipLinks.open(context, GearslipLinks.UPSTREAM) },
        subtitle = "AutoSpace is a fork of Gearslip by Twink Software LLC, licensed under the AGPL-3.0",
        icon = Icons.Filled.Build,
        trailing = arrow,
    )
    SettingsDivider()
    // Grouped, as individual rows for every library would be noise:
    // Media3 (player), AndroidX including Compose Material 3 and
    // the car-app libraries, and Google's Material components —
    // all Apache 2.0. The vendored sherpa-onnx bindings in
    // com/k2fsa/sherpa/onnx are part of sherpa-onnx, credited in
    // the Voice credits row below.
    SettingsRow(
        "Open-source libraries",
        subtitle = "Media3, AndroidX (including Compose Material 3 and the car-app libraries) " +
            "and Google Material components, all Apache 2.0",
        icon = Icons.Filled.Build,
    )
    SettingsDivider()
    SettingsRow(
        "Voice credits",
        subtitle = "Speech recognition by sherpa-onnx (Apache 2.0), with Kroko ASR by Banafo " +
            "(CC BY-SA 4.0), and Parakeet and Canary by NVIDIA (CC BY 4.0). " +
            "Spoken voice by Kokoro-82M (Hexgrad, Apache 2.0), with Piper voices " +
            "(MIT) as fallback, and espeak-ng phonemization (GPL-3.0)",
        icon = Icons.Filled.Favorite,
    )
}
