package app.autospace.car

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.autospace.car.theme.FrostedPanelSurface
import app.autospace.car.theme.GsButton
import app.autospace.car.theme.GsIconButton
import app.autospace.car.theme.gsButtonShape
import app.autospace.notify.CarMotion
import app.autospace.speech.VoiceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * r161: the bookshelf and reader, a separate section like games, with
 * its own launcher entry. The shelf lists EPUB books with cover, title,
 * author, and Mariner-faithful progress ("Chapter N+1"); the reader is
 * a scrolling column by default, restoring the saved position, with
 * read-aloud over the car's existing voice.
 *
 * Safety rule, implemented exactly as briefed: the bookshelf and
 * reader TEXT surfaces are hard parked-gated like games (content
 * swaps to a parked notice when the car moves, no passenger bypass).
 * Read-aloud AUDIO, once started while parked, CONTINUES while moving
 * (audiobooks are a driving activity, unlike games, which stop
 * playback); the parked notice carries a Stop button so the audio can
 * always be stopped, and read-aloud cannot be started while moving.
 * The session object lives at this screen's level, outside the gated
 * branch, so swapping the text away never stops the audio; leaving
 * the bookshelf entirely does stop it.
 *
 * What is deliberately not here: spread mode, page curls, a theme
 * picker (the reader follows the AutoSpace scheme), custom fonts,
 * ribbons, sync, or a percent readout (progress shows as Chapter
 * N+1; the fraction is stored and restored, not displayed).
 */
private sealed interface BookPage {
    data object Shelf : BookPage
    data class Reader(val bookId: String) : BookPage
}

/** Read-aloud over the car's existing voice, r21 sentence-chain shape. */
class BookReadAloudSession {
    var bookId by mutableStateOf<String?>(null)
        private set
    var bookTitle by mutableStateOf("")
        private set
    var chapterIndex by mutableStateOf(0)
        private set
    var speaking by mutableStateOf(false)
        private set
    var paused by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    private var token = 0
    private var chunkIndex = 0
    private var chunkCount = 0
    private var chunks: List<String> = emptyList()
    private var parsed: ParsedBook? = null
    private var appContext: android.content.Context? = null

    /** Fraction through the current chapter's spoken chunks, for progress saves. */
    fun progressFraction(): Float =
        if (chunkCount > 0) chunkIndex.toFloat() / chunkCount.toFloat() else 0f

    fun start(
        context: android.content.Context,
        book: BookRecord,
        parsedBook: ParsedBook,
        fromChapter: Int,
        fromChunk: Int = 0,
    ) {
        token++
        appContext = context.applicationContext
        parsed = parsedBook
        bookId = book.id
        bookTitle = book.title
        speaking = true
        paused = false
        notice = null
        playChapter(fromChapter.coerceIn(0, parsedBook.chapters.lastIndex), fromChunk)
    }

    private fun playChapter(chapter: Int, fromChunk: Int) {
        val book = parsed ?: return
        val t = token
        if (chapter >= book.chapters.size) {
            finish()
            return
        }
        chapterIndex = chapter
        val text = stripImageTokens(book.chapters[chapter].text)
        chunks = ArticleExtractor.sentenceChunks(text, 600)
        chunkCount = chunks.size
        if (chunks.isEmpty()) {
            playChapter(chapter + 1, 0)
            return
        }
        playChunk(fromChunk.coerceIn(0, chunks.lastIndex), t)
    }

    private fun playChunk(index: Int, t: Int) {
        val ctx = appContext ?: return
        if (index >= chunks.size) {
            playChapter(chapterIndex + 1, 0)
            return
        }
        chunkIndex = index
        val started = VoiceEngine.play(
            ctx,
            chunks[index],
            onDone = { if (t == token) playChunk(index + 1, t) },
            onSilent = { if (t == token) { speaking = false; paused = true } },
        )
        if (!started) {
            notice = "No spoken voice is downloaded yet. Download one in Settings first."
            speaking = false
            paused = false
        }
    }

    /** Pause: stop plus remember the paragraph; resume restarts there. No true-pause engine work. */
    fun pause() {
        if (!speaking) return
        token++
        VoiceEngine.stop()
        speaking = false
        paused = true
    }

    fun resume() {
        if (!paused) return
        token++
        speaking = true
        paused = false
        notice = null
        playChapter(chapterIndex, chunkIndex)
    }

    /** Stop: clears the session; the caller saves progress first from [chapterIndex] and [progressFraction]. */
    fun stop() {
        token++
        VoiceEngine.stop()
        speaking = false
        paused = false
        bookId = null
        bookTitle = ""
        notice = null
        parsed = null
        chunks = emptyList()
        chunkCount = 0
    }

    private fun finish() {
        val ctx = appContext
        val id = bookId
        if (ctx != null && id != null) {
            val last = (parsed?.chapters?.lastIndex ?: 0).coerceAtLeast(0)
            BookImport.updateProgress(ctx, id, last, 1f)
        }
        speaking = false
        paused = false
        bookId = null
        bookTitle = ""
        notice = null
        parsed = null
    }
}

private sealed interface ReaderItem {
    data class Header(val chapter: Int, val title: String) : ReaderItem
    data class Text(val chapter: Int, val text: String) : ReaderItem
    data class Image(val chapter: Int, val token: String) : ReaderItem
}

@Composable
fun BookshelfScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<BookPage>(BookPage.Shelf) }
    var books by remember { mutableStateOf(BookImport.load(context)) }
    var moving by remember { mutableStateOf(CarMotion.moving) }
    var importing by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<BookRecord?>(null) }
    var pickerNote by remember { mutableStateOf(false) }

    // The session lives here, outside the parked-gate branch: moving
    // swaps the text away but never touches this object, so audio
    // continues; leaving the bookshelf disposes it and stops audio.
    val session = remember { BookReadAloudSession() }
    DisposableEffect(Unit) {
        onDispose { session.stop() }
    }

    // Parked gate, live, games shape: a plain property, polled.
    LaunchedEffect(Unit) {
        while (true) {
            moving = CarMotion.moving
            delay(1000)
        }
    }

    fun refresh() {
        books = BookImport.load(context)
    }

    // Returning to the shelf re-reads progress (a finished read-aloud
    // or a reader save must show at once).
    LaunchedEffect(page) {
        if (page is BookPage.Shelf) refresh()
    }

    fun stopReadingAndSave() {
        val id = session.bookId
        if (id != null) {
            BookImport.updateProgress(context, id, session.chapterIndex, session.progressFraction())
        }
        session.stop()
        refresh()
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        pickerNote = false
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch(Dispatchers.IO) {
            val result = BookImport.importFromUri(context, uri, allowDuplicate = true)
            withContext(Dispatchers.Main) {
                importing = false
                refresh()
                resultText = when (result) {
                    is BookImportResult.Added -> "Added ${result.record.title} (${result.chapters} chapters)."
                    is BookImportResult.Refused -> result.reason
                    is BookImportResult.Failed -> result.reason
                }
            }
        }
    }

    // The picker UI opens on the phone (its registry lives in the phone
    // activity, provided at the projector root). Say so while it is open:
    // the car screen otherwise shows nothing at all until the result
    // comes back. A launch failure never crashes.
    fun launchPicker() {
        pickerNote = true
        val ok = runCatching { picker.launch(arrayOf("*/*")) }.isSuccess
        if (!ok) {
            pickerNote = false
            resultText = "Import needs the file picker on your phone, and it could not be opened. Try again."
        }
    }

    Column(
        Modifier.fillMaxSize().padding(
            PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        ),
    ) {
        if (moving) {
            ScreenHeader("Bookshelf")
            Spacer(Modifier.height(16.dp))
            Text(
                "Books need the car parked.",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Reading text on the car screen is parked-only, like games. " +
                    "Read-aloud audio that is already playing keeps going, " +
                    "and you can stop it here.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (session.bookId != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Still reading aloud: ${session.bookTitle}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(8.dp))
                GsButton(onClick = { stopReadingAndSave() }) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Stop reading")
                }
            }
            return@Column
        }
        when (val current = page) {
            BookPage.Shelf -> BookshelfPage(
                books = books,
                importing = importing,
                session = session,
                onOpen = { page = BookPage.Reader(it.id) },
                onImport = { launchPicker() },
                onDelete = { deleteTarget = it },
                onStopReading = { stopReadingAndSave() },
            )
            is BookPage.Reader -> {
                val record = books.firstOrNull { it.id == current.bookId }
                if (record == null) {
                    page = BookPage.Shelf
                } else {
                    ReaderPage(
                        record = record,
                        session = session,
                        onBack = { page = BookPage.Shelf },
                        onSaveProgress = { chapter, fraction ->
                            BookImport.updateProgress(context, record.id, chapter, fraction)
                        },
                    )
                }
            }
        }
        if (!moving) {
            if (pickerNote) {
                BookOverlay(onDismiss = { pickerNote = false }) {
                    Text("The file picker opened on your phone.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Pick there — your book will appear here when you are done.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    BookRow("OK", null) { pickerNote = false }
                }
            }
            resultText?.let { text ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            deleteTarget?.let { book ->
                BookOverlay(onDismiss = { deleteTarget = null }) {
                    Text("Remove ${book.title}?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "This deletes the shelf copy, its record, and its cover.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    BookRow("Remove", null) {
                        val wasReading = session.bookId == book.id
                        if (wasReading) session.stop()
                        BookImport.remove(context, book.id)
                        refresh()
                        deleteTarget = null
                        if (page is BookPage.Reader) page = BookPage.Shelf
                    }
                    BookRow("Keep", null) { deleteTarget = null }
                }
            }
        }
    }
}

@Composable
private fun BookshelfPage(
    books: List<BookRecord>,
    importing: Boolean,
    session: BookReadAloudSession,
    onOpen: (BookRecord) -> Unit,
    onImport: () -> Unit,
    onDelete: (BookRecord) -> Unit,
    onStopReading: () -> Unit,
) {
    val context = LocalContext.current
    ScreenHeader("Bookshelf")
    Text(
        "EPUB books, stored on this device. Books reflow as plain text: " +
            "layout, fonts, and tables do not carry over.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
    )
    if (session.bookId != null) {
        BookRow(
            title = "Reading: ${session.bookTitle}",
            caption = if (session.speaking) "Reading aloud now" else "Paused",
            trailing = {
                GsButton(onClick = onStopReading) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Stop")
                }
            },
        ) { }
        Spacer(Modifier.height(8.dp))
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (books.isEmpty()) {
            item(key = "empty") {
                Text(
                    "No books yet. Import an EPUB to start reading.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(books, key = { _, book -> book.id }) { _, book ->
            BookRow(
                title = book.title,
                caption = buildString {
                    append("EPUB")
                    if (book.author.isNotBlank()) append(" • ${book.author}")
                    append(" • Chapter ${book.chapterIndex + 1}")
                },
                leading = {
                    val coverBytes = remember(book.id, book.coverPath) {
                        book.coverPath?.let { name ->
                            runCatching { java.io.File(BookImport.booksDir(context), name).readBytes() }.getOrNull()
                        }
                    }
                    val bitmap = remember(coverBytes) {
                        coverBytes?.let { BookImport.decodeImage(it) }
                    }
                    Box(
                        Modifier.size(width = 48.dp, height = 64.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh, gsButtonShape(MaterialTheme.shapes.large)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(Icons.Filled.MenuBook, contentDescription = null)
                        }
                    }
                },
                trailing = {
                    IconButton(onClick = { onDelete(book) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove ${book.title}")
                    }
                    Icon(Icons.Filled.ChevronRight, contentDescription = null)
                },
            ) { onOpen(book) }
        }
        item(key = "import") {
            BookRow(
                "Import an EPUB",
                if (importing) "Importing…" else "Opens the file picker on your phone",
            ) { if (!importing) onImport() }
        }
    }
}

@Composable
private fun ReaderPage(
    record: BookRecord,
    session: BookReadAloudSession,
    onBack: () -> Unit,
    onSaveProgress: (Int, Float) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var parsed by remember(record.id) { mutableStateOf<ParsedBook?>(null) }
    var loadError by remember(record.id) { mutableStateOf<String?>(null) }
    var progressArmed by remember(record.id) { mutableStateOf(false) }

    // Re-parse on open (Mariner shape), through the 3-entry cache.
    LaunchedEffect(record.id) {
        val file = BookImport.bookFile(context, record)
        val cached = ParsedBookCache.get(file.absolutePath)
        if (cached != null) {
            parsed = cached
        } else {
            val result = withContext(Dispatchers.IO) {
                runCatching { EpubParser.parse(file) }
            }
            result.onSuccess { book ->
                ParsedBookCache.put(file.absolutePath, book)
                parsed = book
            }.onFailure { e ->
                loadError = if (e.message == EpubParser.DRM_MESSAGE) {
                    EpubParser.DRM_MESSAGE
                } else {
                    "That book could not be opened."
                }
            }
        }
    }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GsIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack, size = 56.dp, iconSize = 28.dp)
        Text(
            record.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
    val error = loadError
    val book = parsed
    if (error != null) {
        Spacer(Modifier.height(16.dp))
        Text(error, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        BookRow("Back to shelf", null) { onBack() }
        return
    }
    if (book == null) {
        Spacer(Modifier.height(16.dp))
        Text("Opening…", style = MaterialTheme.typography.bodyLarge)
        return
    }

    val items = remember(book) {
        val out = mutableListOf<ReaderItem>()
        book.chapters.forEachIndexed { chapterIndex, chapter ->
            out += ReaderItem.Header(chapterIndex, chapter.title)
            val paragraphs = chapter.text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            readerParts(paragraphs).forEach { part ->
                when (part) {
                    is BookPart.Text -> out += ReaderItem.Text(chapterIndex, part.text)
                    is BookPart.Image -> out += ReaderItem.Image(chapterIndex, part.token)
                }
            }
        }
        out
    }
    val itemChapters = remember(items) { items.map { item ->
        when (item) {
            is ReaderItem.Header -> item.chapter
            is ReaderItem.Text -> item.chapter
            is ReaderItem.Image -> item.chapter
        }
    } }
    val chapterStarts = remember(itemChapters) {
        book.chapters.indices.mapNotNull { chapter ->
            itemChapters.indexOfFirst { it == chapter }.takeIf { it >= 0 }
        }
    }
    val listState = rememberLazyListState()

    // Restore the saved position once the items exist, then arm progress saves.
    LaunchedEffect(book, record.id) {
        val target = BookLibrary.itemIndexForProgress(itemChapters, record.chapterIndex, record.scrollFraction)
        if (itemChapters.isNotEmpty()) {
            listState.scrollToItem(target)
        }
        progressArmed = true
    }
    // Progress saves, debounced 600 ms, only once restoring is done:
    // without the arm, the first visible item (chapter 0) would
    // overwrite the saved position before the restore scroll lands.
    LaunchedEffect(progressArmed, book) {
        if (!progressArmed) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .debounce(600)
            .collect { index ->
                val (chapter, fraction) = BookLibrary.progressForVisibleItem(itemChapters, index)
                onSaveProgress(chapter, fraction)
            }
    }

    val visibleChapter = if (itemChapters.isNotEmpty()) {
        itemChapters.getOrNull(listState.firstVisibleItemIndex) ?: record.chapterIndex
    } else {
        record.chapterIndex
    }
    val readingThisBook = session.bookId == record.id
    Spacer(Modifier.height(8.dp))
    Text(
        "Chapter ${visibleChapter + 1} of ${book.chapters.size} • ${book.chapters.getOrNull(visibleChapter)?.title.orEmpty()}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (readingThisBook) {
        Text(
            if (session.speaking) "Reading aloud: Chapter ${session.chapterIndex + 1}" else "Read-aloud paused",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    session.notice?.let { notice ->
        if (readingThisBook) {
            Text(notice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!readingThisBook) {
            GsButton(onClick = {
                session.start(context, record, book, visibleChapter)
            }) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Read aloud")
            }
        } else if (session.speaking) {
            GsButton(onClick = {
                val chapter = session.chapterIndex
                val fraction = session.progressFraction()
                session.pause()
                onSaveProgress(chapter, fraction)
            }) {
                Icon(Icons.Filled.Pause, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Pause")
            }
            GsButton(onClick = {
                val chapter = session.chapterIndex
                val fraction = session.progressFraction()
                session.stop()
                onSaveProgress(chapter, fraction)
            }) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Stop")
            }
        } else if (session.paused) {
            GsButton(onClick = { session.resume() }) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Resume")
            }
            GsButton(onClick = {
                val chapter = session.chapterIndex
                val fraction = session.progressFraction()
                session.stop()
                onSaveProgress(chapter, fraction)
            }) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Stop")
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GsButton(
            onClick = {
                val prev = (visibleChapter - 1).coerceAtLeast(0)
                chapterStarts.getOrNull(prev)?.let { target ->
                    scope.launch { listState.scrollToItem(target) }
                }
            },
            enabled = visibleChapter > 0,
        ) { Text("Previous chapter") }
        GsButton(
            onClick = {
                val next = (visibleChapter + 1).coerceAtMost(book.chapters.lastIndex)
                chapterStarts.getOrNull(next)?.let { target ->
                    scope.launch { listState.scrollToItem(target) }
                }
            },
            enabled = visibleChapter < book.chapters.lastIndex,
        ) { Text("Next chapter") }
    }
    Spacer(Modifier.height(8.dp))
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(items, key = { index, _ -> index }) { _, item ->
            when (item) {
                is ReaderItem.Header -> Text(
                    item.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
                is ReaderItem.Text -> Text(
                    item.text,
                    style = MaterialTheme.typography.bodyLarge,
                )
                is ReaderItem.Image -> {
                    val bytes = book.images[item.token]
                    val bitmap = remember(item.token, bytes) {
                        bytes?.let { BookImport.decodeImage(it) }
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** One full-width car row: touchscreen tap, D-pad focus with the car ring, and gamepad A all confirm. */
@Composable
private fun BookRow(
    title: String,
    caption: String?,
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = gsButtonShape(MaterialTheme.shapes.large),
    ) {
        Row(
            Modifier.fillMaxWidth()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.ButtonA) {
                        onClick()
                        true
                    } else {
                        false
                    }
                }
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading()
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (caption != null) {
                    Text(
                        caption,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing()
        }
    }
}

/** In-composition overlay card: a dimmed scrim plus a centered frosted panel. Never a real Dialog window. */
@Composable
private fun BookOverlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        FrostedPanelSurface(
            shape = gsButtonShape(MaterialTheme.shapes.large),
            elevation = 4.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp).clickable {},
        ) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                content()
            }
        }
    }
}
