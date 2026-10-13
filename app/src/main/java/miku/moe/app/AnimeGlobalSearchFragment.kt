package miku.moe.app

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import miku.moe.app.api.AnimeRepository
import miku.moe.app.api.ApiAnimePost
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Pencarian Anime Global v2 (UI/UX disamakan dengan MangaSearchFragment).
 * Hasil dari semua source digabung dalam satu daftar vertikal, diurutkan berdasarkan kecocokan judul,
 * bisa difilter per source, dan mencari otomatis saat mengetik. Saat memuat tampil kartu shimmer.
 */
class AnimeGlobalSearchFragment : Fragment() {
    private class SourceState(val id: String, val label: String) {
        var status = STATUS_LOADING
        var finished = false
        val items = ArrayList<AnimePost>()
        val scores = ArrayList<Int>()
    }

    private class Scored(val post: AnimePost, val score: Int, val sourceIndex: Int, val position: Int)

    /** Satu putaran pencarian: sumber dijalankan bergantian (maks. MAX_PARALLEL_SOURCES sekaligus). */
    private class SearchRun(val id: Int) {
        val pending = ArrayDeque<SourceState>()
        var active = 0
    }

    private lateinit var searchEditText: EditText
    private lateinit var clearButton: View
    private lateinit var searchProgress: LinearProgressIndicator
    private lateinit var filterScroll: HorizontalScrollView
    private lateinit var filterChips: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var resultList: RecyclerView
    private lateinit var idleState: View
    private lateinit var idleTitle: TextView
    private lateinit var idleSubtitle: TextView
    private lateinit var recentSection: View
    private lateinit var recentGroup: ChipGroup
    private var adapter: AnimeSearchV2Adapter? = null

    private val repository = AnimeRepository()
    private val states = ArrayList<SourceState>()
    private val handler = Handler(Looper.getMainLooper())
    private val debounceRunnable = Runnable { searchAll(false) }
    private var activeQuery = ""
    private var activeQueryNorm = ""
    private var selectedSource = ""
    private var generation = 0
    private var ignoreTextChange = false
    private var currentRun: SearchRun? = null
    private var renderScheduled = false
    private var lastFilterSignature = ""
    private val renderRunnable = Runnable {
        renderScheduled = false
        render()
    }

    private fun alive() = isAdded && view != null && adapter != null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_manga_search_v2, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        searchEditText = view.findViewById(R.id.searchEditText)
        clearButton = view.findViewById(R.id.searchClearButton)
        searchProgress = view.findViewById(R.id.searchProgress)
        filterScroll = view.findViewById(R.id.searchFilterScroll)
        filterChips = view.findViewById(R.id.searchFilterChips)
        statusText = view.findViewById(R.id.searchStatusText)
        resultList = view.findViewById(R.id.searchResultList)
        idleState = view.findViewById(R.id.searchIdleState)
        idleTitle = view.findViewById(R.id.searchIdleTitle)
        idleSubtitle = view.findViewById(R.id.searchIdleSubtitle)
        recentSection = view.findViewById(R.id.searchRecentSection)
        recentGroup = view.findViewById(R.id.searchRecentGroup)
        searchEditText.hint = "Cari anime di semua sumber"

        adapter = AnimeSearchV2Adapter(requireContext(), object : AnimeSearchV2Adapter.Listener {
            override fun onAnimeClick(post: AnimePost) {
                if (!isAdded) return
                saveRecent(activeQuery)
                hideKeyboard()
                openAnime(post)
            }

            override fun onEpisodeClick(post: AnimePost) {
                if (!isAdded) return
                saveRecent(activeQuery)
                hideKeyboard()
                openAnime(post)
            }

            override fun onBannerClick() = Unit

            override fun onFooterClick(sourceId: String, sourceLabel: String) {
                openViewAll(sourceId, sourceLabel)
            }
        })
        resultList.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false)
        resultList.itemAnimator = null
        resultList.setHasFixedSize(true)
        resultList.setItemViewCacheSize(8)
        resultList.adapter = adapter
        resultList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard()
            }
        })

        searchEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchAll(true)
                true
            } else false
        }
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (ignoreTextChange) return
                val value = s?.toString()?.trim().orEmpty()
                clearButton.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
                handler.removeCallbacks(debounceRunnable)
                if (value.isEmpty()) {
                    resetToIdle()
                    return
                }
                if (value == activeQuery) return
                if (value.length >= 2) handler.postDelayed(debounceRunnable, DEBOUNCE_MS)
            }
        })
        clearButton.setOnClickListener {
            searchEditText.setText("")
            searchEditText.requestFocus()
        }
        view.findViewById<View>(R.id.searchRecentClear).setOnClickListener {
            clearRecents()
            refreshRecents()
        }
        resetToIdle()
    }

    fun refreshSourceSettings() {
        if (view == null) return
        if (currentQuery().isNotEmpty()) searchAll(false)
    }

    // ---------------------------------------------------------------- pencarian

    private fun currentQuery(): String = searchEditText.text?.toString()?.trim().orEmpty()

    private fun searchAll(explicit: Boolean) {
        if (!alive()) return
        handler.removeCallbacks(debounceRunnable)
        val query = currentQuery()
        val run = ++generation
        states.clear()
        currentRun = null
        selectedSource = ""
        lastFilterSignature = ""
        if (query.isEmpty()) {
            resetToIdle()
            return
        }
        activeQuery = query
        activeQueryNorm = normalize(query)
        if (explicit) {
            saveRecent(query)
            hideKeyboard()
        }
        for (sourceId in AnimeSettingsManager.getEnabledAnimeSources(requireContext())) {
            states.add(SourceState(sourceId, AnimeSettingsManager.labelForSourceId(sourceId)))
        }
        render()
        if (states.isEmpty()) return
        val searchRun = SearchRun(run)
        searchRun.pending.addAll(states)
        currentRun = searchRun
        pumpSearches(searchRun, query)
    }

    private fun pumpSearches(searchRun: SearchRun, query: String) {
        while (alive() && searchRun === currentRun && searchRun.active < MAX_PARALLEL_SOURCES && searchRun.pending.isNotEmpty()) {
            val state = searchRun.pending.poll() ?: continue
            searchRun.active++
            searchSourceAsync(searchRun, state, query)
        }
    }

    private fun searchSourceAsync(searchRun: SearchRun, state: SourceState, query: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            var data: List<AnimePost>? = null
            var status = STATUS_DONE
            try {
                // Sumber yang macet tidak boleh menahan antrean: lewat batas waktu dianggap gagal.
                data = withTimeoutOrNull(SOURCE_TIMEOUT_MS) { searchSource(state.id, query) }
                if (data == null) status = STATUS_ERROR
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Search error ${state.id}", e)
                status = STATUS_ERROR
            }
            finishSource(searchRun, state, status, data)
        }
    }

    private fun finishSource(searchRun: SearchRun, state: SourceState, status: Int, data: List<AnimePost>?) {
        if (state.finished) return
        state.finished = true
        if (!alive() || searchRun !== currentRun) return
        searchRun.active = maxOf(0, searchRun.active - 1)
        if (status == STATUS_DONE) applySourceData(state, data)
        else {
            state.items.clear()
            state.scores.clear()
            state.status = status
        }
        scheduleRender()
        pumpSearches(searchRun, activeQuery)
    }

    private fun applySourceData(state: SourceState, data: List<AnimePost>?) {
        state.items.clear()
        state.scores.clear()
        state.status = STATUS_DONE
        if (data == null) return
        val keys = HashSet<String>()
        for (post in data) {
            post.sourceId = state.id
            val key = if (post.slug.isNullOrBlank()) "${post.categoryId}:${post.categoryName}" else post.slug
            if (key.isBlank() || !keys.add(key.trim())) continue
            state.items.add(post)
            state.scores.add(score(post.categoryName, activeQueryNorm))
            if (state.items.size >= SEARCH_LIMIT) break
        }
    }

    private fun resetToIdle() {
        generation++
        handler.removeCallbacks(debounceRunnable)
        states.clear()
        currentRun = null
        selectedSource = ""
        activeQuery = ""
        activeQueryNorm = ""
        lastFilterSignature = ""
        render()
    }

    /** Menggabungkan banyak pembaruan beruntun jadi satu render agar UI tidak tersendat. */
    private fun scheduleRender() {
        if (renderScheduled) return
        renderScheduled = true
        handler.postDelayed(renderRunnable, RENDER_THROTTLE_MS)
    }

    // ---------------------------------------------------------------- tampilan

    private fun render() {
        renderScheduled = false
        handler.removeCallbacks(renderRunnable)
        val listAdapter = adapter
        if (!alive() || listAdapter == null) return
        if (activeQuery.isEmpty()) {
            filterScroll.visibility = View.GONE
            statusText.visibility = View.GONE
            searchProgress.visibility = View.GONE
            resultList.visibility = View.GONE
            listAdapter.submitList(ArrayList<AnimeSearchV2Adapter.Row>())
            idleTitle.text = "Cari di semua sumber"
            idleSubtitle.text = "Hasil dari semua sumber aktif digabung dan diurutkan berdasarkan kecocokan judul."
            idleState.visibility = View.VISIBLE
            refreshRecents()
            return
        }
        var selected = findState(selectedSource)
        if (selectedSource.isNotEmpty() && (selected == null || (selected.status != STATUS_LOADING && selected.items.isEmpty()))) {
            selectedSource = ""
            selected = null
        }
        val rows = buildRows(selected)
        val loading = anyLoading()
        val hasResultRows = rows.any { it.kind == AnimeSearchV2Adapter.KIND_RESULT }
        if (loading && selected == null) {
            // Shimmer: penuh saat belum ada hasil, dua kartu di bawah saat sumber lain masih diproses.
            val skeletons = if (hasResultRows) SKELETON_MORE_ROWS else SKELETON_ROWS
            for (i in 0 until skeletons) rows.add(AnimeSearchV2Adapter.Row.skeleton(i))
        }
        listAdapter.submitList(rows)
        updateFilterChips()
        updateStatus()

        if (rows.isEmpty()) {
            resultList.visibility = View.GONE
            idleState.visibility = View.VISIBLE
            recentSection.visibility = View.GONE
            if (states.isEmpty()) {
                idleTitle.text = "Belum ada sumber aktif"
                idleSubtitle.text = "Aktifkan minimal satu sumber anime di pengaturan."
            } else if (loading) {
                idleTitle.text = "Mencari…"
                idleSubtitle.text = "Menunggu hasil dari ${states.size} sumber."
            } else {
                idleTitle.text = "Tidak ada hasil untuk “$activeQuery”"
                idleSubtitle.text = "Coba kata kunci lain atau periksa sumber yang aktif."
            }
        } else {
            idleState.visibility = View.GONE
            resultList.visibility = View.VISIBLE
        }
    }

    private fun buildRows(selected: SourceState?): ArrayList<AnimeSearchV2Adapter.Row> {
        val rows = ArrayList<AnimeSearchV2Adapter.Row>()
        if (selected != null) {
            for (post in selected.items) rows.add(AnimeSearchV2Adapter.Row.result(post))
            if (selected.items.isNotEmpty()) rows.add(AnimeSearchV2Adapter.Row.footer(selected.id, "Lihat semua hasil di ${selected.label}"))
            return rows
        }
        val scored = ArrayList<Scored>()
        for (s in states.indices) {
            val state = states[s]
            for (p in state.items.indices) {
                val value = if (p < state.scores.size) state.scores[p] else 4
                scored.add(Scored(state.items[p], value, s, p))
            }
        }
        scored.sortWith(Comparator { a, b ->
            if (a.score != b.score) return@Comparator if (a.score < b.score) -1 else 1
            if (a.sourceIndex != b.sourceIndex) return@Comparator if (a.sourceIndex < b.sourceIndex) -1 else 1
            a.position.compareTo(b.position)
        })
        for (item in scored) rows.add(AnimeSearchV2Adapter.Row.result(item.post))
        return rows
    }

    private fun updateFilterChips() {
        val total = totalResults()
        val showFilters = total > 0 || anyLoading()
        filterScroll.visibility = if (showFilters) View.VISIBLE else View.GONE
        if (!showFilters) {
            filterChips.removeAllViews()
            lastFilterSignature = ""
            return
        }
        val signature = StringBuilder()
        signature.append("Semua ").append(total).append('|').append(selectedSource.isEmpty())
        val chips = ArrayList<Array<String>>()
        for (state in states) {
            if (state.status == STATUS_ERROR) continue
            if (state.status == STATUS_DONE && state.items.isEmpty()) continue
            val count = if (state.status == STATUS_LOADING) "…" else state.items.size.toString()
            chips.add(arrayOf(state.id, "${state.label} $count"))
            signature.append(';').append(state.id).append(':').append(count).append(':').append(state.id == selectedSource)
        }
        val sig = signature.toString()
        if (sig == lastFilterSignature) return
        lastFilterSignature = sig
        filterChips.removeAllViews()
        addFilterChip("", "Semua $total", selectedSource.isEmpty())
        for (chip in chips) addFilterChip(chip[0], chip[1], chip[0] == selectedSource)
    }

    private fun addFilterChip(sourceId: String, label: String, selected: Boolean) {
        val chip = LayoutInflater.from(requireContext()).inflate(R.layout.manga_home_v3_source_chip, filterChips, false) as TextView
        chip.text = label
        chip.isSelected = selected
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.marginEnd = dp(8)
        chip.layoutParams = params
        chip.setOnClickListener {
            if (sourceId == selectedSource) return@setOnClickListener
            selectedSource = sourceId
            render()
            resultList.scrollToPosition(0)
        }
        filterChips.addView(chip)
    }

    private fun updateStatus() {
        val total = states.size
        val finished = states.count { it.status != STATUS_LOADING }
        val results = totalResults()
        val loading = finished < total
        if (loading) {
            searchProgress.visibility = View.VISIBLE
            searchProgress.setProgressCompat(if (total == 0) 0 else finished * 100 / total, true)
            var text = "Mencari… $finished/$total sumber"
            if (results > 0) text += " · $results hasil"
            statusText.text = text
        } else {
            searchProgress.visibility = View.GONE
            val sourcesWithResults = states.count { it.items.isNotEmpty() }
            val failed = countStatus(STATUS_ERROR)
            val text = StringBuilder()
            if (results == 0) text.append("Tidak ada hasil")
            else text.append(results).append(" hasil dari ").append(sourcesWithResults).append(" sumber")
            if (failed > 0) text.append(" · ").append(failed).append(" gagal")
            statusText.text = text.toString()
        }
        statusText.visibility = if (total == 0) View.GONE else View.VISIBLE
    }

    private fun findState(sourceId: String): SourceState? {
        if (sourceId.isEmpty()) return null
        return states.firstOrNull { it.id == sourceId }
    }

    private fun anyLoading() = states.any { it.status == STATUS_LOADING }

    private fun countStatus(status: Int) = states.count { it.status == status }

    private fun totalResults() = states.sumOf { it.items.size }

    // ---------------------------------------------------------------- riwayat

    private fun prefs(): SharedPreferences = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadRecents(): ArrayList<String> {
        val out = ArrayList<String>()
        if (!isAdded) return out
        val raw = prefs().getString(KEY_RECENT, "").orEmpty()
        if (raw.isEmpty()) return out
        for (part in raw.split("\n")) {
            val clean = part.trim()
            if (clean.isNotEmpty()) out.add(clean)
        }
        return out
    }

    private fun storeRecents(values: List<String>) {
        if (!isAdded) return
        prefs().edit().putString(KEY_RECENT, TextUtils.join("\n", values)).apply()
    }

    private fun saveRecent(query: String?) {
        if (!isAdded || query == null) return
        val clean = query.trim().replace("\n", " ")
        if (clean.isEmpty()) return
        val recents = loadRecents()
        recents.removeAll { it.equals(clean, ignoreCase = true) }
        recents.add(0, clean)
        while (recents.size > MAX_RECENT) recents.removeAt(recents.size - 1)
        storeRecents(recents)
    }

    private fun removeRecent(query: String) {
        val recents = loadRecents()
        recents.removeAll { it.equals(query, ignoreCase = true) }
        storeRecents(recents)
    }

    private fun clearRecents() {
        if (isAdded) prefs().edit().remove(KEY_RECENT).apply()
    }

    private fun refreshRecents() {
        if (!isAdded || view == null) return
        recentGroup.removeAllViews()
        val recents = loadRecents()
        recentSection.visibility = if (recents.isEmpty()) View.GONE else View.VISIBLE
        val surface = MaterialColors.getColor(recentGroup, com.google.android.material.R.attr.colorSurfaceContainerHigh)
        val onSurface = MaterialColors.getColor(recentGroup, com.google.android.material.R.attr.colorOnSurface)
        val onSurfaceVariant = MaterialColors.getColor(recentGroup, com.google.android.material.R.attr.colorOnSurfaceVariant)
        for (query in recents) {
            val chip = Chip(requireContext())
            chip.text = query
            chip.textSize = 13f
            chip.setTextColor(onSurface)
            chip.chipBackgroundColor = ColorStateList.valueOf(surface)
            chip.chipStrokeWidth = 0f
            chip.chipCornerRadius = dp(10).toFloat()
            chip.setEnsureMinTouchTargetSize(false)
            chip.setCloseIconResource(R.drawable.ic_close)
            chip.closeIconSize = dp(14).toFloat()
            chip.closeIconTint = ColorStateList.valueOf(onSurfaceVariant)
            chip.isCloseIconVisible = true
            chip.setOnClickListener {
                ignoreTextChange = true
                searchEditText.setText(query)
                searchEditText.setSelection(query.length)
                ignoreTextChange = false
                clearButton.visibility = View.VISIBLE
                searchAll(true)
            }
            chip.setOnCloseIconClickListener {
                removeRecent(query)
                refreshRecents()
            }
            recentGroup.addView(chip)
        }
    }

    // ---------------------------------------------------------------- aksi

    private fun hideKeyboard() {
        if (!isAdded || view == null) return
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchEditText.windowToken, 0)
    }

    private fun dp(value: Int): Int = Math.round(value * requireContext().resources.displayMetrics.density)

    private fun openViewAll(sourceId: String, @Suppress("UNUSED_PARAMETER") sourceLabel: String) {
        if (!isAdded || activeQuery.isEmpty()) return
        (requireActivity() as? MainActivity)?.openAnimeBrowseSource(sourceId, AnimeSettingsManager.labelForSourceId(sourceId), activeQuery)
    }

    private fun openAnime(post: AnimePost) {
        val activity = activity as? MainActivity ?: return
        when (post.sourceId) {
            AnimeSettingsManager.SOURCE_ANIMEKU -> activity.openAnimekuDetail(post.categoryId, post.channelId, post.categoryName, post.imgUrl, post.genre, post.rating, post.year, post.countView, post.episodeCount, post.description)
            AnimeSettingsManager.SOURCE_ANIMELOVERZ -> activity.openAnimeLoverzDetail(post.slug, post.categoryName, post.imgUrl, post.genre, post.rating, post.statusVideo, post.description)
            AnimeSettingsManager.SOURCE_DRAMORA -> activity.openAnimeDetailV2(post)
            AnimeSettingsManager.SOURCE_DRAKORKU -> activity.openAnimeDetailV2(post)
            else -> activity.openDetail(post.categoryId, post.channelId)
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden && view != null) hideKeyboard()
    }

    override fun onDestroyView() {
        generation++
        handler.removeCallbacksAndMessages(null)
        currentRun = null
        renderScheduled = false
        lastFilterSignature = ""
        if (view != null) resultList.adapter = null
        adapter = null
        super.onDestroyView()
    }

    // ---------------------------------------------------------------- pengambilan data per sumber

    private fun normalize(value: String?): String {
        if (value == null) return ""
        return NON_WORD.replace(value.lowercase(Locale.ROOT), " ").trim()
    }

    /** query harus sudah dinormalisasi (lihat activeQueryNorm). */
    private fun score(title: String?, q: String): Int {
        val t = normalize(title)
        if (q.isEmpty() || t.isEmpty()) return 4
        if (t == q) return 0
        if (t.startsWith(q)) return 1
        if (t.contains(q)) return 2
        for (token in q.split(" ")) {
            if (token.isNotEmpty() && !t.contains(token)) return 4
        }
        return 3
    }

    private suspend fun searchSource(sourceId: String, query: String): List<AnimePost> {
        return when (sourceId) {
            AnimeSettingsManager.SOURCE_ANIMEKU -> searchAnimeku(query)
            AnimeSettingsManager.SOURCE_ANIMELOVERZ -> searchAnimeLoverz(query)
            AnimeSettingsManager.SOURCE_DRAMORA -> searchDramora(query)
            AnimeSettingsManager.SOURCE_DRAKORKU -> searchDrakorku(query)
            else -> searchDefault(query)
        }
    }

    private suspend fun searchDefault(query: String): List<AnimePost> {
        val response = repository.searchAnime(query, 1, SEARCH_LIMIT)
        if (!response.status.equals("ok", true)) return emptyList()
        return response.categories.orEmpty().mapNotNull { item -> defaultPost(item, query) }
    }

    private fun defaultPost(item: ApiAnimePost, query: String): AnimePost? {
        if (miku.moe.app.api.XNontonBlocklist.isBlocked(item)) return null
        val categoryId = item.cid ?: item.categoryId ?: -1
        val title = item.categoryName.orEmpty()
        if (categoryId <= 0 || title.isBlank()) return null
        if (!title.lowercase().contains(query.lowercase())) return null
        return AnimePost(item.imgUrl.orEmpty(), cleanTitle(title), categoryId, item.channelId ?: -1).apply {
            sourceId = AnimeSettingsManager.SOURCE_DEFAULT
            channelName = ""
            created = item.created.orEmpty()
            countView = item.countView ?: item.totalViews.orEmpty()
            rating = item.rating.orEmpty()
            scheduleDay = item.days ?: -1
            year = item.years?.toIntOrNull() ?: 0
        }
    }

    private suspend fun searchAnimeku(query: String): List<AnimePost> = withContext(Dispatchers.IO) {
        val url = "$ANIMEKU_API_BASE/get_category_genre?search=${encode(query)}&sort=c.category_name%20ASC&api_key=$ANIMEKU_API_KEY"
        val body = httpClient.newCall(Request.Builder().url(url).headers(animekuHeaders()).build()).execute().use { it.body?.string().orEmpty() }
        val json = JSONObject(body)
        if (!json.optString("status").equals("ok", true)) return@withContext emptyList()
        val array = json.optJSONArray("categories") ?: JSONArray()
        val result = ArrayList<AnimePost>()
        val needle = query.lowercase()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val title = cleanTitle(item.optString("category_name", ""))
            if (title.isBlank() || !title.lowercase().contains(needle)) continue
            val categoryId = item.optInt("cid", item.optInt("cat_id", -1))
            if (categoryId <= 0) continue
            result.add(AnimePost(imageAnimeku(item.optString("category_image", "")), title, categoryId, -1).apply {
                sourceId = AnimeSettingsManager.SOURCE_ANIMEKU
                genre = item.optString("genre", "")
                rating = item.optString("rating", "")
                statusVideo = item.optString("status_video", "")
            })
            if (result.size >= SEARCH_LIMIT) break
        }
        result
    }

    private suspend fun searchAnimeLoverz(query: String): List<AnimePost> = withContext(Dispatchers.IO) {
        val url = "$ANIMELOVERZ_API_BASE/search.php?keyword=${encode(query)}&page=1&per_page=$SEARCH_LIMIT"
        val body = httpClient.newCall(Request.Builder().url(url).headers(animeLoverzHeaders()).build()).execute().use { it.body?.string().orEmpty() }
        parseAnimeLoverzSearch(JSONObject(body).optJSONArray("data"))
    }

    private fun parseAnimeLoverzSearch(data: JSONArray?): ArrayList<AnimePost> {
        val result = ArrayList<AnimePost>()
        if (data == null) return result
        for (d in 0 until data.length()) {
            val block = data.optJSONObject(d) ?: continue
            val array = block.optJSONArray("result") ?: continue
            for (i in 0 until array.length()) {
                val post = animeLoverzPost(array.optJSONObject(i) ?: continue)
                if (post != null) result.add(post)
                if (result.size >= SEARCH_LIMIT) return result
            }
        }
        return result
    }

    private fun animeLoverzPost(item: JSONObject): AnimePost? {
        val title = item.optString("judul", "").trim()
        val slug = item.optString("url", "").trim().trim('/')
        val id = item.optString("id", "").toIntOrNull() ?: slug.hashCode().let { if (it == Int.MIN_VALUE) 1 else kotlin.math.abs(it) }
        if (title.isEmpty() || slug.isEmpty()) return null
        return AnimePost(item.optString("cover", ""), cleanTitle(title), id, -1).apply {
            sourceId = AnimeSettingsManager.SOURCE_ANIMELOVERZ
            this.slug = slug
            genre = joinArray(item.optJSONArray("genre"))
            rating = item.optString("score", "")
            statusVideo = item.optString("status", "")
            description = item.optString("sinopsis", "")
            episodeCount = item.optString("total_episode", "")
        }
    }

    private suspend fun searchDramora(query: String): List<AnimePost> = withContext(Dispatchers.IO) {
        Dramora.search(query, 1).items.take(SEARCH_LIMIT)
    }

    private suspend fun searchDrakorku(query: String): List<AnimePost> = withContext(Dispatchers.IO) {
        Drakorku.search(query, 1).items.take(SEARCH_LIMIT)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun cleanTitle(value: String?): String {
        var text = value?.trim().orEmpty().replace(Regex("\\s+"), " ")
        text = text.replace(Regex("(?i)\\s+Eps?\\s*[-:]*\\s*\\d+.*$"), "").trim()
        text = text.replace(Regex("(?i)\\s+Episode\\s*[-:]*\\s*\\d+.*$"), "").trim()
        return text
    }

    private fun imageAnimeku(value: String?): String {
        val image = value?.trim().orEmpty()
        if (image.isEmpty() || image.equals("null", true)) return ""
        if (image.startsWith("http://") || image.startsWith("https://")) return image
        return ANIMEKU_IMAGE_BASE + image
    }

    private fun joinArray(array: JSONArray?): String {
        if (array == null) return ""
        val values = ArrayList<String>()
        for (i in 0 until array.length()) {
            val value = array.optString(i, "").trim()
            if (value.isNotEmpty()) values.add(value)
        }
        return values.joinToString(", ")
    }

    private fun animekuHeaders() = okhttp3.Headers.headersOf(
        "Cache-Control", "max-age=0",
        "Data-Agent", "Your Videos Channel",
        "User-Agent", "Dalvik/7.1.12.1.0 (com.newanimeku.animechanneldonghuasubindosubenglish U; Android ; 20175 Build/NMF260)",
        "Accept", "application/vnd.yourapi.v1.full+json"
    )

    private fun animeLoverzHeaders() = okhttp3.Headers.headersOf(
        "user-agent", "Dart/3.9 (dart:io)",
        "accept", "application/json"
    )

    companion object {
        private const val STATUS_LOADING = 0
        private const val STATUS_DONE = 1
        private const val STATUS_ERROR = 2
        private const val DEBOUNCE_MS = 550L
        private const val MAX_PARALLEL_SOURCES = 4
        private const val SOURCE_TIMEOUT_MS = 25000L
        private const val RENDER_THROTTLE_MS = 120L
        private const val SKELETON_ROWS = 5
        private const val SKELETON_MORE_ROWS = 2
        private const val MAX_RECENT = 12
        private const val PREFS = "miku_anime_search_v2"
        private const val KEY_RECENT = "recent"
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
        private const val TAG = "AnimeGlobalSearch"
        private const val SEARCH_LIMIT = 20
        private const val ANIMEKU_API_BASE = "https://pencarinafkah.xyz/vA6//api/"
        private const val ANIMEKU_API_KEY = "cda11y63tfI7rwln8BLeiKTvjsD5g2Mox01RzkhQCEXSGWbqYO"
        private const val ANIMEKU_IMAGE_BASE = "http://elara.whatbox.ca:29318/Duljanah/"
        private const val ANIMELOVERZ_API_BASE = "https://apps.animekita.org/api/v1.2.5"
        private val httpClient: OkHttpClient by lazy { OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }
    }
}
