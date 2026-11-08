package app.autospace.car

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.GsButton
import app.autospace.car.theme.GsIconButton
import app.autospace.car.theme.GsSheetSurface
import java.util.Locale

/**
 * The reader's display sheet (r187, browser parity Phase 5 + G4):
 * Mariner's typeset panel as a car sheet — theme swatches (White /
 * Sepia / Gray / Night), a text-size stepper (14-28), a font picker
 * (Open Sans / Roboto / Inter), and a line-spacing stepper (1.1-2.2).
 * It sits above the reader overlay; BACK closes it before the reader,
 * and every pick reloads the live reader at once. The highlighted theme
 * is the EFFECTIVE one (the stored pick, or the car-scheme match until
 * the user picks), so the swatch always shows what the reader shows.
 * A kit sheet, not a DropdownMenu, because real popup windows crash on
 * this virtual display.
 */
@Composable
fun ReaderDisplaySheet(
    currentTheme: String,
    fontSizePx: Int,
    font: String,
    lineMult: Float,
    onTheme: (String) -> Unit,
    onFontSize: (Int) -> Unit,
    onFont: (String) -> Unit,
    onLineMult: (Float) -> Unit,
    onClose: () -> Unit,
) {
    @Composable
    fun ThemeSwatch(key: String, label: String) {
        val pal = readerPalette(key)
        val selected = currentTheme == key
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(48.dp)
                    .border(
                        if (selected) 2.5.dp else 1.dp,
                        if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    )
                    .background(Color(android.graphics.Color.parseColor(pal.bg)), CircleShape)
                    .clickable { onTheme(key) },
            )
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
    @Composable
    fun FontPill(key: String, label: String) {
        val selected = readerFontKey(font) == key
        Box(
            Modifier.background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(50),
            )
                .clickable { onFont(key) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)).clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClose,
            ),
        )
        GsSheetSurface(
            // Same nav-bar clearance as the menu and settings sheets:
            // 80dp bottom padding, harmless when the bar is hidden.
            modifier = Modifier.fillMaxWidth().padding(
                start = 16.dp, end = 16.dp, top = 16.dp, bottom = 80.dp,
            ),
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Reader display", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    GsIconButton(Icons.Filled.Close, "Close reader display", onClose, size = 56.dp, iconSize = 28.dp)
                }
                Text("Theme", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ThemeSwatch("white", "White")
                    ThemeSwatch("sepia", "Sepia")
                    ThemeSwatch("gray", "Gray")
                    ThemeSwatch("night", "Night")
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Text size",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    GsButton(
                        onClick = { onFontSize(fontSizePx - 1) },
                        enabled = fontSizePx > 14,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    ) { Text("A−") }
                    Text(
                        "$fontSizePx",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    GsButton(
                        onClick = { onFontSize(fontSizePx + 1) },
                        enabled = fontSizePx < 28,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    ) { Text("A+") }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Font",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FontPill("opensans", "Open Sans")
                        FontPill("roboto", "Roboto")
                        FontPill("inter", "Inter")
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Line spacing",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    GsButton(
                        onClick = { onLineMult(lineMult - 0.1f) },
                        enabled = lineMult > 1.1f,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    ) { Text("−") }
                    Text(
                        "%.1f".format(Locale.US, lineMult),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    GsButton(
                        onClick = { onLineMult(lineMult + 0.1f) },
                        enabled = lineMult < 2.2f,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    ) { Text("+") }
                }
            }
        }
    }
}
