@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package miku.moe.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import com.google.android.material.color.MaterialColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.HashSet
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

class MangaDetailV3Fragment : Fragment() {
    private var initialManga: MangaPost? = null
    private var systemBarsApplied = false

    companion object {
        @JvmStatic
        fun newInstance(manga: MangaPost): MangaDetailV3Fragment {
            val fragment = MangaDetailV3Fragment()
            val args = Bundle()
            args.putSerializable("manga", manga)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialManga = arguments?.getSerializable("manga") as? MangaPost
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        applyV3SystemBars()
        val swipeRefreshLayout = SwipeRefreshLayout(requireContext()).apply {
            setColorSchemeColors(MaterialColors.getColor(requireContext(), androidx.appcompat.R.attr.colorPrimary, android.graphics.Color.rgb(103, 80, 164)))
            setProgressBackgroundColorSchemeColor(MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorSurface, android.graphics.Color.rgb(28, 27, 32)))
        }
        val composeView = ComposeView(requireContext())
        swipeRefreshLayout.addView(composeView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        composeView.setContent {
            var refreshKey by remember { mutableIntStateOf(0) }
            DisposableEffect(swipeRefreshLayout) {
                swipeRefreshLayout.setOnRefreshListener {
                    initialManga?.getSourceId()?.let { MangaSourceFactory.invalidateSourceCaches(it) }
                    refreshKey++
                }
                onDispose { swipeRefreshLayout.setOnRefreshListener { } }
            }
            MikuMangaDetailV3Theme {
                MangaDetailV3Screen(
                    initial = initialManga,
                    refreshKey = refreshKey,
                    onRefreshFinished = { swipeRefreshLayout.isRefreshing = false },
                    onCanRefreshChange = { swipeRefreshLayout.isEnabled = it },
                    onBack = { requireActivity().onBackPressedDispatcher.onBackPressed() },
                    onChapterClick = { manga, chapter, chapters -> openChapter(manga, chapter, chapters) },
                    onMangaClick = { openMangaDetail(it) },
                    onGenreClick = { sourceId, sourceLabel, title, value -> openGenre(sourceId, sourceLabel, title, value) }
                )
            }
        }
        return swipeRefreshLayout
    }

    override fun onResume() {
        super.onResume()
        applyV3SystemBars()
    }

    override fun onPause() {
        restoreV3SystemBars()
        super.onPause()
    }

    override fun onDestroyView() {
        restoreV3SystemBars()
        super.onDestroyView()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) restoreV3SystemBars() else applyV3SystemBars()
    }

    private fun applyV3SystemBars() {
        val host = activity ?: return
        ThemeManager.applySystemBars(host)
        systemBarsApplied = true
    }

    private fun restoreV3SystemBars() {
        val host = activity ?: return
        if (!systemBarsApplied) return
        ThemeManager.applySystemBars(host)
        systemBarsApplied = false
    }

    private fun openChapter(manga: MangaPost, chapter: MangaChapter, chapters: List<MangaChapter>) {
        val list = ArrayList(chapters)
        val position = list.indexOfFirst { kotlin.math.abs(it.index - chapter.index) < 0.001f }.coerceAtLeast(0)
        val activity = requireActivity()
        when (activity) {
            is MainActivity -> activity.openMangaReader(manga, list, position)
            is MikuAll -> activity.openMangaReader(manga, list, position)
        }
    }

    private fun openMangaDetail(manga: MangaPost) {
        val activity = requireActivity()
        when (activity) {
            is MainActivity -> activity.openMangaDetail(manga)
            is MikuAll -> activity.openMangaDetail(manga)
        }
    }

    private fun openGenre(sourceId: String, sourceLabel: String, title: String, value: String) {
        when (val activity = activity) {
            is MainActivity -> activity.openMangaGenreResult(sourceId, sourceLabel, title, value)
            is MikuAll -> activity.openMangaGenreResult(sourceId, sourceLabel, title, value)
        }
    }
}

@Composable
private fun MikuMangaDetailV3Theme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    fun themeColor(attr: Int, fallback: Int): Color = Color(MaterialColors.getColor(context, attr, fallback))
    val colors = darkColorScheme(
        primary = themeColor(androidx.appcompat.R.attr.colorPrimary, 0xFFFF78C8.toInt()),
        onPrimary = themeColor(com.google.android.material.R.attr.colorOnPrimary, 0xFF31111F.toInt()),
        primaryContainer = themeColor(com.google.android.material.R.attr.colorPrimaryContainer, 0xFF5B2F4B.toInt()),
        onPrimaryContainer = themeColor(com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFFFFD7EC.toInt()),
        secondary = themeColor(com.google.android.material.R.attr.colorSecondary, 0xFFB7C7FF.toInt()),
        onSecondary = themeColor(com.google.android.material.R.attr.colorOnSecondary, 0xFF1F293D.toInt()),
        secondaryContainer = themeColor(com.google.android.material.R.attr.colorSecondaryContainer, 0xFF3D4563.toInt()),
        onSecondaryContainer = themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer, 0xFFE0E6FF.toInt()),
        background = themeColor(com.google.android.material.R.attr.colorSurface, 0xFF1C1B20.toInt()),
        surface = themeColor(com.google.android.material.R.attr.colorSurface, 0xFF1C1B20.toInt()),
        surfaceVariant = themeColor(com.google.android.material.R.attr.colorSurfaceVariant, 0xFF302830.toInt()),
        surfaceContainer = themeColor(com.google.android.material.R.attr.colorSurfaceContainer, 0xFF262429.toInt()),
        surfaceContainerHigh = themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF2E2B31.toInt()),
        onSurface = themeColor(com.google.android.material.R.attr.colorOnSurface, 0xFFF2EEF5.toInt()),
        onSurfaceVariant = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFD0C7D2.toInt()),
        outline = themeColor(com.google.android.material.R.attr.colorOutline, 0xFF938F99.toInt()),
        outlineVariant = themeColor(com.google.android.material.R.attr.colorOutlineVariant, 0xFF4A4450.toInt())
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(10.dp),
        small = RoundedCornerShape(14.dp),
        medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(30.dp)
    )
    MaterialTheme(colorScheme = colors, shapes = shapes, content = content)
}

@Composable
private fun MangaDetailV3Screen(
    initial: MangaPost?,
    refreshKey: Int,
    onRefreshFinished: () -> Unit,
    onCanRefreshChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onChapterClick: (MangaPost, MangaChapter, List<MangaChapter>) -> Unit,
    onMangaClick: (MangaPost) -> Unit,
    onGenreClick: (String, String, String, String) -> Unit
) {
    val context = LocalContext.current
    val detailViewModel = remember(context) { ViewModelProvider(context as FragmentActivity).get(MangaDetailViewModel::class.java) }
    val prefs = remember { context.getSharedPreferences("miku_detail_chapter_prefs", 0) }
    val lifecycleOwner = context as? LifecycleOwner
    val scope = rememberCoroutineScope()

    var manga by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf(initial) }
    var chapters by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf<List<MangaChapter>>(emptyList()) }
    var genres by remember(initial?.getSourceId()) { mutableStateOf<List<KomikcastClient.GenreItem>>(emptyList()) }
    var extrasLoaded by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf(false) }
    var loading by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf(true) }
    var errorText by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf<String?>(null) }
    var isFavorite by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf(initial?.let { MangaFavoriteManager.isFavorite(context, it) } ?: false) }
    var chapterDescending by remember { mutableStateOf(prefs.getBoolean("global_chapter_order_newest_first", false)) }
    var chapterGrid by remember { mutableStateOf(MangaSettingsManager.isChapterGrid2(context)) }
    var historyVersion by remember(initial?.slug, initial?.getSourceId()) { mutableIntStateOf(0) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    var serupaItems by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf<List<MangaPost>>(emptyList()) }
    var serupaPage by remember(initial?.slug, initial?.getSourceId()) { mutableIntStateOf(0) }
    var serupaLoadingMore by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf(false) }
    var serupaEndReached by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf(false) }
    val serupaSeenKeys = remember(initial?.slug, initial?.getSourceId()) { HashSet<String>() }
    var serupaFilters by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf<List<String>?>(null) }

    val infoListState = rememberLazyListState()
    val chapterListState = rememberLazyListState()
    val serupaGridState = rememberLazyGridState()

    DisposableEffect(initial?.slug, initial?.getSourceId(), lifecycleOwner) {
        val historyPrefs = context.getSharedPreferences("miku_manga_history", 0)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "items" || key == "chapter_progress") historyVersion++
        }
        val roomListener = MangaRoomEvents.Listener { historyVersion++ }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) historyVersion++
        }
        historyPrefs.registerOnSharedPreferenceChangeListener(listener)
        MangaRoomEvents.addListener(roomListener)
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            historyPrefs.unregisterOnSharedPreferenceChangeListener(listener)
            MangaRoomEvents.removeListener(roomListener)
            lifecycleOwner?.lifecycle?.removeObserver(observer)
        }
    }

    LaunchedEffect(initial?.slug, initial?.getSourceId(), refreshKey) {
        val base = initial
        if (base == null) {
            loading = false
            errorText = "Detail manga gagal dimuat"
            onRefreshFinished()
            return@LaunchedEffect
        }
        loading = true
        errorText = null
        genres = emptyList()
        extrasLoaded = false
        serupaItems = emptyList()
        serupaPage = 0
        serupaEndReached = false
        serupaLoadingMore = false
        serupaSeenKeys.clear()
        serupaFilters = null
        try {
            val loaded = detailViewModel.loadCoreDetailData(base).awaitFuture()
            val detail = loaded.detail ?: base
            detail.totalChapters = maxOf(detail.totalChapters, loaded.chapters.size)
            manga = detail
            chapters = loaded.chapters
            isFavorite = MangaFavoriteManager.isFavorite(context, detail)
            loading = false
            onRefreshFinished()
            try {
                val extras = detailViewModel.loadDetailExtras(detail, false).awaitFuture()
                genres = extras.genres
            } catch (ignored: Throwable) {
            } finally {
                extrasLoaded = true
            }
        } catch (e: Throwable) {
            manga = base
            errorText = e.message ?: "Detail manga gagal dimuat"
            loading = false
            onRefreshFinished()
        }
    }

    val current = manga

    fun requestSerupaNextPage() {
        val active = manga ?: return
        if (serupaLoadingMore || serupaEndReached || !extrasLoaded) return
        val sourceId = active.getSourceId()
        val sort = serupaSortV3(sourceId)
        val typeFilter = serupaTypeFilterV3(sourceId, active.getTypeLabel())
        val genreFilters = serupaGenreFiltersV3(active.genre, genres)
        val primaryFilters: List<String> = when {
            genreFilters.isNotEmpty() -> genreFilters.map { joinFilterV3(it, typeFilter) }
            typeFilter.isNotEmpty() -> listOf(typeFilter)
            else -> listOf("")
        }
        serupaLoadingMore = true
        scope.launch {
            try {
                val nextPage = serupaPage + 1
                var filters = serupaFilters ?: primaryFilters
                var result = fetchSerupaPageV3(context, active, sort, filters, nextPage, serupaSeenKeys)
                if (nextPage == 1 && result.first.isEmpty()) {
                    if (genreFilters.isNotEmpty() && typeFilter.isNotEmpty()) {
                        filters = genreFilters
                        result = fetchSerupaPageV3(context, active, sort, filters, nextPage, serupaSeenKeys)
                    }
                    if (result.first.isEmpty()) {
                        filters = listOf("")
                        result = fetchSerupaPageV3(context, active, sort, filters, nextPage, serupaSeenKeys)
                    }
                }
                serupaFilters = filters
                serupaItems = serupaItems + result.first
                serupaPage = nextPage
                if (!result.second || nextPage >= 12) serupaEndReached = true
            } catch (e: Throwable) {
                serupaEndReached = true
            } finally {
                serupaLoadingMore = false
            }
        }
    }

    LaunchedEffect(serupaGridState) {
        snapshotFlow {
            val info = serupaGridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }.collect { (lastIndex, total) ->
            if (selectedTab == 2 && total > 0 && lastIndex >= total - 4) requestSerupaNextPage()
        }
    }

    LaunchedEffect(extrasLoaded, selectedTab) {
        if (selectedTab == 2 && extrasLoaded && serupaItems.isEmpty() && !serupaEndReached) {
            requestSerupaNextPage()
        }
    }

    LaunchedEffect(selectedTab) {
        snapshotFlow {
            when (selectedTab) {
                0 -> infoListState.firstVisibleItemIndex == 0 && infoListState.firstVisibleItemScrollOffset == 0
                1 -> chapterListState.firstVisibleItemIndex == 0 && chapterListState.firstVisibleItemScrollOffset == 0
                else -> serupaGridState.firstVisibleItemIndex == 0 && serupaGridState.firstVisibleItemScrollOffset == 0
            }
        }.collect { onCanRefreshChange(it) }
    }

    val doShare: () -> Unit = {
        current?.let { active ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, mangaDetailShareUrlV3(context, active))
            }
            context.startActivity(Intent.createChooser(intent, null))
        }
    }
    val doFavorite: () -> Unit = {
        current?.let { active ->
            val favoritePost = favoriteSnapshotForV3(active, chapters)
            MangaFavoriteManager.toggle(context, favoritePost)
            isFavorite = MangaFavoriteManager.isFavorite(context, favoritePost)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (selectedTab != 0 && current != null) {
                DetailTopBarV3(
                    title = current.title.ifBlank { "Detail Manga" },
                    isFavorite = isFavorite,
                    loading = loading,
                    onBack = onBack,
                    onShare = doShare,
                    onFavorite = doFavorite
                )
            }
        },
        floatingActionButton = {
            val active = current
            if (!loading && active != null && chapters.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { onChapterClick(active, startChapterV3(context, active, chapters), chapters) },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    text = { Text(startChapterTextV3(context, active, chapters, historyVersion)) }
                )
            } else if (loading && active != null) {
                // Ghost shimmer Extended FAB: bentuk & posisi sama persis seperti FAB asli
                // ("Lanjut Chapter X" / "Mulai Membaca") supaya transisi loading -> selesai mulus.
                MangaShimmerBlock(Modifier.width(176.dp).height(56.dp), rememberMangaShimmerProgress(), 16.dp)
            }
        }
    ) { padding ->
        when {
            loading -> MangaDetailSkeletonV3(Modifier.padding(padding), selectedTab, onBack)
            current == null -> EmptyStateV3(errorText ?: "Detail manga gagal dimuat", Modifier.padding(padding))
            selectedTab == 0 -> InfoTabV3(
                manga = current,
                chapters = chapters,
                genres = genres,
                chapterCount = chapters.size,
                selectedTab = selectedTab,
                onTabSelect = { selectedTab = it },
                isFavorite = isFavorite,
                onBack = onBack,
                onShare = doShare,
                onFavorite = doFavorite,
                onGenreClick = onGenreClick,
                listState = infoListState,
                modifier = Modifier.padding(padding)
            )
            selectedTab == 1 -> ChapterTabV3(
                manga = current,
                chapters = chapters,
                chapterDescending = chapterDescending,
                chapterGrid = chapterGrid,
                historyVersion = historyVersion,
                selectedTab = selectedTab,
                chapterCount = chapters.size,
                onTabSelect = { selectedTab = it },
                onToggleOrder = {
                    chapterDescending = !chapterDescending
                    prefs.edit().putBoolean("global_chapter_order_newest_first", chapterDescending).apply()
                },
                onToggleLayout = {
                    val nextGrid = !chapterGrid
                    chapterGrid = nextGrid
                    MangaSettingsManager.setChapterLayout(context, if (nextGrid) MangaSettingsManager.CHAPTER_LAYOUT_GRID_2 else MangaSettingsManager.CHAPTER_LAYOUT_DEFAULT)
                },
                onChapterClick = onChapterClick,
                listState = chapterListState,
                modifier = Modifier.padding(padding)
            )
            else -> SerupaTabV3(
                selectedTab = selectedTab,
                chapterCount = chapters.size,
                onTabSelect = { selectedTab = it },
                serupaItems = serupaItems,
                serupaLoadingMore = serupaLoadingMore,
                serupaEndReached = serupaEndReached,
                extrasLoaded = extrasLoaded,
                onMangaClick = onMangaClick,
                gridState = serupaGridState,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun InfoTabV3(
    manga: MangaPost,
    chapters: List<MangaChapter>,
    genres: List<KomikcastClient.GenreItem>,
    chapterCount: Int,
    selectedTab: Int,
    onTabSelect: (Int) -> Unit,
    isFavorite: Boolean,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onFavorite: () -> Unit,
    onGenreClick: (String, String, String, String) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 88.dp)
    ) {
        item { DetailBannerV3(manga, isFavorite, onBack, onShare, onFavorite) }
        stickyHeader { DetailTabsV3(selectedTab, chapterCount, onTabSelect) }
        item {
            Column(Modifier.padding(top = 14.dp)) {
                InfoCardV3("Sinopsis") { expanded ->
                    SelectionContainer {
                        Text(
                            manga.synopsis.ifBlank { "Sinopsis belum tersedia" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = if (expanded) Int.MAX_VALUE else 5,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                val infoRows = listOf("Source" to manga.getSourceLabel(), "Judul" to manga.title) +
                    detailRowsV3(manga, chapters.size).filterNot { it.first.equals("Source", true) || it.first.equals("Judul", true) }
                InfoCardV3("Informasi Manga") { expanded ->
                    val visibleRows = if (expanded) infoRows else infoRows.take(((infoRows.size + 1) / 2).coerceAtLeast(1))
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        visibleRows.forEach { InfoRowV3(it.first, it.second) }
                    }
                }
            }
        }
        val genreItems = manga.genre.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (genreItems.isNotEmpty()) {
            item {
                Column(Modifier.padding(top = 16.dp)) {
                    Text(
                        "Genre",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                    )
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        genreItems.forEach { label ->
                            val value = genres.firstOrNull { it.title.equals(label, true) }?.value ?: label
                            GenreChipV3(label) { onGenreClick(manga.getSourceId(), manga.getSourceLabel(), label, value) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterTabV3(
    manga: MangaPost,
    chapters: List<MangaChapter>,
    chapterDescending: Boolean,
    chapterGrid: Boolean,
    historyVersion: Int,
    selectedTab: Int,
    chapterCount: Int,
    onTabSelect: (Int) -> Unit,
    onToggleOrder: () -> Unit,
    onToggleLayout: () -> Unit,
    onChapterClick: (MangaPost, MangaChapter, List<MangaChapter>) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    val shownChapters = remember(chapters, chapterDescending) {
        if (chapterDescending) chapters.sortedByDescending { it.index } else chapters.sortedBy { it.index }
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        stickyHeader {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column {
                    DetailTabsV3(selectedTab, chapterCount, onTabSelect)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Daftar Chapter",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        ChapterIconButtonV3(Icons.Default.SwapVert, onToggleOrder)
                        Spacer(Modifier.width(4.dp))
                        ChapterIconButtonV3(if (chapterGrid) Icons.Default.ViewList else Icons.Default.GridView, onToggleLayout)
                    }
                }
            }
        }
        if (shownChapters.isEmpty()) {
            item { EmptyStateV3("Belum ada chapter", Modifier.fillMaxWidth()) }
        } else if (chapterGrid) {
            items(shownChapters.chunked(2)) { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowItems.forEach { chapter ->
                        ChapterRowV3(manga, chapter, historyVersion, onChapterClick, chapters, Modifier.weight(1f))
                    }
                    repeat(2 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else {
            items(shownChapters, key = { it.index }) { chapter ->
                ChapterRowV3(manga, chapter, historyVersion, onChapterClick, chapters, Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun SerupaTabV3(
    selectedTab: Int,
    chapterCount: Int,
    onTabSelect: (Int) -> Unit,
    serupaItems: List<MangaPost>,
    serupaLoadingMore: Boolean,
    serupaEndReached: Boolean,
    extrasLoaded: Boolean,
    onMangaClick: (MangaPost) -> Unit,
    gridState: LazyGridState,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 88.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        stickyHeader {
            Surface(color = MaterialTheme.colorScheme.background) {
                DetailTabsV3(selectedTab, chapterCount, onTabSelect)
            }
        }
        item(span = { GridItemSpan(3) }) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Manga Serupa",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "Berdasarkan genre",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(serupaItems, key = { it.getSourceId() + "|" + it.slug }) { post ->
            SerupaCardV3(post, onMangaClick)
        }
        if (!extrasLoaded || serupaLoadingMore) {
            items(3) { MangaRelatedSkeletonCard(rememberMangaShimmerProgress()) }
        }
        if (!serupaEndReached) {
            item(span = { GridItemSpan(3) }) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (serupaItems.isEmpty() && !extrasLoaded) "Menyiapkan data..." else "Memuat lebih banyak...",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            if (serupaItems.isEmpty()) {
                item(span = { GridItemSpan(3) }) {
                    EmptyStateV3("Belum ada manga serupa", Modifier.fillMaxWidth())
                }
            } else {
                item(span = { GridItemSpan(3) }) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Semua data sudah dimuat",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailBannerV3(
    manga: MangaPost,
    isFavorite: Boolean,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onFavorite: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val titleText = manga.title.ifBlank { manga.slug.substringAfterLast('/') }
    val backdrop = MaterialTheme.colorScheme.background
    val statusText = manga.status.ifBlank { "-" }
    val statusColor = mangaStatusTextColorV3(statusText)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
            .height(284.dp)
            .clip(RoundedCornerShape(28.dp))
    ) {
        MangaNetworkImageV3(
            url = manga.coverImage,
            sourceId = manga.getSourceId(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().blur(18.dp)
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.42f),
                        Color.Black.copy(alpha = 0.50f),
                        backdrop.copy(alpha = 0.96f)
                    )
                )
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BannerIconButtonV3(Icons.Default.ArrowBack, Color.White, onBack)
            Spacer(Modifier.weight(1f))
            BannerIconButtonV3(Icons.Default.Share, Color.White, onShare)
            BannerIconButtonV3(
                if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                if (isFavorite) MaterialTheme.colorScheme.primary else Color.White,
                onFavorite
            )
        }
        Row(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            MangaNetworkImageV3(
                url = manga.coverImage,
                sourceId = manga.getSourceId(),
                contentDescription = manga.title,
                modifier = Modifier
                    .width(112.dp)
                    .height(166.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(16.dp))
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    titleText,
                    fontSize = 20.sp,
                    lineHeight = 25.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable {
                        clipboardManager.setText(AnnotatedString(titleText))
                        Toast.makeText(context, "Judul disalin", Toast.LENGTH_SHORT).show()
                    }
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoPillV3(manga.getSourceLabel(), highlighted = true)
                    InfoPillV3(manga.getTypeLabel())
                    InfoPillV3(statusText, dotColor = statusColor)
                }
            }
        }
    }
}

@Composable
private fun BannerIconButtonV3(icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.38f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun InfoPillV3(text: String, highlighted: Boolean = false, dotColor: Color? = null) {
    if (text.isBlank()) return
    Surface(
        shape = RoundedCornerShape(99.dp),
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else Color.White.copy(alpha = 0.16f),
        border = if (highlighted) null else BorderStroke(1.dp, Color.White.copy(alpha = 0.24f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (dotColor != null) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(dotColor))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DetailTabsV3(selectedTab: Int, chapterCount: Int, onSelect: (Int) -> Unit) {
    DetailTabsRowV3(selectedTab, listOf("Info", "Chapter $chapterCount", "Serupa"), onSelect)
}

@Composable
private fun DetailTabsRowV3(selectedTab: Int, titles: List<String>, onSelect: (Int) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            Row(Modifier.fillMaxWidth()) {
                titles.forEachIndexed { index, title ->
                    val selected = index == selectedTab
                    Box(modifier = Modifier.weight(1f).clickable { onSelect(index) }) {
                        Text(
                            title,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.Center).padding(vertical = 13.dp)
                        )
                        if (selected) {
                            Box(
                                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        }
    }
}

/**
 * Ghost shimmer baris tab: posisi, ukuran kolom, dan indikator tab aktif SAMA PERSIS
 * seperti [DetailTabsRowV3], hanya label teksnya diganti blok shimmer. Dipakai saat loading
 * supaya skeleton tab terlihat seperti tab asli V3, bukan tab statis.
 */
@Composable
private fun SkeletonTabsV3(selectedTab: Int, progress: State<Float>) {
    val safeSelected = selectedTab.coerceIn(0, 2)
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            Row(Modifier.fillMaxWidth()) {
                // Lebar ghost meniru label asli: "Info" | "Chapter N" (lebih lebar) | "Serupa".
                listOf(64.dp, 96.dp, 64.dp).forEachIndexed { index, w ->
                    Box(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            MangaShimmerBlock(Modifier.width(w).height(14.dp), progress, 6.dp)
                        }
                        if (index == safeSelected) {
                            MangaShimmerBlock(
                                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                                progress,
                                0.dp
                            )
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        }
    }
}

// ───────────────────────── Skeleton V3 ─────────────────────────
// PURE GHOST ala Facebook: seluruh konten dinamis (banner, cover, judul, pills, tab label,
// sinopsis, baris info, genre, chapter, kartu serupa) diganti blok shimmer yang bentuk &
// posisinya SAMA PERSIS dengan UI asli V3. Tidak ada teks/data asli yang ditampilkan saat
// loading — semua shimmer, supaya jelas terlihat "ini shimmer V3" sebelum konten asli muncul.

@Composable
private fun MangaDetailSkeletonV3(modifier: Modifier, selectedTab: Int, onBack: () -> Unit) {
    val progress = rememberMangaShimmerProgress()
    val base = modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .clipToBounds()
    when (selectedTab) {
        0 -> Column(base) {
            SkeletonBannerV3(progress, onBack)
            SkeletonTabsV3(selectedTab, progress)
            Column(Modifier.padding(top = 14.dp)) {
                SkeletonInfoCardV3(96.dp, progress) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(5) { MangaShimmerBlock(Modifier.fillMaxWidth(if (it == 4) 0.6f else 1f).height(14.dp), progress) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                SkeletonInfoCardV3(150.dp, progress) {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        SkeletonInfoRowV3(0.45f, progress)
                        SkeletonInfoRowV3(0.8f, progress)
                        SkeletonInfoRowV3(0.3f, progress)
                        SkeletonInfoRowV3(0.7f, progress)
                    }
                }
            }
            Column(Modifier.padding(top = 16.dp)) {
                Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
                    MangaShimmerBlock(Modifier.width(80.dp).height(20.dp), progress, 6.dp)
                }
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(76, 92, 112, 84).forEach { w ->
                        MangaShimmerBlock(Modifier.width(w.dp).height(34.dp), progress, 10.dp)
                    }
                }
            }
        }
        1 -> Column(base, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                SkeletonTabsV3(selectedTab, progress)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MangaShimmerBlock(Modifier.width(160.dp).height(22.dp), progress, 6.dp)
                    Spacer(Modifier.weight(1f))
                    // Ghost tombol urutan & layout: 38.dp rounded 12.dp persis seperti ChapterIconButtonV3.
                    MangaShimmerBlock(Modifier.size(38.dp), progress, 12.dp)
                    Spacer(Modifier.width(4.dp))
                    MangaShimmerBlock(Modifier.size(38.dp), progress, 12.dp)
                }
            }
            if (MangaSettingsManager.isChapterGrid2(LocalContext.current)) {
                repeat(7) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SkeletonChapterRowV3(progress, Modifier.weight(1f))
                        SkeletonChapterRowV3(progress, Modifier.weight(1f))
                    }
                }
            } else {
                repeat(8) { SkeletonChapterRowV3(progress, Modifier.padding(horizontal = 16.dp)) }
            }
        }
        else -> Column(base) {
            SkeletonTabsV3(selectedTab, progress)
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MangaShimmerBlock(Modifier.width(140.dp).height(20.dp), progress, 6.dp)
                Spacer(Modifier.weight(1f))
                MangaShimmerBlock(Modifier.width(110.dp).height(12.dp), progress, 6.dp)
            }
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                repeat(3) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(3) { Box(Modifier.weight(1f)) { MangaRelatedSkeletonCard(progress) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonBannerV3(progress: State<Float>, onBack: () -> Unit) {
    val backdrop = MaterialTheme.colorScheme.background
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
            .height(284.dp)
            .clip(RoundedCornerShape(28.dp))
    ) {
        // Gambar belakang: full shimmer (bukan foto asli) — background ikut shimmer sesuai permintaan.
        MangaShimmerBlock(Modifier.fillMaxSize(), progress, 0.dp)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.42f),
                        Color.Black.copy(alpha = 0.50f),
                        backdrop.copy(alpha = 0.96f)
                    )
                )
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BannerIconButtonV3(Icons.Default.ArrowBack, Color.White, onBack)
            Spacer(Modifier.weight(1f))
            // Ghost tombol share & favorit: lingkaran 40.dp persis seperti BannerIconButtonV3 aslinya.
            // Tombol back tetap asli supaya user bisa kembali saat loading.
            MangaShimmerBlock(Modifier.size(40.dp), progress, 20.dp, onHero = true)
            MangaShimmerBlock(Modifier.size(40.dp), progress, 20.dp, onHero = true)
        }
        Row(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // Cover: shimmer 112×166.dp, sudut 16.dp — persis ukuran cover asli.
            MangaShimmerBlock(
                Modifier
                    .width(112.dp)
                    .height(166.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(16.dp)),
                progress,
                16.dp
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                // Judul: 2 baris shimmer setinggi judul asli (20.sp).
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    MangaShimmerBlock(Modifier.fillMaxWidth(0.92f).height(18.dp), progress, onHero = true)
                    MangaShimmerBlock(Modifier.fillMaxWidth(0.62f).height(18.dp), progress, onHero = true)
                }
                Spacer(Modifier.height(10.dp))
                // Pills: Source + Tipe + Status — susunan & ukuran sama seperti aslinya.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MangaShimmerBlock(Modifier.width(72.dp).height(24.dp), progress, 99.dp, onHero = true)
                    MangaShimmerBlock(Modifier.width(58.dp).height(24.dp), progress, 99.dp, onHero = true)
                    MangaShimmerBlock(Modifier.width(70.dp).height(24.dp), progress, 99.dp, onHero = true)
                }
            }
        }
    }
}

@Composable
private fun SkeletonInfoCardV3(titleWidth: Dp, progress: State<Float>, content: @Composable () -> Unit) {
    // CARDVIEW-NYA SENDIRI ikut kena shimmer: container = blok shimmer 20.dp dengan warna dasar
    // = warna card asli. Judul card dan tombol expand juga jadi shimmer.
    MangaShimmerBlock(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        progress,
        20.dp,
        baseColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MangaShimmerBlock(Modifier.width(titleWidth).height(20.dp), progress, 6.dp)
                Spacer(Modifier.weight(1f))
                MangaShimmerBlock(Modifier.size(24.dp), progress, 12.dp)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SkeletonInfoRowV3(shimmerWidth: Float, progress: State<Float>) {
    // Label baris JUGA shimmer — tidak ada teks asli sama sekali saat loading.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(0.42f)) {
            MangaShimmerBlock(Modifier.width(64.dp).height(14.dp), progress, 6.dp)
        }
        Box(Modifier.weight(0.58f)) {
            MangaShimmerBlock(Modifier.fillMaxWidth(shimmerWidth).height(14.dp), progress)
        }
    }
}

@Composable
private fun SkeletonChapterRowV3(progress: State<Float>, modifier: Modifier = Modifier) {
    // Ghost 1:1 baris chapter asli: container ikut shimmer + judul + label % + tanggal +
    // "Hal. x/y" + progress bar 4.dp — semuanya shimmer.
    MangaShimmerBlock(
        modifier.fillMaxWidth(),
        progress,
        12.dp,
        baseColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MangaShimmerBlock(Modifier.weight(1f).height(14.dp), progress)
                MangaShimmerBlock(Modifier.width(44.dp).height(10.dp), progress)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MangaShimmerBlock(Modifier.fillMaxWidth(0.32f).height(10.dp), progress)
                Spacer(Modifier.weight(1f))
                MangaShimmerBlock(Modifier.width(64.dp).height(10.dp), progress)
            }
            MangaShimmerBlock(Modifier.fillMaxWidth().height(4.dp), progress, 2.dp)
        }
    }
}

@Composable
private fun InfoCardV3(title: String, content: @Composable (Boolean) -> Unit) {
    var expanded by remember(title) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            content(expanded)
        }
    }
}

@Composable
private fun InfoRowV3(label: String, value: String) {
    val valueColor = if (label.contains("status", true)) mangaStatusTextColorV3(value) else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
        Text(value.ifBlank { "-" }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.58f), color = valueColor)
    }
}

@Composable
private fun GenreChipV3(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun DetailTopBarV3(
    title: String,
    isFavorite: Boolean,
    loading: Boolean,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onFavorite: () -> Unit
) {
    val progress = rememberMangaShimmerProgress()
    Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChapterIconButtonV3(Icons.Default.ArrowBack, onBack, 34.dp)
            Spacer(Modifier.width(4.dp))
            if (loading) {
                // Ghost top bar: judul + tombol share/favorit jadi shimmer (back tetap asli).
                MangaShimmerBlock(Modifier.width(180.dp).height(18.dp), progress, 6.dp)
                Spacer(Modifier.weight(1f))
                MangaShimmerBlock(Modifier.size(38.dp), progress, 12.dp)
                Spacer(Modifier.width(4.dp))
                MangaShimmerBlock(Modifier.size(38.dp), progress, 12.dp)
            } else {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                ChapterIconButtonV3(Icons.Default.Share, onShare)
                ChapterIconButtonV3(
                    if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    onFavorite,
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun ChapterIconButtonV3(icon: ImageVector, onClick: () -> Unit, size: Dp = 38.dp, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun SerupaCardV3(post: MangaPost, onClick: (MangaPost) -> Unit) {
    Column(Modifier.clickable { onClick(post) }) {
        MangaNetworkImageV3(
            url = post.coverImage,
            sourceId = post.getSourceId(),
            contentDescription = post.title,
            modifier = Modifier.fillMaxWidth().aspectRatio(0.68f).clip(RoundedCornerShape(12.dp))
        )
        Spacer(Modifier.height(6.dp))
        Text(
            post.title.ifBlank { "-" },
            fontSize = 12.5.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ChapterRowV3(manga: MangaPost, chapter: MangaChapter, historyVersion: Int, onChapterClick: (MangaPost, MangaChapter, List<MangaChapter>) -> Unit, chapters: List<MangaChapter>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val progress = remember(historyVersion, manga.slug, manga.getSourceId(), chapter.index) { MangaHistoryManager.getProgress(context, manga, chapter.index) }
    val fraction = remember(progress?.page, progress?.totalPages) {
        val total = progress?.totalPages ?: 0
        if (progress != null && total > 0) ((progress.page + 1).toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    }
    val percent = remember(fraction) { (fraction * 100f).toInt().coerceIn(0, 100) }
    val isFinished = progress != null && progress.totalPages > 0 && percent >= 100
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
        modifier = modifier
            .fillMaxWidth()
            .clickable { onChapterClick(manga, chapter, chapters) }
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    chapter.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (progress != null && progress.totalPages > 0) {
                    Text(
                        if (isFinished) "✓ Selesai" else "$percent%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            }
            if (chapter.date.isNotBlank() || progress != null && progress.totalPages > 0) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (chapter.date.isNotBlank()) {
                        Text(
                            chapter.date,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (progress != null && progress.totalPages > 0) {
                        Text(
                            "Hal. ${(progress.page + 1).coerceAtMost(progress.totalPages)}/${progress.totalPages}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
            if (progress != null && progress.totalPages > 0) {
                LinearProgressIndicator(
                    progress = fraction,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun EmptyStateV3(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MangaNetworkImageV3(url: String, sourceId: String, contentDescription: String?, modifier: Modifier = Modifier) {
    SubcomposeAsyncImage(
        model = mangaImageRequestV3(url, sourceId),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop,
    ) {
        when (painter.state) {
            is coil.compose.AsyncImagePainter.State.Loading -> MangaShimmerBlock(Modifier.fillMaxSize(), rememberMangaShimmerProgress(), 0.dp)
            is coil.compose.AsyncImagePainter.State.Error -> MangaBrokenImage(Modifier.fillMaxSize())
            else -> SubcomposeAsyncImageContent()
        }
    }
}

@Composable
private fun mangaImageRequestV3(url: String, sourceId: String): Any {
    val context = LocalContext.current
    return remember(url, sourceId) {
        if (url.isBlank()) {
            return@remember url
        }
        val resolvedUrl = MangaImageLoader.resolveImageUrl(url, sourceId)
        val requestKey = MangaImageLoader.imageCacheKey(url, sourceId)
        val builder = ImageRequest.Builder(context)
            .data(resolvedUrl)
            .memoryCacheKey(requestKey)
            .diskCacheKey(requestKey)
            .crossfade(true)
        val headers = MangaImageLoader.headersFor(resolvedUrl, sourceId)
        headers.names().forEach { name ->
            val value = headers[name]
            if (value != null) builder.setHeader(name, value)
        }
        builder.build()
    }
}

private fun serupaFormatTokenV3(typeLabel: String): String {
    val format = when (typeLabel.trim().uppercase(java.util.Locale.ROOT)) {
        "MANHWA" -> "manhwa"
        "MANHUA" -> "manhua"
        "MANGATOON" -> "mangatoon"
        "DOUJINSHI" -> "doujinshi"
        "IMAGE-SET" -> "image-set"
        "ONESHOT", "ONE-SHOT" -> "one-shot"
        "COMIC" -> "comic"
        "NOVEL" -> "novel"
        "MANGA" -> "manga"
        else -> ""
    }
    return if (format.isEmpty()) "" else "type:$format"
}

private fun joinFilterV3(genre: String, type: String): String =
    if (genre.isEmpty()) type else if (type.isEmpty()) genre else "$genre|$type"

private fun normalizeKeyV3(value: String): String =
    value.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }

private fun serupaSortV3(sourceId: String): String = when (sourceId) {
    MangaSettingsManager.MANGA_SOURCE_KOMIKCAST,
    MangaSettingsManager.MANGA_SOURCE_KOMIKTAP,
    MangaSettingsManager.MANGA_SOURCE_MANHWAINDO,
    MangaSettingsManager.MANGA_SOURCE_SOULSCANS,
    MangaSettingsManager.MANGA_SOURCE_KUROMANGA,
    MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK,
    MangaSettingsManager.MANGA_SOURCE_IKIRU,
    MangaSettingsManager.MANGA_SOURCE_LUVYAA,
    MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN -> "popular"
    MangaSettingsManager.MANGA_SOURCE_AINZSCANSS -> "views"
    MangaSettingsManager.MANGA_SOURCE_COMICASO -> "latest"
    else -> "popularity"
}

private fun serupaTypeFilterV3(sourceId: String, typeLabel: String): String {
    val token = serupaFormatTokenV3(typeLabel)
    if (token.isEmpty()) return ""
    val value = token.removePrefix("type:")
    return when (sourceId) {
        MangaSettingsManager.MANGA_SOURCE_SHINIGAMI -> "format:$value"
        MangaSettingsManager.MANGA_SOURCE_DOUJINDESU -> token
        MangaSettingsManager.MANGA_SOURCE_NGOMIK,
        MangaSettingsManager.MANGA_SOURCE_COMICASO -> ""
        MangaSettingsManager.MANGA_SOURCE_IKIRU,
        MangaSettingsManager.MANGA_SOURCE_KOMIKU,
        MangaSettingsManager.MANGA_SOURCE_MANGASUSU,
        MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG,
        MangaSettingsManager.MANGA_SOURCE_COSMICSCANS,
        MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL,
        MangaSettingsManager.MANGA_SOURCE_NATSU,
        MangaSettingsManager.MANGA_SOURCE_AINZSCANSS,
        MangaSettingsManager.MANGA_SOURCE_APKOMIK,
        MangaSettingsManager.MANGA_SOURCE_KOMIKINDO,
        MangaSettingsManager.MANGA_SOURCE_CROTPEDIA,
        MangaSettingsManager.MANGA_SOURCE_MGKOMIK,
        MangaSettingsManager.MANGA_SOURCE_KOMIKTAP,
        MangaSettingsManager.MANGA_SOURCE_MANHWAINDO,
        MangaSettingsManager.MANGA_SOURCE_SOULSCANS,
        MangaSettingsManager.MANGA_SOURCE_KUROMANGA,
        MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK,
        MangaSettingsManager.MANGA_SOURCE_KOMIKCAST,
        MangaSettingsManager.MANGA_SOURCE_LUVYAA,
        MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN -> token
        else -> ""
    }
}

private fun serupaGenreFiltersV3(genreText: String, genres: List<KomikcastClient.GenreItem>): List<String> {
    val labels = genreText.split(",", "|", "/").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (labels.isEmpty()) return emptyList()
    val usable = genres.filter { it.value.isNotBlank() && !it.value.startsWith("type:") && !it.value.startsWith("status:") }
    val out = LinkedHashSet<String>()
    for (label in labels) {
        val key = normalizeKeyV3(label)
        if (key.isEmpty()) continue
        val match = usable.firstOrNull { normalizeKeyV3(it.title) == key }
            ?: usable.firstOrNull { normalizeKeyV3(it.value) == key }
        if (match != null) out.add(match.value) else if (usable.isEmpty()) out.add(label)
        if (out.size >= 3) break
    }
    return out.toList()
}

private suspend fun fetchSerupaPageV3(
    context: Context,
    active: MangaPost,
    sort: String,
    filters: List<String>,
    page: Int,
    seen: MutableSet<String>
): Pair<List<MangaPost>, Boolean> {
    val sourceId = active.getSourceId()
    val sourceLabel = active.getSourceLabel()
    var anyHasNext = false
    val perStream = ArrayList<List<MangaPost>>()
    for (filter in filters) {
        val result = try {
            MangaRepository.list(sourceId, page, sort, "", filter)
        } catch (e: Throwable) { null } ?: continue
        if (result.hasNext) anyHasNext = true
        val items = ArrayList<MangaPost>()
        result.data.forEach { post ->
            val item = post.withSource(sourceId, sourceLabel)
            MangaLabelUtils.applyHiddenLabels(context, item)
            val key = item.getSourceId() + "|" + item.slug
            if (item.slug != active.slug && seen.add(key)) items.add(item)
        }
        if (items.isNotEmpty()) perStream.add(items)
    }
    val merged = ArrayList<MangaPost>()
    val iters = perStream.map { it.iterator() }
    var addedAny = true
    while (addedAny) {
        addedAny = false
        for (iter in iters) {
            if (iter.hasNext()) { merged.add(iter.next()); addedAny = true }
        }
    }
    return merged to anyHasNext
}

private fun detailRowsV3(manga: MangaPost, totalChapters: Int): List<Pair<String, String>> {
    val rows = ArrayList<Pair<String, String>>()
    val used = HashSet<String>()
    val soulScans = manga.getSourceId() == MangaSettingsManager.MANGA_SOURCE_SOULSCANS
    fun add(label: String, value: String) {
        val cleanLabel = label.trim()
        val clean = value.trim()
        val key = cleanLabel.lowercase()
        if (key == "source" || key == "sumber" || key == "judul") return
        if (soulScans && soulScansHiddenDetailKeyV3(key)) return
        if (clean.isEmpty() || used.contains(key)) return
        used.add(key)
        rows.add(cleanLabel to clean)
    }
    manga.info.replace("\n", "||").split("||").flatMap { row ->
        val parts = row.split("|").map { it.trim() }.filter { it.contains(":") }
        if (parts.size > 1) parts else listOf(row)
    }.forEach { raw ->
        val idx = raw.indexOf(':')
        if (idx > 0) add(raw.substring(0, idx), raw.substring(idx + 1))
    }
    add("Author", manga.author.ifBlank { "-" })
    add("Status", manga.status.ifBlank { "-" })
    add("Tipe", manga.getTypeLabel())
    add("Total Chapter", totalChapters.toString())
    return rows
}

private fun soulScansHiddenDetailKeyV3(key: String): Boolean {
    return key == "author" ||
        key == "rating" ||
        key == "rating count" ||
        key == "views" ||
        key == "uploader" ||
        key == "readers" ||
        key == "language" ||
        key == "followers"
}

private fun mangaStatusTextColorV3(status: String): Color = when {
    status.contains("ongoing", true) || status.contains("berjalan", true) || status.contains("publishing", true) -> Color(0xFF66BB6A)
    status.contains("complete", true) || status.contains("tamat", true) || status.contains("finished", true) || status.equals("end", true) -> Color(0xFF64B5F6)
    status.contains("hiatus", true) || status.contains("jeda", true) -> Color(0xFFFFB74D)
    status.contains("cancel", true) || status.contains("drop", true) || status.contains("batal", true) -> Color(0xFFEF5350)
    status.contains("upcoming", true) || status.contains("segera", true) -> Color(0xFFBA68C8)
    else -> Color(0xFF90A4AE)
}

private fun mangaDetailShareUrlV3(context: Context, manga: MangaPost): String {
    val raw = manga.slug.trim()
    if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) return raw
    val source = manga.getSourceId()
    val domain = MangaSettingsManager.getSourceDomain(context, source).trimEnd('/')
    var path = raw.substringAfter("::", raw).trim()
    if (path.isBlank()) return domain
    if (!path.startsWith('/')) {
        path = when {
            path.contains('/') -> "/$path"
            source == MangaSettingsManager.MANGA_SOURCE_KOMIKCAST -> "/komik/$path/"
            source == MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL || source == MangaSettingsManager.MANGA_SOURCE_NATSU -> "/manga/$path/"
            else -> "/$path"
        }
    }
    return domain + path
}

private fun favoriteSnapshotForV3(manga: MangaPost, chapters: List<MangaChapter>): MangaPost {
    val copy = MangaPost(
        manga.slug,
        manga.title,
        manga.coverImage,
        manga.author,
        manga.status,
        manga.synopsis,
        manga.genre,
        manga.getTypeLabel(),
        compactFavoriteChapterV3(manga.latestChapter),
        manga.latestChapterDate
    ).withSource(manga.getSourceId(), manga.getSourceLabel())
    copy.info = manga.info
    copy.totalChapters = kotlin.math.max(manga.totalChapters, chapters.size)
    val newest = chapters.maxByOrNull { it.index }
    if (newest != null) {
        copy.latestChapter = MangaChapter.formatIndex(newest.index)
        copy.latestChapterDate = newest.date.orEmpty()
    }
    return copy
}

private fun compactFavoriteChapterV3(value: String?): String {
    return value.orEmpty()
        .trim()
        .replace(Regex("(?i)^chapter\\s+"), "")
        .replace(Regex("(?i)^ch\\.?\\s*"), "")
        .trim()
}

private fun startChapterV3(context: android.content.Context, manga: MangaPost, chapters: List<MangaChapter>): MangaChapter {
    val resume = MangaHistoryManager.getLastReadChapterIndex(context, manga)
    if (resume >= 0f) chapters.firstOrNull { kotlin.math.abs(it.index - resume) < 0.001f }?.let { return it }
    return chapters.minByOrNull { it.index } ?: chapters.first()
}

private fun startChapterTextV3(context: android.content.Context, manga: MangaPost, chapters: List<MangaChapter>, historyVersion: Int): String {
    val resume = MangaHistoryManager.getLastReadChapterIndex(context, manga)
    if (resume >= 0f) {
        val chapter = chapters.firstOrNull { kotlin.math.abs(it.index - resume) < 0.001f }
        val chapterIndex = MangaChapter.formatIndex(chapter?.index ?: resume)
        return "Lanjut Chapter $chapterIndex"
    }
    return "Mulai Membaca"
}

private suspend fun <T> CompletableFuture<T>.awaitFuture(): T = suspendCancellableCoroutine { cont ->
    whenComplete { value, error ->
        if (!cont.isActive) return@whenComplete
        if (error != null) cont.resumeWithException(error) else cont.resume(value)
    }
}
