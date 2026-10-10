package miku.moe.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Divider
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import com.google.android.material.color.MaterialColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine

class MangaDetailV2Fragment : Fragment() {
    private var initialManga: MangaPost? = null
    private var systemBarsApplied = false
    private var previousStatusBarColor = 0
    private var previousNavigationBarColor = 0
    private var previousSystemUiVisibility = 0
    private var previousStatusBarContrastEnforced = true
    private var previousNavigationBarContrastEnforced = true
    private var previousNavigationBarDividerColor = 0

    companion object {
        @JvmStatic
        fun newInstance(manga: MangaPost): MangaDetailV2Fragment {
            val fragment = MangaDetailV2Fragment()
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
        applyV2SystemBars()
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
            MikuMangaDetailV2Theme {
                MangaDetailV2Screen(
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
        applyV2SystemBars()
    }

    override fun onPause() {
        restoreV2SystemBars()
        super.onPause()
    }

    override fun onDestroyView() {
        restoreV2SystemBars()
        super.onDestroyView()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) restoreV2SystemBars() else applyV2SystemBars()
    }

    private fun applyV2SystemBars() {
        val host = activity ?: return
        ThemeManager.applySystemBars(host)
        systemBarsApplied = true
    }

    private fun restoreV2SystemBars() {
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
private fun MikuMangaDetailV2Theme(content: @Composable () -> Unit) {
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
        onSurface = themeColor(com.google.android.material.R.attr.colorOnSurface, 0xFFF2EEF5.toInt()),
        onSurfaceVariant = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFD0C7D2.toInt()),
        outline = themeColor(com.google.android.material.R.attr.colorOutline, 0xFF938F99.toInt())
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MangaDetailV2Screen(
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
    var manga by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf(initial) }
    var chapters by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf<List<MangaChapter>>(emptyList()) }
    var genres by remember(initial?.getSourceId()) { mutableStateOf<List<KomikcastClient.GenreItem>>(emptyList()) }
    var loading by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf(true) }
    var errorText by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf<String?>(null) }
    var isFavorite by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf(initial?.let { MangaFavoriteManager.isFavorite(context, it) } ?: false) }
    var chapterDescending by remember { mutableStateOf(prefs.getBoolean("global_chapter_order_newest_first", false)) }
    var chapterGrid by remember { mutableStateOf(MangaSettingsManager.isChapterGrid2(context)) }
    var historyVersion by remember(initial?.slug, initial?.getSourceId()) { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }.collect { onCanRefreshChange(it) }
    }

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
            }
        } catch (e: Throwable) {
            manga = base
            errorText = e.message ?: "Detail manga gagal dimuat"
            loading = false
            onRefreshFinished()
        }
    }

    val current = manga
    Scaffold(
        topBar = {
            DetailTopBarV2(
                title = current?.title ?: "Detail Manga",
                showActions = !loading && current != null,
                isFavorite = isFavorite,
                loading = loading,
                onBack = onBack,
                onShare = {
                    manga?.let { active ->
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, mangaDetailShareUrlV2(context, active))
                        }
                        context.startActivity(Intent.createChooser(intent, null))
                    }
                },
                onFavorite = {
                    manga?.let { active ->
                        val favoritePost = favoriteSnapshotForV2(active, chapters)
                        MangaFavoriteManager.toggle(context, favoritePost)
                        isFavorite = MangaFavoriteManager.isFavorite(context, favoritePost)
                    }
                }
            )
        },
        floatingActionButton = {
            val active = current
            if (!loading && active != null && chapters.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { onChapterClick(active, startChapter(context, active, chapters), chapters) },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    text = { Text(startChapterText(context, active, chapters, historyVersion)) }
                )
            } else if (loading && active != null) {
                // Ghost shimmer Extended FAB: bentuk & posisi sama persis seperti FAB asli
                // ("Lanjut Chapter X" / "Mulai Membaca") supaya transisi loading -> selesai mulus.
                MangaShimmerBlock(Modifier.width(176.dp).height(56.dp), rememberMangaShimmerProgress(), 16.dp)
            }
        }
    ) { padding ->
        when {
            loading -> MangaDetailSkeletonV2(Modifier.padding(padding))
            current == null -> EmptyStateV2(errorText ?: "Detail manga gagal dimuat", Modifier.padding(padding))
            else -> {
                val genreItems = current.genre.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val shownChapters = remember(chapters, chapterDescending) {
                    if (chapterDescending) chapters.sortedByDescending { it.index } else chapters.sortedBy { it.index }
                }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(padding)
                ) {
                    item { DetailHeroV2(current) }
                    item {
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            InfoCardV2("Sinopsis") { expanded ->
                                SelectionContainer {
                                    Text(
                                        current.synopsis.ifBlank { "Sinopsis belum tersedia" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = if (expanded) Int.MAX_VALUE else 5,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            val infoRows = listOf("Source" to current.getSourceLabel(), "Judul" to current.title) +
                                detailRows(current, chapters.size).filterNot { it.first.equals("Source", true) || it.first.equals("Judul", true) }
                            InfoCardV2("Informasi Manga") { expanded ->
                                val visibleRows = if (expanded) infoRows else infoRows.take(((infoRows.size + 1) / 2).coerceAtLeast(1))
                                visibleRows.forEach { InfoRowV2(it.first, it.second) }
                            }
                        }
                    }
                    if (genreItems.isNotEmpty()) {
                        item {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(genreItems) { label ->
                                    val value = genres.firstOrNull { it.title.equals(label, true) }?.value ?: label
                                    AssistChip(
                                        onClick = { onGenreClick(current.getSourceId(), current.getSourceLabel(), label, value) },
                                        label = { Text(label, maxLines = 1) }
                                    )
                                }
                            }
                        }
                    }
                    stickyHeader {
                        ChapterStickyHeaderV2(
                            chapterGrid = chapterGrid,
                            onToggleOrder = {
                                chapterDescending = !chapterDescending
                                prefs.edit().putBoolean("global_chapter_order_newest_first", chapterDescending).apply()
                            },
                            onToggleLayout = {
                                val nextGrid = !chapterGrid
                                chapterGrid = nextGrid
                                MangaSettingsManager.setChapterLayout(context, if (nextGrid) MangaSettingsManager.CHAPTER_LAYOUT_GRID_2 else MangaSettingsManager.CHAPTER_LAYOUT_DEFAULT)
                            }
                        )
                    }
                    if (chapterGrid) {
                        items(shownChapters.chunked(2)) { rowItems ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowItems.forEach { chapter ->
                                    ChapterRowV2(current, chapter, historyVersion, onChapterClick, chapters, Modifier.weight(1f))
                                }
                                repeat(2 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    } else {
                        items(shownChapters) { chapter ->
                            ChapterRowV2(current, chapter, historyVersion, onChapterClick, chapters, Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailTopBarV2(
    title: String,
    showActions: Boolean,
    isFavorite: Boolean,
    loading: Boolean,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onFavorite: () -> Unit
) {
    val progress = rememberMangaShimmerProgress()
    Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = null)
            }
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
                    modifier = Modifier.weight(1f)
                )
                if (showActions) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onShare() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                    }
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onFavorite() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = null,
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailHeroV2(current: MangaPost) {
    val backgroundColor = MaterialTheme.colorScheme.background
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(278.dp)
    ) {
        MangaNetworkImage(
            url = current.coverImage,
            sourceId = current.getSourceId(),
            contentDescription = current.title,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.48f))
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(92.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            backgroundColor.copy(alpha = 0.92f),
                            backgroundColor
                        )
                    )
                )
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 30.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            MangaNetworkImage(
                url = current.coverImage,
                sourceId = current.getSourceId(),
                contentDescription = current.title,
                modifier = Modifier
                    .width(126.dp)
                    .aspectRatio(0.68f)
                    .clip(RoundedCornerShape(14.dp))
            )
            Spacer(Modifier.width(16.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 20.dp)
            ) {
                val context = LocalContext.current
                val clipboardManager = LocalClipboardManager.current
                val titleText = current.title.ifBlank { current.slug.substringAfterLast('/') }
                Text(
                    titleText,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboardManager.setText(AnnotatedString(titleText))
                            Toast.makeText(context, "Judul disalin", Toast.LENGTH_SHORT).show()
                        }
                )
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfoPillV2(current.getSourceLabel())
                        InfoPillV2(current.getTypeLabel())
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfoPillV2(current.status)
                    }
                }
            }
        }
    }
}

// ───────────────────────── Skeleton V2 ─────────────────────────
// PURE GHOST ala Facebook: seluruh konten dinamis (gambar belakang, cover, judul, pills,
// sinopsis, baris info, genre, chapter) diganti blok shimmer yang bentuk & posisinya SAMA
// PERSIS dengan UI asli V2. Tidak ada teks/data asli yang ditampilkan saat loading —
// semua shimmer, supaya jelas terlihat "ini shimmer V2" sebelum konten asli muncul.

@Composable
private fun MangaDetailSkeletonV2(modifier: Modifier) {
    val progress = rememberMangaShimmerProgress()
    val chapterGrid = MangaSettingsManager.isChapterGrid2(LocalContext.current)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(modifier)
            .clipToBounds(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SkeletonHeroV2(progress)
        Box(Modifier.padding(horizontal = 16.dp)) {
            SkeletonInfoCardV2(96.dp, progress) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(5) { MangaShimmerBlock(Modifier.fillMaxWidth(if (it == 4) 0.6f else 1f).height(14.dp), progress) }
                }
            }
        }
        Box(Modifier.padding(horizontal = 16.dp)) {
            SkeletonInfoCardV2(150.dp, progress) {
                SkeletonInfoRowV2(0.45f, progress)
                SkeletonInfoRowV2(0.8f, progress)
                SkeletonInfoRowV2(0.3f, progress)
                SkeletonInfoRowV2(0.7f, progress)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Ghost chip berbentuk pill (99.dp) persis seperti AssistChip asli.
            listOf(72, 88, 104, 80).forEach { w -> MangaShimmerBlock(Modifier.width(w.dp).height(32.dp), progress, 99.dp) }
        }
        // Header chapter ikut full shimmer: container + teks "Daftar Chapter" + 2 tombol.
        MangaShimmerBlock(
            Modifier.fillMaxWidth(),
            progress,
            0.dp,
            baseColor = MaterialTheme.colorScheme.background,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MangaShimmerBlock(Modifier.width(170.dp).height(24.dp), progress, 6.dp)
                Spacer(Modifier.weight(1f))
                // Ghost tombol urutan & layout: 38.dp rounded 12.dp persis seperti tombol aslinya.
                MangaShimmerBlock(Modifier.size(38.dp), progress, 12.dp)
                Spacer(Modifier.width(4.dp))
                MangaShimmerBlock(Modifier.size(38.dp), progress, 12.dp)
                Spacer(Modifier.width(4.dp))
            }
        }
        if (chapterGrid) {
            repeat(4) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonChapterRowV2(progress, Modifier.weight(1f))
                    SkeletonChapterRowV2(progress, Modifier.weight(1f))
                }
            }
        } else {
            repeat(5) { SkeletonChapterRowV2(progress, Modifier.padding(horizontal = 16.dp)) }
        }
    }
}

@Composable
private fun SkeletonHeroV2(progress: androidx.compose.runtime.State<Float>) {
    val backgroundColor = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxWidth().height(278.dp)) {
        // Gambar belakang: full shimmer (bukan foto asli) — background ikut shimmer sesuai permintaan.
        MangaShimmerBlock(Modifier.fillMaxSize(), progress, 0.dp)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.48f)))
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(92.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, backgroundColor.copy(alpha = 0.92f), backgroundColor)))
        )
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 30.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // Cover: shimmer 126.dp × rasio 0.68, sudut 14.dp — persis ukuran cover asli.
            MangaShimmerBlock(
                Modifier.width(126.dp).aspectRatio(0.68f).clip(RoundedCornerShape(14.dp)),
                progress,
                14.dp
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).padding(bottom = 20.dp)) {
                // Judul: 2 baris shimmer setinggi headline.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MangaShimmerBlock(Modifier.fillMaxWidth(0.92f).height(26.dp), progress, onHero = true)
                    MangaShimmerBlock(Modifier.fillMaxWidth(0.6f).height(26.dp), progress, onHero = true)
                }
                Spacer(Modifier.height(10.dp))
                // Pills: Source + Tipe sebaris, Status di baris kedua — susunan sama seperti aslinya.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MangaShimmerBlock(Modifier.width(76.dp).height(26.dp), progress, 99.dp, onHero = true)
                        MangaShimmerBlock(Modifier.width(60.dp).height(26.dp), progress, 99.dp, onHero = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MangaShimmerBlock(Modifier.width(70.dp).height(26.dp), progress, 99.dp, onHero = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonInfoCardV2(titleWidth: Dp, progress: androidx.compose.runtime.State<Float>, content: @Composable () -> Unit) {
    // CARDVIEW-NYA SENDIRI ikut kena shimmer: container = blok shimmer 16.dp dengan warna dasar
    // = warna card asli. Judul card, divider, dan tombol expand juga jadi shimmer.
    MangaShimmerBlock(
        Modifier.fillMaxWidth(),
        progress,
        16.dp,
        baseColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MangaShimmerBlock(Modifier.width(titleWidth).height(20.dp), progress, 6.dp)
            MangaShimmerBlock(Modifier.fillMaxWidth().height(1.dp), progress, 0.dp)
            content()
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                MangaShimmerBlock(Modifier.size(28.dp), progress, 14.dp)
            }
        }
    }
}

@Composable
private fun SkeletonInfoRowV2(shimmerWidth: Float, progress: androidx.compose.runtime.State<Float>) {
    // Label baris JUGA shimmer — tidak ada teks asli sama sekali saat loading.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(0.42f)) {
            MangaShimmerBlock(Modifier.width(64.dp).height(16.dp), progress, 6.dp)
        }
        Box(Modifier.weight(0.58f)) {
            MangaShimmerBlock(Modifier.fillMaxWidth(shimmerWidth).height(16.dp), progress)
        }
    }
}

@Composable
private fun SkeletonChapterRowV2(progress: androidx.compose.runtime.State<Float>, modifier: Modifier = Modifier) {
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
                MangaShimmerBlock(Modifier.weight(1f).height(16.dp), progress)
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
private fun InfoCardV2(title: String, content: @Composable (Boolean) -> Unit) {
    var expanded by remember(title) { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().animateContentSize().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.62f))
            content(expanded)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun SectionHeaderV2(title: String) {
    Text(title, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
}

@Composable
private fun InfoPillV2(text: String) {
    if (text.isBlank()) return
    Surface(shape = RoundedCornerShape(99.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun InfoRowV2(label: String, value: String) {
    val valueColor = if (label.contains("status", true)) mangaStatusTextColorV2(value) else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
        Text(value.ifBlank { "-" }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.58f), color = valueColor)
    }
}

private fun mangaStatusTextColorV2(status: String): Color = when {
    status.contains("ongoing", true) || status.contains("berjalan", true) || status.contains("publishing", true) -> Color(0xFF66BB6A)
    status.contains("complete", true) || status.contains("tamat", true) || status.contains("finished", true) || status.equals("end", true) -> Color(0xFF64B5F6)
    status.contains("hiatus", true) || status.contains("jeda", true) -> Color(0xFFFFB74D)
    status.contains("cancel", true) || status.contains("drop", true) || status.contains("batal", true) -> Color(0xFFEF5350)
    status.contains("upcoming", true) || status.contains("segera", true) -> Color(0xFFBA68C8)
    else -> Color(0xFF90A4AE)
}

private fun mangaDetailShareUrlV2(context: Context, manga: MangaPost): String {
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

@Composable
private fun MangaMiniGridV2(items: List<MangaPost>, onMangaClick: (MangaPost) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.take(6).chunked(3).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowItems.forEach { MangaCardV2(it, Modifier.weight(1f), onMangaClick) }
                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MangaCardV2(post: MangaPost, modifier: Modifier, onClick: (MangaPost) -> Unit) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).clickable { onClick(post) }) {
        Box {
            val context = LocalContext.current
            MangaNetworkImage(
                url = post.coverImage,
                sourceId = post.getSourceId(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.68f).clip(RoundedCornerShape(18.dp))
            )
            if (!MangaSettingsManager.shouldHideLatestChapterLabel(context) && post.latestChapter.isNotBlank()) {
                Surface(modifier = Modifier.align(Alignment.BottomStart).padding(6.dp), shape = RoundedCornerShape(99.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)) {
                    Text(post.latestChapter, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        Text(post.title.ifBlank { post.slug.substringAfterLast('/') }, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChapterListItemV2(manga: MangaPost, chapter: MangaChapter, historyVersion: Int, onChapterClick: (MangaPost, MangaChapter, List<MangaChapter>) -> Unit, chapters: List<MangaChapter>, modifier: Modifier) {
    ChapterRowV2(manga, chapter, historyVersion, onChapterClick, chapters, modifier)
}

@Composable
private fun ChapterGridItemV2(manga: MangaPost, chapter: MangaChapter, historyVersion: Int, onChapterClick: (MangaPost, MangaChapter, List<MangaChapter>) -> Unit, chapters: List<MangaChapter>, modifier: Modifier) {
    ChapterRowV2(manga, chapter, historyVersion, onChapterClick, chapters, modifier)
}

@Composable
private fun ChapterStickyHeaderV2(
    chapterGrid: Boolean,
    onToggleOrder: () -> Unit,
    onToggleLayout: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.background.copy(alpha = 0.98f), tonalElevation = 6.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Daftar Chapter",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onToggleOrder),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.SwapVert, contentDescription = null)
            }
            Spacer(Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onToggleLayout),
                contentAlignment = Alignment.Center
            ) {
                Icon(if (chapterGrid) Icons.Default.ViewList else Icons.Default.GridView, contentDescription = null)
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun ChapterRowV2(manga: MangaPost, chapter: MangaChapter, historyVersion: Int, onChapterClick: (MangaPost, MangaChapter, List<MangaChapter>) -> Unit, chapters: List<MangaChapter>, modifier: Modifier = Modifier) {
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
private fun EmptyStateV2(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MangaNetworkImage(url: String, sourceId: String, contentDescription: String?, modifier: Modifier = Modifier) {
    SubcomposeAsyncImage(
        model = mangaImageRequest(url, sourceId),
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
private fun mangaImageRequest(url: String, sourceId: String): Any {
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

private suspend fun loadDetail(source: KomikcastClient, base: MangaPost): MangaPost? = suspendCancellableCoroutine { cont ->
    source.detail(base.slug, object : KomikcastClient.Result<MangaPost> {
        override fun onSuccess(data: MangaPost, hasNext: Boolean) {
            if (cont.isActive) cont.resume(data.withSource(base.getSourceId(), base.getSourceLabel()))
        }

        override fun onError(message: String) {
            if (cont.isActive) cont.resume(base)
        }
    })
}

private suspend fun loadChapters(source: KomikcastClient, slug: String): List<MangaChapter> = suspendCancellableCoroutine { cont ->
    source.chapters(slug, object : KomikcastClient.Result<ArrayList<MangaChapter>> {
        override fun onSuccess(data: ArrayList<MangaChapter>, hasNext: Boolean) {
            if (cont.isActive) cont.resume(data)
        }

        override fun onError(message: String) {
            if (cont.isActive) cont.resume(emptyList())
        }
    })
}

private suspend fun loadGenres(source: KomikcastClient): List<KomikcastClient.GenreItem> = suspendCancellableCoroutine { cont ->
    source.genres(object : KomikcastClient.Result<ArrayList<KomikcastClient.GenreItem>> {
        override fun onSuccess(data: ArrayList<KomikcastClient.GenreItem>, hasNext: Boolean) {
            if (cont.isActive) cont.resume(data)
        }

        override fun onError(message: String) {
            if (cont.isActive) cont.resume(emptyList())
        }
    })
}

private suspend fun loadRelated(context: android.content.Context, source: KomikcastClient, manga: MangaPost, genres: List<KomikcastClient.GenreItem>): List<MangaPost> {
    val genreLabels = manga.genre.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(2)
    val genreValues = genreLabels.map { label -> genres.firstOrNull { it.title.equals(label, true) }?.value ?: label }
    val out = LinkedHashMap<String, MangaPost>()
    coroutineScope {
        val jobs = ArrayList<kotlinx.coroutines.Deferred<List<MangaPost>>>()
        genreValues.forEach { value -> jobs.add(async { loadList(source, 1, "latest", "", value) }) }
        jobs.add(async { loadList(source, 1, "popular", "", "") })
        jobs.forEach { job ->
            job.await().forEach { post ->
                val p = post.withSource(manga.getSourceId(), manga.getSourceLabel())
                val key = p.getSourceId() + "|" + p.slug
                if (p.slug != manga.slug && !out.containsKey(key)) out[key] = p
            }
        }
    }
    return out.values.take(6)
}

private suspend fun loadList(source: KomikcastClient, page: Int, sort: String, query: String, genre: String): List<MangaPost> = suspendCancellableCoroutine { cont ->
    source.list(page, sort, query, genre, object : KomikcastClient.Result<ArrayList<MangaPost>> {
        override fun onSuccess(data: ArrayList<MangaPost>, hasNext: Boolean) {
            if (cont.isActive) cont.resume(data)
        }

        override fun onError(message: String) {
            if (cont.isActive) cont.resume(emptyList())
        }
    })
}

private fun detailRows(manga: MangaPost, totalChapters: Int): List<Pair<String, String>> {
    val rows = ArrayList<Pair<String, String>>()
    val used = HashSet<String>()
    val soulScans = manga.getSourceId() == MangaSettingsManager.MANGA_SOURCE_SOULSCANS
    fun add(label: String, value: String) {
        val cleanLabel = label.trim()
        val clean = value.trim()
        val key = cleanLabel.lowercase()
        if (key == "source" || key == "sumber" || key == "judul") return
        if (soulScans && soulScansHiddenDetailKey(key)) return
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

private fun soulScansHiddenDetailKey(key: String): Boolean {
    return key == "author" ||
        key == "rating" ||
        key == "rating count" ||
        key == "views" ||
        key == "uploader" ||
        key == "readers" ||
        key == "language" ||
        key == "followers"
}

private fun favoriteSnapshotForV2(manga: MangaPost, chapters: List<MangaChapter>): MangaPost {
    val copy = MangaPost(
        manga.slug,
        manga.title,
        manga.coverImage,
        manga.author,
        manga.status,
        manga.synopsis,
        manga.genre,
        manga.getTypeLabel(),
        compactFavoriteChapter(manga.latestChapter),
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

private fun compactFavoriteChapter(value: String?): String {
    return value.orEmpty()
        .trim()
        .replace(Regex("(?i)^chapter\\s+"), "")
        .replace(Regex("(?i)^ch\\.?\\s*"), "")
        .trim()
}

private fun startChapter(context: android.content.Context, manga: MangaPost, chapters: List<MangaChapter>): MangaChapter {
    val resume = MangaHistoryManager.getLastReadChapterIndex(context, manga)
    if (resume >= 0f) chapters.firstOrNull { kotlin.math.abs(it.index - resume) < 0.001f }?.let { return it }
    return chapters.minByOrNull { it.index } ?: chapters.first()
}

private fun startChapterText(context: android.content.Context, manga: MangaPost, chapters: List<MangaChapter>, historyVersion: Int): String {
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
