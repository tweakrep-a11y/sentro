package miku.moe.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class MangaHomeFragment extends Fragment {
    private RecyclerView sourceRecyclerView;
    private ProgressBar progressBar;
    private SwipeRefreshLayout swipeRefreshLayout;
    private MangaHomeSectionAdapter adapter;
    private MangaHomeV1Adapter styleV1Adapter;
    private MangaHomeV3Adapter styleV3Adapter;
    private MangaHomeViewModel homeViewModel;
    private final ArrayList<SourceSection> sections = new ArrayList<>();
    private final ArrayList<MangaPost> styleV1Popular = new ArrayList<>();
    private final ArrayList<MangaPost> styleV1Latest = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean homeHistoryDirty = false;
    private String homeHistorySignature = "";
    // Refresh kartu "lanjut baca" di Home otomatis begitu history berubah (baca manga baru / hapus history)
    private final MangaRoomEvents.Listener homeHistoryListener = () -> {
        if (!isAdded() || getView() == null) return;
        if (isHidden() || !isResumed()) { homeHistoryDirty = true; return; }
        refreshHomeHistoryIfChanged();
    };
    private final CloudflareHelper.SolvedListener cloudflareSolvedListener = (host, sourceLabel) -> mainHandler.post(() -> reloadSolvedCloudflareSource(sourceLabel));
    private int generation = 0;
    private int styleV1LatestPage = 1;
    private int styleV1PopularPage = 1;
    private boolean styleV1LatestHasMore = true;
    private boolean styleV1PopularHasMore = true;
    private boolean styleV1Loading;
    private boolean styleV1LoadingPopularMore;
    private boolean styleV1LoadingMore;
    private boolean styleV1CloudflareRequired;
    private final ArrayList<MangaPost> styleV3Rail = new ArrayList<>();
    private String styleV3PopularSort = MangaHomeV3Sorts.DEFAULT;
    private int styleV3RailPage = 1;
    private int styleV3RailRun = 0;
    private boolean styleV3RailHasMore = true;
    private boolean styleV3RailLoading;
    private boolean openingLatestChapter;
    private String styleV1SourceId = "";
    private String styleV1SourceLabel = "";
    private String styleV1Error = "";
    private static final int HOME_LIMIT = 10;
    private static final ArrayList<SourceSection> HOME_CACHE = new ArrayList<>();
    private static boolean homeCacheLoaded = false;
    private static final ArrayList<MangaPost> HOME_V1_POPULAR_CACHE = new ArrayList<>();
    private static final ArrayList<MangaPost> HOME_V1_LATEST_CACHE = new ArrayList<>();
    private static boolean homeV1CacheLoaded = false;
    private static String homeV1CacheSource = "";
    private static int homeV1CachePopularPage = 1;
    private static int homeV1CacheLatestPage = 1;
    private static boolean homeV1CachePopularHasMore = true;
    private static boolean homeV1CacheLatestHasMore = true;

    public static class SourceSection {
        public final String sourceId;
        public final String sourceLabel;
        public final String sort;
        public final ArrayList<MangaPost> items = new ArrayList<>();
        public boolean loading = true;
        public boolean cloudflareRequired = false;
        public boolean finished = false;
        public String errorMessage = "";
        public SourceSection(String sourceId, String sort) {
            this.sourceId = sourceId;
            this.sort = sort == null || sort.trim().isEmpty() ? "latest" : sort.trim();
            String label = MangaSourceFactory.labelForSourceId(sourceId);
            this.sourceLabel = label;
        }
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_manga_home, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        homeViewModel = new ViewModelProvider(this).get(MangaHomeViewModel.class);
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout);
        sourceRecyclerView = view.findViewById(R.id.sourceRecyclerView);
        progressBar = view.findViewById(R.id.progressBar);
        sourceRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false));
        sourceRecyclerView.setItemAnimator(null);
        sourceRecyclerView.setHasFixedSize(false);
        sourceRecyclerView.setItemViewCacheSize(8);
        CloudflareHelper.addSolvedListener(cloudflareSolvedListener);
        MangaRoomEvents.addListener(homeHistoryListener);
        if (swipeRefreshLayout != null) swipeRefreshLayout.setOnRefreshListener(() -> refreshHome(true));
        if (MangaSettingsManager.usesHomeSourcePipeline(requireContext())) {
            setupStyleV1Home();
            return;
        }
        adapter = new MangaHomeSectionAdapter(requireContext(), sections, new MangaHomeSectionAdapter.ActionListener() {
            @Override public void onViewAll(SourceSection section) { openViewAll(section); }
            @Override public void onResolveCloudflare(SourceSection section) { openCloudflare(section); }
            @Override public void onMangaClick(MangaPost post) { if (isAdded()) ((MainActivity) requireActivity()).openMangaDetail(post); }
            @Override public void onChapterClick(MangaPost post) { openLatestChapter(post); }
        });
        sourceRecyclerView.setAdapter(adapter);
        if (homeCacheLoaded) restoreHomeCache(); else refreshHome(true);
    }

    @Override public void onResume() {
        super.onResume();
        if (!isAdded()) return;
        if (MangaSettingsManager.usesHomeSourcePipeline(requireContext())) {
            String currentSource = MangaSettingsManager.getHomeV1Source(requireContext());
            if (!currentSource.equals(styleV1SourceId)) setupStyleV1Home();
            else updateStyleV1Adapter();
            return;
        }
        if (homeCacheLoaded && sections.isEmpty()) restoreHomeCache();
    }

    @Override public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        // Fragment root di-hide/show (bukan pause/resume), jadi kembali dari reader tidak memicu onResume
        if (!hidden && isAdded()) refreshHomeHistoryIfChanged();
    }

    private void refreshHomeHistoryIfChanged() {
        homeHistoryDirty = false;
        if (!isAdded() || getView() == null) return;
        if (!MangaSettingsManager.usesHomeSourcePipeline(requireContext())) return;
        if (styleV1Adapter == null && styleV3Adapter == null) return;
        String signature = historySignature(latestHistoryEntry());
        if (signature.equals(homeHistorySignature)) return;
        updateStyleV1Adapter();
    }

    private String historySignature(MangaHistoryManager.Entry entry) {
        if (entry == null || entry.manga == null) return "none";
        return entry.key + "|" + entry.time + "|" + entry.chapterIndex + "|" + entry.page + "|" + entry.totalPages + "|" + entry.chapterTitle;
    }

    private boolean needsSourceRefresh() {
        ArrayList<String> sourceIds = MangaSourceFactory.enabledSourceIds(requireContext());
        if (sourceIds.size() != sections.size()) return true;
        for (int i = 0; i < sourceIds.size(); i++) if (!sourceIds.get(i).equals(sections.get(i).sourceId)) return true;
        return false;
    }

    private boolean hasCloudflareSection() {
        for (SourceSection section : sections) if (section != null && section.cloudflareRequired) return true;
        return false;
    }

    @Override public void onPause() {
        if (MangaSettingsManager.usesHomeSourcePipeline(requireContext())) saveStyleV1Cache();
        else if (!sections.isEmpty()) saveHomeCache();
        super.onPause();
    }

    @Override public void onDestroyView() {
        if (!sections.isEmpty()) saveHomeCache();
        generation++;
        mainHandler.removeCallbacksAndMessages(null);
        CloudflareHelper.removeSolvedListener(cloudflareSolvedListener);
        MangaRoomEvents.removeListener(homeHistoryListener);
        homeHistorySignature = "";
        homeHistoryDirty = false;
        if (sourceRecyclerView != null) sourceRecyclerView.setAdapter(null);
        sourceRecyclerView = null;
        swipeRefreshLayout = null;
        adapter = null;
        styleV1Adapter = null;
        styleV3Adapter = null;
        openingLatestChapter = false;
        super.onDestroyView();
    }

    public void refreshHome() { refreshHome(false); }

    public void refreshHome(boolean forceNetwork) {
        if (!isAdded()) return;
        if (MangaSettingsManager.usesHomeSourcePipeline(requireContext())) {
            refreshStyleV1Home(forceNetwork);
            return;
        }
        if (!forceNetwork && homeCacheLoaded) { restoreHomeCache(); return; }
        int run = ++generation;
        sections.clear();
        ArrayList<String> sourceIds = MangaSourceFactory.enabledSourceIds(requireContext());
        if (forceNetwork) for (String sourceId : sourceIds) MangaSourceFactory.invalidateSourceCaches(sourceId);
        for (String sourceId : sourceIds) {
            sections.add(new SourceSection(sourceId, "latest"));
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        AtomicInteger remaining = new AtomicInteger(sections.size());
        if (sections.isEmpty()) { if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false); return; }
        for (int i = 0; i < sections.size(); i++) loadSection(sections.get(i), i, run, remaining);
    }

    private void setupStyleV1Home() {
        if (!isAdded() || sourceRecyclerView == null) return;
        styleV1SourceId = MangaSettingsManager.getHomeV1Source(requireContext());
        styleV1SourceLabel = MangaSourceFactory.labelForSourceId(styleV1SourceId);
        MangaHomeV3Adapter.Listener homeListener = new MangaHomeV3Adapter.Listener() {
            @Override public void onMangaClick(MangaPost post) {
                if (isAdded() && requireActivity() instanceof MainActivity) ((MainActivity) requireActivity()).openMangaDetail(post);
            }

            @Override public void onExplore(String kind) {
                if (!isAdded() || !(requireActivity() instanceof MainActivity)) return;
                if ("popular".equals(kind) && styleV3Adapter != null && !MangaHomeV3Sorts.isDefault(styleV3PopularSort)) {
                    ((MainActivity) requireActivity()).openMangaStyleV1Result(styleV1SourceId, styleV1SourceLabel, kind, styleV3PopularSort, styleV3Adapter.getPopularSortLabel());
                } else {
                    ((MainActivity) requireActivity()).openMangaStyleV1Result(styleV1SourceId, styleV1SourceLabel, kind);
                }
            }

            @Override public void onBrowseSource() {
                if (!isAdded() || !(requireActivity() instanceof MainActivity)) return;
                if (styleV1CloudflareRequired) {
                    boolean opened = CloudflareHelper.openResolverForSource(requireContext(), styleV1SourceId, styleV1SourceLabel);
                    if (!opened) Toast.makeText(requireContext(), "Gagal membuka halaman Cloudflare", Toast.LENGTH_SHORT).show();
                } else {
                    ((MainActivity) requireActivity()).openMangaBrowseSource(styleV1SourceId, styleV1SourceLabel, "");
                }
            }

            @Override public void onChangeSource() {
                showStyleV1SourceDialog();
            }

            @Override public void onPopularNearEnd() {
                if (MangaHomeV3Sorts.isDefault(styleV3PopularSort)) loadMoreStyleV1Popular();
                else loadMoreStyleV3Rail();
            }

            @Override public void onSelectPopularSort(String sort) {
                if (!isAdded() || styleV3Adapter == null || sort == null || sort.equals(styleV3PopularSort)) return;
                selectStyleV3PopularSort(sort);
            }

            @Override public void onNeedChapter(MangaPost post) {
                loadStyleV1Chapter(post);
            }

            @Override public void onChapterClick(MangaPost post) {
                openLatestChapter(post);
            }

            @Override public void onContinueRead(MangaHistoryManager.Entry entry) {
                openContinueReading(entry);
            }

            @Override public void onSelectSource(String sourceId) {
                if (!isAdded() || sourceId == null || sourceId.equals(styleV1SourceId)) return;
                MangaSettingsManager.setHomeV1Source(requireContext(), sourceId);
                setupStyleV1Home();
            }
        };
        resetStyleV3Rail();
        if (MangaSettingsManager.isHomeStyleV3(requireContext())) {
            styleV1Adapter = null;
            styleV3Adapter = new MangaHomeV3Adapter(requireContext(), styleV1SourceId, styleV1Popular, styleV1Latest, homeListener);
        } else {
            styleV3Adapter = null;
            styleV1Adapter = new MangaHomeV1Adapter(requireContext(), styleV1SourceLabel, styleV1Popular, styleV1Latest, homeListener);
        }
        GridLayoutManager layoutManager = new GridLayoutManager(requireContext(), 3);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                if (styleV3Adapter != null) return styleV3Adapter.getSpanSize(position);
                return styleV1Adapter == null ? 3 : styleV1Adapter.getSpanSize(position);
            }
        });
        sourceRecyclerView.setLayoutManager(layoutManager);
        sourceRecyclerView.setItemViewCacheSize(4);
        sourceRecyclerView.clearOnScrollListeners();
        sourceRecyclerView.setAdapter(styleV3Adapter != null ? styleV3Adapter : styleV1Adapter);
        sourceRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (styleV3Adapter != null) return;
                if (dy <= 0 || styleV1Loading || styleV1LoadingMore || !styleV1LatestHasMore) return;
                RecyclerView.LayoutManager manager = recyclerView.getLayoutManager();
                if (!(manager instanceof LinearLayoutManager)) return;
                int last = ((LinearLayoutManager) manager).findLastVisibleItemPosition();
                if (last >= Math.max(0, styleV1Adapter.getItemCount() - 2)) loadMoreStyleV1Latest();
            }
        });
        if (homeV1CacheLoaded && styleV1SourceId.equals(homeV1CacheSource)) restoreStyleV1Cache();
        else refreshStyleV1Home(true);
    }

    private void resetStyleV3Rail() {
        styleV3Rail.clear();
        styleV3PopularSort = MangaHomeV3Sorts.DEFAULT;
        styleV3RailPage = 1;
        styleV3RailHasMore = true;
        styleV3RailLoading = false;
        styleV3RailRun++;
        if (styleV3Adapter != null) styleV3Adapter.setPopularSort(MangaHomeV3Sorts.DEFAULT, styleV1Popular);
    }

    private void selectStyleV3PopularSort(String sort) {
        if (!isAdded() || styleV3Adapter == null) return;
        int run = ++styleV3RailRun;
        styleV3PopularSort = sort;
        styleV3Rail.clear();
        styleV3RailPage = 1;
        styleV3RailHasMore = true;
        if (MangaHomeV3Sorts.isDefault(sort)) {
            styleV3PopularSort = MangaHomeV3Sorts.DEFAULT;
            styleV3RailLoading = false;
            styleV3Adapter.setPopularSort(MangaHomeV3Sorts.DEFAULT, styleV1Popular);
            updateStyleV1Adapter();
            return;
        }
        styleV3RailLoading = true;
        styleV3Adapter.setPopularSort(sort, styleV3Rail);
        updateStyleV1Adapter();
        MangaRepository.INSTANCE.listFuture(styleV1SourceId, 1, sort, "", "").whenComplete((result, error) -> {
            if (!isAdded() || run != styleV3RailRun) return;
            styleV3RailLoading = false;
            if (error != null || result == null) {
                styleV3RailHasMore = false;
                styleV1Error = safeMessage(error);
                updateStyleV1Adapter();
                return;
            }
            appendStyleV1(styleV3Rail, result.getData(), 0);
            styleV3RailHasMore = result.getHasNext();
            styleV1Error = "";
            updateStyleV1Adapter();
        });
    }

    private void loadMoreStyleV3Rail() {
        if (!isAdded() || styleV1Loading || styleV3RailLoading || !styleV3RailHasMore || MangaHomeV3Sorts.isDefault(styleV3PopularSort)) return;
        styleV3RailLoading = true;
        int targetPage = styleV3RailPage + 1;
        int run = styleV3RailRun;
        String sort = styleV3PopularSort;
        updateStyleV1Adapter();
        MangaRepository.INSTANCE.listFuture(styleV1SourceId, targetPage, sort, "", "").whenComplete((result, error) -> {
            if (!isAdded() || run != styleV3RailRun) return;
            styleV3RailLoading = false;
            if (error != null || result == null) {
                styleV3RailHasMore = false;
                styleV1Error = safeMessage(error);
                updateStyleV1Adapter();
                return;
            }
            appendStyleV1(styleV3Rail, result.getData(), 0);
            styleV3RailPage = targetPage;
            styleV3RailHasMore = result.getHasNext();
            styleV1Error = "";
            updateStyleV1Adapter();
        });
    }

    private void showStyleV1SourceDialog() {
        if (!isAdded()) return;
        String[] sourceIds = MangaSourceFactory.allSourceIds();
        String[] labels = new String[sourceIds.length];
        int checked = 0;
        String current = MangaSettingsManager.getHomeV1Source(requireContext());
        for (int i = 0; i < sourceIds.length; i++) {
            labels[i] = MangaSourceFactory.labelForSourceId(sourceIds[i]);
            if (sourceIds[i].equals(current)) checked = i;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Pilih source Home v1")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    if (which < 0 || which >= sourceIds.length) return;
                    String selected = sourceIds[which];
                    dialog.dismiss();
                    if (selected.equals(styleV1SourceId)) return;
                    MangaSettingsManager.setHomeV1Source(requireContext(), selected);
                    setupStyleV1Home();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void refreshStyleV1Home(boolean forceNetwork) {
        if (!isAdded()) return;
        String currentSource = MangaSettingsManager.getHomeV1Source(requireContext());
        if (!currentSource.equals(styleV1SourceId) || (styleV1Adapter == null && styleV3Adapter == null)) {
            setupStyleV1Home();
            return;
        }
        if (!forceNetwork && homeV1CacheLoaded && currentSource.equals(homeV1CacheSource)) {
            restoreStyleV1Cache();
            return;
        }
        int run = ++generation;
        if (forceNetwork) MangaSourceFactory.invalidateSourceCaches(styleV1SourceId);
        resetStyleV3Rail();
        styleV1Popular.clear();
        styleV1Latest.clear();
        styleV1PopularPage = 1;
        styleV1LatestPage = 1;
        styleV1PopularHasMore = true;
        styleV1LatestHasMore = true;
        styleV1Loading = true;
        styleV1LoadingPopularMore = false;
        styleV1LoadingMore = false;
        styleV1CloudflareRequired = false;
        styleV1Error = "";
        updateStyleV1Adapter();
        if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(true);
        CompletableFuture<MangaRepository.MangaPage> popularFuture = MangaRepository.INSTANCE.listFuture(styleV1SourceId, 1, "popular", "", "");
        CompletableFuture<MangaRepository.MangaPage> latestFuture = MangaRepository.INSTANCE.listFuture(styleV1SourceId, 1, "latest", "", "");
        CompletableFuture.allOf(popularFuture, latestFuture).whenComplete((unused, combinedError) -> {
            if (!isAdded() || run != generation) return;
            MangaRepository.MangaPage popularPage = completedPage(popularFuture);
            MangaRepository.MangaPage latestPage = completedPage(latestFuture);
            Throwable popularError = completedError(popularFuture);
            Throwable latestError = completedError(latestFuture);
            if (popularPage != null) {
                appendStyleV1(styleV1Popular, popularPage.getData(), HOME_LIMIT + 2);
                styleV1PopularHasMore = popularPage.getHasNext();
            } else {
                styleV1PopularHasMore = false;
            }
            if (latestPage != null) {
                appendStyleV1(styleV1Latest, latestPage.getData(), 0);
                styleV1LatestHasMore = latestPage.getHasNext();
            } else {
                styleV1LatestHasMore = false;
            }
            synchronizeStyleV1Lists();
            Throwable error = popularError != null ? popularError : latestError;
            styleV1Error = error == null ? "" : safeMessage(error);
            styleV1CloudflareRequired = CloudflareHelper.isCloudflareRequiredMessage(styleV1Error) || CloudflareHelper.needsResolution(styleV1SourceLabel);
            if (styleV1CloudflareRequired) styleV1Error = "Harap selesaikan Cloudflare pada source " + styleV1SourceLabel + " melalui baris source di atas";
            styleV1Loading = false;
            if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
            updateStyleV1Adapter();
            saveStyleV1Cache();
        });
    }

    private void loadMoreStyleV1Popular() {
        if (!isAdded() || styleV1Loading || styleV1LoadingPopularMore || !styleV1PopularHasMore) return;
        styleV1LoadingPopularMore = true;
        int targetPage = styleV1PopularPage + 1;
        int run = generation;
        updateStyleV1Adapter();
        MangaRepository.INSTANCE.listFuture(styleV1SourceId, targetPage, "popular", "", "").whenComplete((result, error) -> {
            if (!isAdded() || run != generation) return;
            styleV1LoadingPopularMore = false;
            if (error != null || result == null) {
                styleV1Error = safeMessage(error);
                updateStyleV1Adapter();
                return;
            }
            appendStyleV1(styleV1Popular, result.getData(), 0);
            synchronizeStyleV1Lists();
            styleV1PopularPage = targetPage;
            styleV1PopularHasMore = result.getHasNext();
            styleV1Error = "";
            updateStyleV1Adapter();
            saveStyleV1Cache();
        });
    }

    private void loadMoreStyleV1Latest() {
        if (!isAdded() || styleV1Loading || styleV1LoadingMore || !styleV1LatestHasMore) return;
        styleV1LoadingMore = true;
        int targetPage = styleV1LatestPage + 1;
        int run = generation;
        updateStyleV1Adapter();
        MangaRepository.INSTANCE.listFuture(styleV1SourceId, targetPage, "latest", "", "").whenComplete((result, error) -> {
            if (!isAdded() || run != generation) return;
            styleV1LoadingMore = false;
            if (error != null || result == null) {
                styleV1Error = safeMessage(error);
                updateStyleV1Adapter();
                return;
            }
            appendStyleV1(styleV1Latest, result.getData(), 0);
            synchronizeStyleV1Lists();
            styleV1LatestPage = targetPage;
            styleV1LatestHasMore = result.getHasNext();
            styleV1Error = "";
            updateStyleV1Adapter();
            saveStyleV1Cache();
        });
    }

    private ArrayList<MangaPost> appendStyleV1(ArrayList<MangaPost> target, ArrayList<MangaPost> data, int limit) {
        ArrayList<MangaPost> added = new ArrayList<>();
        HashSet<String> keys = new HashSet<>();
        for (MangaPost existing : target) keys.add(styleV1Key(existing));
        if (data == null) return added;
        for (MangaPost post : data) {
            if (post == null) continue;
            post.withSource(styleV1SourceId, styleV1SourceLabel);
            if (MangaSettingsManager.isHomeStyleV3(getContext())) {
                if (!MangaSettingsManager.shouldLoadTypeLabel(getContext())) post.typeLabel = "";
            } else {
                MangaLabelUtils.applyHiddenLabels(getContext(), post);
            }
            String key = styleV1Key(post);
            if (key.isEmpty() || !keys.add(key)) continue;
            target.add(post);
            added.add(post);
            if (limit > 0 && target.size() >= limit) break;
        }
        return added;
    }

    private String styleV1Key(MangaPost post) {
        if (post == null) return "";
        String value = post.slug == null || post.slug.trim().isEmpty() ? post.title : post.slug;
        return value == null || value.trim().isEmpty() ? "" : post.getSourceId() + "|" + value.trim();
    }

    private void loadStyleV1Chapter(MangaPost post) {
        if (!isAdded() || post == null) return;
        int run = generation;
        MangaLatestChapterResolver.resolve(post, styleV3Adapter != null, (resolved, changed) -> {
            if (!isAdded() || run != generation || resolved == null) return;
            boolean synchronizedData = synchronizeResolvedChapter(resolved);
            if (styleV1Adapter != null) styleV1Adapter.notifyChapterChanged(resolved);
            if (styleV3Adapter != null) styleV3Adapter.notifyChapterChanged(resolved);
            if (changed || synchronizedData) saveStyleV1Cache();
        });
    }

    private boolean synchronizeResolvedChapter(MangaPost resolved) {
        String key = styleV1Key(resolved);
        if (key.isEmpty()) return false;
        boolean changed = false;
        for (MangaPost post : styleV1Popular) changed |= copyResolvedChapter(post, resolved, key);
        for (MangaPost post : styleV1Latest) changed |= copyResolvedChapter(post, resolved, key);
        return changed;
    }

    private boolean copyResolvedChapter(MangaPost target, MangaPost resolved, String key) {
        if (target == null || !key.equals(styleV1Key(target))) return false;
        boolean changed = false;
        String chapter = MangaLatestChapterResolver.normalize(resolved.latestChapter);
        if (!chapter.isEmpty() && !chapter.equals(MangaLatestChapterResolver.normalize(target.latestChapter))) {
            target.latestChapter = chapter;
            changed = true;
        }
        if (resolved.totalChapters > target.totalChapters) {
            target.totalChapters = resolved.totalChapters;
            changed = true;
        }
        String date = resolved.latestChapterDate == null ? "" : resolved.latestChapterDate.trim();
        boolean missingDate = target.latestChapterDate == null || target.latestChapterDate.trim().isEmpty();
        if (!date.isEmpty() && missingDate) {
            target.latestChapterDate = date;
            changed = true;
        }
        return changed;
    }

    private void synchronizeStyleV1Lists() {
        HashMap<String, MangaPost> latestByKey = new HashMap<>();
        for (MangaPost post : styleV1Latest) {
            String key = styleV1Key(post);
            if (!key.isEmpty()) latestByKey.put(key, post);
        }
        for (MangaPost popular : styleV1Popular) {
            MangaPost latest = latestByKey.get(styleV1Key(popular));
            if (latest == null) continue;
            if (latest.title != null && !latest.title.trim().isEmpty()) popular.title = latest.title;
            if (latest.coverImage != null && !latest.coverImage.trim().isEmpty()) popular.coverImage = latest.coverImage;
            if (latest.genre != null && !latest.genre.trim().isEmpty()) popular.genre = latest.genre;
            String chapter = MangaLatestChapterResolver.normalize(latest.latestChapter);
            if (!chapter.isEmpty()) popular.latestChapter = chapter;
            if (latest.totalChapters > popular.totalChapters) popular.totalChapters = latest.totalChapters;
        }
    }

    private MangaRepository.MangaPage completedPage(CompletableFuture<MangaRepository.MangaPage> future) {
        try { return future.getNow(null); } catch (Exception ignored) { return null; }
    }

    private Throwable completedError(CompletableFuture<?> future) {
        if (!future.isCompletedExceptionally()) return null;
        try {
            future.join();
            return null;
        } catch (Throwable error) {
            return error;
        }
    }

    private String safeMessage(Throwable error) {
        if (error == null) return "Gagal memuat manga";
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        return message == null || message.trim().isEmpty() ? "Gagal memuat manga" : message.trim();
    }

    private MangaHistoryManager.Entry latestHistoryEntry() {
        if (!isAdded()) return null;
        MangaHistoryManager.Entry latestEntry = null;
        for (MangaHistoryManager.Entry entry : MangaHistoryManager.entries(requireContext())) {
            if (entry == null || entry.manga == null) continue;
            if (latestEntry == null || entry.time > latestEntry.time) latestEntry = entry;
        }
        return latestEntry;
    }

    private void updateStyleV1Adapter() {
        MangaHistoryManager.Entry latestHistory = latestHistoryEntry();
        homeHistorySignature = historySignature(latestHistory);
        homeHistoryDirty = false;
        if (styleV1Adapter != null) styleV1Adapter.updateState(latestHistory, styleV1Loading, styleV1LoadingPopularMore, styleV1LoadingMore, styleV1Error);
        if (styleV3Adapter != null) styleV3Adapter.updateState(latestHistory, styleV1Loading, styleV1LoadingPopularMore || styleV3RailLoading, styleV1LoadingMore, styleV1Error);
        if (progressBar != null) progressBar.setVisibility(View.GONE);
    }

    private void saveStyleV1Cache() {
        if (styleV1SourceId == null || styleV1SourceId.isEmpty()) return;
        HOME_V1_POPULAR_CACHE.clear();
        HOME_V1_POPULAR_CACHE.addAll(styleV1Popular);
        HOME_V1_LATEST_CACHE.clear();
        HOME_V1_LATEST_CACHE.addAll(styleV1Latest);
        homeV1CacheSource = styleV1SourceId;
        homeV1CachePopularPage = styleV1PopularPage;
        homeV1CacheLatestPage = styleV1LatestPage;
        homeV1CachePopularHasMore = styleV1PopularHasMore;
        homeV1CacheLatestHasMore = styleV1LatestHasMore;
        homeV1CacheLoaded = true;
    }

    private void restoreStyleV1Cache() {
        styleV1Popular.clear();
        styleV1Popular.addAll(HOME_V1_POPULAR_CACHE);
        styleV1Latest.clear();
        styleV1Latest.addAll(HOME_V1_LATEST_CACHE);
        styleV1PopularPage = homeV1CachePopularPage;
        styleV1LatestPage = homeV1CacheLatestPage;
        styleV1PopularHasMore = homeV1CachePopularHasMore;
        styleV1LatestHasMore = homeV1CacheLatestHasMore;
        styleV1Loading = false;
        styleV1LoadingPopularMore = false;
        styleV1LoadingMore = false;
        styleV1CloudflareRequired = false;
        styleV1Error = "";
        synchronizeStyleV1Lists();
        if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
        updateStyleV1Adapter();
    }

    private void loadSection(SourceSection section, int index, int run, AtomicInteger remaining) {
        if (homeViewModel == null) homeViewModel = new ViewModelProvider(this).get(MangaHomeViewModel.class);
        homeViewModel.loadSection(section.sourceId, section.sort).whenComplete((page, error) -> {
            if (!isAdded() || run != generation) return;
            if (error == null && page != null) {
                appendSectionData(section, page.getData());
                section.loading = false;
                section.cloudflareRequired = false;
                section.errorMessage = "";
                if (adapter != null) adapter.notifyItemChanged(index);
                finishSection(section, remaining);
                if (!section.items.isEmpty() && MangaLabelUtils.shouldEnrichLabels(requireContext())) MangaSourceFactory.createBySourceId(section.sourceId).enrichLatest(section.items, () -> {
                    if (isAdded() && run == generation && adapter != null) {
                        for (MangaPost post : section.items) MangaLabelUtils.applyHiddenLabels(requireContext(), post);
                        adapter.notifyItemChanged(index);
                    }
                });
            } else {
                String message = error == null || error.getMessage() == null ? "" : error.getMessage();
                section.loading = false;
                section.errorMessage = message == null ? "" : message.trim();
                section.cloudflareRequired = CloudflareHelper.isCloudflareRequiredMessage(message) || CloudflareHelper.needsResolution(section.sourceLabel);
                if (adapter != null) adapter.notifyItemChanged(index);
                finishSection(section, remaining);
            }
        });
    }

    private void appendSectionData(SourceSection section, ArrayList<MangaPost> data) {
        section.items.clear();
        HashSet<String> slugs = new HashSet<>();
        if (data == null) return;
        for (MangaPost post : data) {
            if (post == null) continue;
            post.withSource(section.sourceId, section.sourceLabel);
            MangaLabelUtils.applyHiddenLabels(getContext(), post);
            String key = post.slug == null || post.slug.trim().isEmpty() ? post.title : post.slug;
            if (key == null || key.trim().isEmpty() || !slugs.add(key.trim())) continue;
            section.items.add(post);
            if (section.items.size() >= HOME_LIMIT) break;
        }
    }

    private void finishSection(SourceSection section, AtomicInteger remaining) {
        if (section.finished) return;
        section.finished = true;
        finishOne(remaining);
    }

    private void finishOne(AtomicInteger remaining) {
        if (remaining.decrementAndGet() <= 0) {
            saveHomeCache();
            if (progressBar != null) progressBar.setVisibility(View.GONE);
            if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
        }
    }

    private void saveHomeCache() {
        HOME_CACHE.clear();
        for (SourceSection section : sections) {
            SourceSection copy = new SourceSection(section.sourceId, section.sort);
            copy.loading = section.loading;
            copy.cloudflareRequired = section.cloudflareRequired;
            copy.finished = section.finished;
            copy.errorMessage = section.errorMessage;
            copy.items.addAll(section.items);
            HOME_CACHE.add(copy);
        }
        homeCacheLoaded = true;
    }

    private void restoreHomeCache() {
        sections.clear();
        for (SourceSection section : HOME_CACHE) {
            SourceSection copy = new SourceSection(section.sourceId, section.sort);
            copy.loading = section.loading;
            copy.cloudflareRequired = section.cloudflareRequired;
            copy.finished = section.finished;
            copy.errorMessage = section.errorMessage;
            copy.items.addAll(section.items);
            sections.add(copy);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        if (progressBar != null) progressBar.setVisibility(View.GONE);
        if (swipeRefreshLayout != null) swipeRefreshLayout.setRefreshing(false);
    }

    private void reloadSolvedCloudflareSource(String sourceLabel) {
        if (!isAdded() || sourceLabel == null || sourceLabel.trim().isEmpty()) return;
        if (MangaSettingsManager.usesHomeSourcePipeline(requireContext())) {
            if (sameSourceLabel(styleV1SourceLabel, sourceLabel)) refreshStyleV1Home(true);
            return;
        }
        for (int i = 0; i < sections.size(); i++) {
            SourceSection section = sections.get(i);
            if (section == null || !section.cloudflareRequired) continue;
            if (!sameSourceLabel(section.sourceLabel, sourceLabel)) continue;
            section.loading = true;
            section.cloudflareRequired = false;
            section.finished = false;
            section.errorMessage = "";
            section.items.clear();
            if (adapter != null) adapter.notifyItemChanged(i);
            loadSection(section, i, generation, new AtomicInteger(1));
            return;
        }
    }

    private boolean sameSourceLabel(String a, String b) {
        String left = a == null ? "" : a.trim();
        String right = b == null ? "" : b.trim();
        return !left.isEmpty() && left.equalsIgnoreCase(right);
    }

    private void openLatestChapter(MangaPost post) {
        if (!isAdded() || openingLatestChapter || post == null || post.slug == null || post.slug.trim().isEmpty()) return;
        openingLatestChapter = true;
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        MangaRepository.INSTANCE.chaptersReliableFuture(post.getSourceId(), post.slug).whenComplete((chapters, error) -> {
            if (!isAdded()) return;
            openingLatestChapter = false;
            if (progressBar != null) progressBar.setVisibility(View.GONE);
            if (error != null) {
                String message = error.getMessage();
                Toast.makeText(requireContext(), message == null || message.trim().isEmpty() ? "Gagal membuka chapter" : message, Toast.LENGTH_SHORT).show();
                return;
            }
            if (chapters == null || chapters.isEmpty()) {
                Toast.makeText(requireContext(), "Chapter terbaru gagal dimuat. Coba lagi.", Toast.LENGTH_SHORT).show();
                return;
            }
            ((MainActivity) requireActivity()).openMangaReader(post, new ArrayList<>(chapters), findChapterPosition(chapters, post.latestChapter));
        });
    }

    /** Tombol play "Lanjutkan membaca": langsung ke reader di chapter history, tanpa lewat Detail Manga. */
    private void openContinueReading(MangaHistoryManager.Entry entry) {
        if (!isAdded() || entry == null || entry.manga == null) return;
        MangaPost manga = entry.manga;
        if (openingLatestChapter || manga.slug == null || manga.slug.trim().isEmpty()) {
            // Data history tidak lengkap: jatuh kembali ke Detail Manga.
            if (!openingLatestChapter) ((MainActivity) requireActivity()).openMangaDetail(manga);
            return;
        }
        openingLatestChapter = true;
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        MangaRepository.INSTANCE.chaptersReliableFuture(manga.getSourceId(), manga.slug).whenComplete((chapters, error) -> {
            if (!isAdded()) return;
            openingLatestChapter = false;
            if (progressBar != null) progressBar.setVisibility(View.GONE);
            if (error != null || chapters == null || chapters.isEmpty()) {
                Toast.makeText(requireContext(), "Daftar chapter gagal dimuat. Coba lagi.", Toast.LENGTH_SHORT).show();
                return;
            }
            int pos = 0;
            for (int i = 0; i < chapters.size(); i++) {
                if (Math.abs(chapters.get(i).index - entry.chapterIndex) < 0.001f) { pos = i; break; }
            }
            manga.totalChapters = chapters.size();
            ((MainActivity) requireActivity()).openMangaReader(manga, new ArrayList<>(chapters), pos);
        });
    }

    private int findChapterPosition(ArrayList<MangaChapter> chapters, String latestChapter) {
        float target = parseChapterIndex(latestChapter);
        if (target >= 0f) {
            for (int i = 0; i < chapters.size(); i++) if (Math.abs(chapters.get(i).index - target) < 0.001f) return i;
        }
        int newest = 0;
        for (int i = 1; i < chapters.size(); i++) if (chapters.get(i).index > chapters.get(newest).index) newest = i;
        return newest;
    }

    private float parseChapterIndex(String text) {
        if (text == null) return -1f;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(text.replace(',', '.'));
        if (!matcher.find()) return -1f;
        try { return Float.parseFloat(matcher.group(1)); } catch (Exception ignored) { return -1f; }
    }

    private void openCloudflare(SourceSection section) {
        if (!isAdded() || section == null) return;
        boolean opened = CloudflareHelper.openResolverForSource(requireContext(), section.sourceId, MangaSourceFactory.labelForSourceId(section.sourceId));
        if (!opened) Toast.makeText(requireContext(), "Gagal membuka halaman Cloudflare", Toast.LENGTH_SHORT).show();
    }

    private void openViewAll(SourceSection section) {
        if (!isAdded() || section == null) return;
        if (!sections.isEmpty()) saveHomeCache();
        if (requireActivity() instanceof MainActivity) ((MainActivity) requireActivity()).openMangaBrowseSource(section.sourceId, MangaSourceFactory.labelForSourceId(section.sourceId), "", section.sort);
    }
}
