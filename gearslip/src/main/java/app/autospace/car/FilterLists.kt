package app.autospace.car

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * r85 — the Android half of the filter engine: downloading the
 * filter lists, caching them under filesDir/filters, parsing them
 * on a background thread, and exposing the observable state the
 * browser settings menu reads (updating / rule count / updated
 * date / blocked-this-session count). The pure parse + match
 * rules live in FilterEngine; this object only does I/O.
 *
 * List set — Mariner's, unchanged (same URLs, same order):
 * EasyList, EasyPrivacy, StevenBlack hosts, AdGuard Tracking
 * Protection (3.txt), AdGuard Mobile Ads (11.txt), uBlockOrigin
 * uAssets filters.txt, AdGuard Base (2.txt) last.
 *
 * Refresh trigger, exactly as Mariner's: [initAsync] loads the
 * disk cache, then refreshes in the background when any list file
 * is missing or the newest file is older than 24 hours; the
 * settings menu's "Update now" button refreshes on demand. List
 * updates NEVER block a page load — every download and parse runs
 * on a worker thread, and the live rule set only swaps when a
 * full parse succeeds.
 *
 * Deliberate deltas from Mariner, each with its reason:
 * - No binary compiled.bin cache in v1: Mariner's fingerprint +
 *   binary cache exists to dodge a cold-start parse cost. The
 *   parse runs on a background thread at browser entry, never on
 *   the navigation path, so the cache buys nothing the driver
 *   can feel and costs a whole second persistence format. Text
 *   parse only; revisit only if a cold-start trace shows a cost.
 * - The hosts-format list is selected by an explicit isHosts flag
 *   on its entry, not by its position (Mariner keys it on index
 *   2) — a future list reorder can never silently mis-parse it.
 * - One init per process: Mariner spawns a thread per initAsync
 *   call; AutoSpace guards with [started] so repeated browser
 *   entries cannot stack refresh threads.
 * - The download User-Agent identifies AutoSpace, not Mariner.
 * - State is StateFlow (the menu collects it) rather than
 *   Mariner's Compose mutableState; [updating] is an
 *   AtomicBoolean so a background refresh and a manual "Update
 *   now" tap cannot interleave.
 */
object FilterLists {

    private data class FilterListSource(
        val url: String,
        val fileName: String,
        val isHostsFormat: Boolean,
    )

    private val sources = listOf(
        FilterListSource(
            "https://easylist.to/easylist/easylist.txt",
            "easylist.txt", false,
        ),
        FilterListSource(
            "https://easylist.to/easylist/easyprivacy.txt",
            "easyprivacy.txt", false,
        ),
        // Hosts-format blocklist (adware/malware domains). Fills
        // the long-tail ad-network gaps the ABP lists only cover
        // with path rules.
        FilterListSource(
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            "hosts.txt", true,
        ),
        // AdGuard Tracking Protection — the trackers EasyPrivacy misses.
        FilterListSource(
            "https://filters.adtidy.org/extension/ublock/filters/3.txt",
            "adguard-tracking.txt", false,
        ),
        // AdGuard Mobile Ads — in-app/mobile-web ad networks.
        FilterListSource(
            "https://filters.adtidy.org/extension/ublock/filters/11.txt",
            "adguard-mobile.txt", false,
        ),
        // uBlock Origin's own filters — extra annoyance/ad coverage
        // in the same ABP syntax this engine compiles.
        FilterListSource(
            "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
            "ublock-filters.txt", false,
        ),
        // AdGuard Base — AdGuard's flagship list. Last, so the
        // set never shifts under a cached install.
        FilterListSource(
            "https://filters.adtidy.org/extension/ublock/filters/2.txt",
            "adguard-base.txt", false,
        ),
    )

    private const val STALE_MS = 24L * 3600 * 1000

    /** True while a refresh is downloading or parsing. */
    private val updatingFlag = AtomicBoolean(false)
    private val updatingFlow = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = updatingFlow

    /** Compiled rule count (flat hosts + scoped rules), for the
     *  settings status line. 0 until the first lists load. */
    private val ruleCountFlow = MutableStateFlow(0)
    val ruleCount: StateFlow<Int> = ruleCountFlow

    /** Newest list-file mtime, for the settings status line. 0
     *  until the first lists load. */
    private val updatedAtFlow = MutableStateFlow(0L)
    val updatedAt: StateFlow<Long> = updatedAtFlow

    /** Subresources blocked since this process started. Session
     *  only, never persisted — a count across drives would need
     *  its own storage story and would overstate what the driver
     *  asked for. */
    private val blockedAtomic = AtomicLong(0L)
    private val blockedFlow = MutableStateFlow(0L)
    val blockedCount: StateFlow<Long> = blockedFlow

    @Volatile private var started = false

    private fun dir(context: Context) =
        File(context.filesDir, "filters").apply { mkdirs() }

    /** Loads the disk cache, then refreshes in the background when
     *  any list file is missing or the newest file is older than
     *  24 hours. Safe to call on every browser entry; only the
     *  first call per process does anything. */
    fun initAsync(context: Context) {
        if (started) return
        started = true
        // The bundled host list is the offline fallback and must be
        // live before any subresource is checked — set synchronously
        // here, not on the worker thread, so the first page load
        // already has it even while lists are still parsing.
        FilterEngine.builtinHosts = BrowserFilter.builtinHosts
        val appCtx = context.applicationContext
        Thread {
            loadFromDisk(appCtx)
            val d = dir(appCtx)
            val missing = sources.any { !File(d, it.fileName).exists() }
            val stale = updatedAtFlow.value == 0L ||
                System.currentTimeMillis() - updatedAtFlow.value > STALE_MS
            if (stale || missing) refresh(appCtx)
        }.start()
    }

    /** Manual refresh from the settings menu's "Update now" button. */
    fun refreshAsync(context: Context) {
        val appCtx = context.applicationContext
        Thread { refresh(appCtx) }.start()
    }

    /** Re-reads the driver's own rules from BrowserStore into the
     *  engine. Called at browser entry and after every rule edit,
     *  so an edit takes effect without restarting the browser. */
    fun syncUserRules(context: Context) {
        FilterEngine.userNetRules =
            UserBlockRules.parseNet(BrowserStore.userNetRaw(context))
        FilterEngine.userCosmeticRules =
            UserBlockRules.parseCosmetic(BrowserStore.userCosmeticRaw(context))
        // The userCosmeticRules setter clears the engine's
        // per-host CSS cache, so a cosmetic edit never serves a
        // stale sheet.
    }

    /** Records one blocked subresource (see [blockedCount]). */
    fun noteBlocked() {
        blockedFlow.value = blockedAtomic.incrementAndGet()
    }

    private fun applyData(data: FilterData, newest: Long) {
        FilterEngine.applyData(data)
        // The parsed $document/$popup set replaces the built-in
        // popunder set the moment lists exist (see PopunderHosts).
        PopunderHosts.parsedDocHosts = data.docHosts
        ruleCountFlow.value = data.hosts.size +
            data.scoped.values.sumOf { it.size }
        if (newest > 0) updatedAtFlow.value = newest
    }

    private fun loadFromDisk(context: Context) {
        val d = dir(context)
        val total = FilterData()
        var newest = 0L
        for (src in sources) {
            val f = File(d, src.fileName)
            val bak = File(d, src.fileName + ".bak")
            val srcFile = when {
                f.exists() -> f
                bak.exists() -> bak
                else -> null
            } ?: continue
            newest = maxOf(newest, srcFile.lastModified())
            val one = FilterData()
            runCatching {
                FilterEngine.parseInto(src.isHostsFormat, srcFile.readText(), one)
            }
            // Rollback: a list file that parses to nothing
            // (truncated or corrupt) falls back to the previous
            // good copy kept beside it.
            if (one.isEmpty() && srcFile == f && bak.exists()) {
                val fromBak = FilterData()
                runCatching {
                    FilterEngine.parseInto(src.isHostsFormat, bak.readText(), fromBak)
                }
                if (!fromBak.isEmpty()) {
                    total.merge(fromBak)
                    continue
                }
            }
            total.merge(one)
        }
        // Sanity guard: a bad/truncated download must never wipe a
        // good rule set — if the freshly parsed hosts collapse
        // versus what's already loaded, keep the loaded rules.
        if (total.hosts.isNotEmpty() && FilterEngine.listHosts.isNotEmpty() &&
            total.hosts.size < FilterEngine.listHosts.size / 4
        ) {
            return
        }
        if (total.hosts.isNotEmpty()) {
            applyData(total, newest)
        }
    }

    private fun refresh(context: Context) {
        if (!updatingFlag.compareAndSet(false, true)) return
        updatingFlow.value = true
        try {
            val d = dir(context)
            var ok = false
            for (src in sources) {
                runCatching {
                    val conn = URL(src.url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 20_000
                    conn.readTimeout = 30_000
                    conn.setRequestProperty(
                        "User-Agent", "Mozilla/5.0 (Android) AutoSpace",
                    )
                    val text = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    // Per-format sanity: a captive-portal HTML page
                    // or an error body must not replace a good list
                    // on disk.
                    val sane = text.length > 50_000 && when {
                        src.isHostsFormat -> text.contains("0.0.0.0")
                        else -> text.contains("||") &&
                            !text.trimStart().startsWith("<")
                    }
                    if (!sane) return@runCatching
                    // Validate-and-rollback: parse the candidate
                    // BEFORE it touches disk. Commit only if it
                    // parses to a real rule set and — when a copy
                    // is already on disk — not to a collapse versus
                    // that copy. The previous good file is kept as
                    // .bak; the commit goes through a staging file
                    // + rename so a crash mid-write can't leave a
                    // torn list.
                    val candidate = FilterData()
                    FilterEngine.parseInto(src.isHostsFormat, text, candidate)
                    if (candidate.isEmpty()) return@runCatching
                    val target = File(d, src.fileName)
                    if (target.exists()) {
                        val current = FilterData()
                        runCatching {
                            FilterEngine.parseInto(
                                src.isHostsFormat, target.readText(), current,
                            )
                        }
                        if (!current.isEmpty() &&
                            candidate.total() < current.total() / 2
                        ) {
                            return@runCatching
                        }
                        runCatching {
                            target.copyTo(
                                File(d, src.fileName + ".bak"),
                                overwrite = true,
                            )
                        }
                    }
                    val tmp = File(d, src.fileName + ".tmp")
                    tmp.writeText(text)
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                    ok = true
                }
            }
            if (ok) loadFromDisk(context)
        } finally {
            updatingFlag.set(false)
            updatingFlow.value = false
        }
    }
}
