package app.autospace.car

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.autospace.ProjectedContent

/** One tile in the launcher and the screen it opens. */
class CarApp(
    val id: String,
    val label: String,
    val content: @Composable () -> Unit,
)

/** Built-in apps. Third-party (Android Auto) apps are a later step and will register here too. */
object CarApps {

    val all: List<CarApp> = listOf(
        CarApp("web", "Browser") {
            WebApp(content = ProjectedContent.resolve(LocalContext.current))
        },
        CarApp("phone", "Screen sharing") {
            PhoneAppsScreen()
        },
        CarApp("dialer", "Phone") {
            PhoneDialerScreen()
        },
        CarApp("settings", "Settings") {
            CarSettingsScreen()
        },
        CarApp("games", "Games") {
            GamesScreen()
        },
        CarApp("books", "Bookshelf") {
            BookshelfScreen()
        },
    )

    fun find(id: String): CarApp? = all.firstOrNull { it.id == id }
}
