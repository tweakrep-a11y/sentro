package miku.moe.app;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

public class MangaReaderFragmentV2 extends Fragment {
    private MangaPost manga;
    private ArrayList<MangaChapter> chapters;
    private int chapterPos;
    private float pendingSelectedChapterIndex = -1f;
    private BottomSheetDialog chapterSheet;
    private RecyclerView readerRecyclerView;
    private LinearLayoutManager readerLayoutManager;
    private MangaReaderPageAdapter pageAdapter;
    private ProgressBar progress;
    private TextView pageIndicator;
    private TextView readerMangaTitle;
    private TextView readerChapterTitle;
    private View readerTopBar;
    private View readerFloatingControls;
    private boolean chapterListDescending = true;
    private KomikcastClient api;
    private int totalPages = 0;
    private boolean restoringScroll = false;
    private int loadVersion = 0;
    private boolean controlsVisible = true;
    private int lastSavedPage = -1;
    private int lastDisplayedPage = -1;
    private int lastScrollProgressAdapterPosition = RecyclerView.NO_POSITION;
    private final Handler readerHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingProgressUpdate;
    private boolean resetPageOnNextChapter = false;
    private boolean keepReaderControlsHiddenOnNextLoad = false;
    private boolean nextChapterSnackbarShown = false;
    private int nextChapterSnackbarChapterPos = -1;
    private Runnable nextChapterAutoRunnable;
    private ReaderNextChapterToast.Handle nextChapterToast;
    private int loadedChapterPos = -1;
    private float loadedChapterIndex = -1f;
    private final HashSet<Integer> inlineLoadingChapterPositions = new HashSet<>();
    private final HashSet<Integer> inlinePreloadedChapterPositions = new HashSet<>();
    private final HashSet<Integer> inlineAppendingChapterPositions = new HashSet<>();
    private final LinkedHashMap<Integer, ArrayList<String>> inlinePreparedChapterPages = new LinkedHashMap<>();
    private final LinkedHashMap<Integer, Boolean> inlineAppendWhenReadyChapterPositions = new LinkedHashMap<>();
    private int lastReadingToastChapterPos = -1;
    private int lastReaderWindowChapterPos = -1;
    private int pendingRestorePage = -1;
    private int pendingRestoreChapterPos = -1;
    private int pendingRestoreVersion = -1;
    private int forcedRestorePage = -1;
    private float forcedRestoreChapterIndex = -1f;
    private boolean closingReader = false;
    private long lastScrollProgressUpdateAt = 0L;
    private Runnable pendingSilentProgressSave;
    private int pendingSilentProgressPage = -1;
    private final MangaReaderImageCachePruner imageCachePruner = new MangaReaderImageCachePruner();
    private boolean readerSessionActive = false;
    private boolean readerExitCacheCleared = false;

    public MangaReaderFragmentV2() {}

    public MangaReaderFragmentV2(MangaPost manga, ArrayList<MangaChapter> chapters, int chapterPos) {
        this.manga = manga;
        float selectedIndex = -1f;
        if (chapters != null && !chapters.isEmpty() && chapterPos >= 0 && chapterPos < chapters.size()) {
            selectedIndex = chapters.get(chapterPos).index;
        }
        this.chapters = normalizeChapters(chapters);
        int found = findChapterPosition(this.chapters, selectedIndex);
        this.chapterPos = found >= 0 ? found : Math.max(0, Math.min(chapterPos, this.chapters == null ? 0 : this.chapters.size() - 1));
    }

    public static MangaReaderFragmentV2 newInstance(MangaPost manga, ArrayList<MangaChapter> chapters, int chapterPos) {
        MangaReaderFragmentV2 fragment = new MangaReaderFragmentV2();
        Bundle args = new Bundle();
        float selectedChapterIndex = -1f;
        if (chapters != null && chapterPos >= 0 && chapterPos < chapters.size()) selectedChapterIndex = chapters.get(chapterPos).index;
        args.putSerializable("manga", manga);
        args.putSerializable("chapters", chapters);
        args.putInt("chapterPos", chapterPos);
        args.putFloat("selectedChapterIndex", selectedChapterIndex);
        fragment.setArguments(args);
        return fragment;
    }

    @Override public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restoreArguments();
        restoreSavedInstanceState(savedInstanceState);
    }

    private void restoreArguments() {
        Bundle args = getArguments();
        if (args == null) return;
        if (manga == null) {
            Object value = args.getSerializable("manga");
            if (value instanceof MangaPost) manga = (MangaPost) value;
        }
        if (chapters == null || chapters.isEmpty()) {
            Object value = args.getSerializable("chapters");
            if (value instanceof ArrayList) {
                try { chapters = normalizeChapters((ArrayList<MangaChapter>) value); } catch (Exception ignored) { chapters = new ArrayList<>(); }
            }
        }
        int requestedPos = Math.max(0, args.getInt("chapterPos", chapterPos));
        float selectedChapterIndex = args.getFloat("selectedChapterIndex", -1f);
        if (chapters == null) chapters = new ArrayList<>();
        int found = findChapterPosition(chapters, selectedChapterIndex);
        if (found >= 0) { chapterPos = found; pendingSelectedChapterIndex = -1f; }
        else { chapterPos = Math.max(0, Math.min(requestedPos, Math.max(0, chapters.size() - 1))); pendingSelectedChapterIndex = selectedChapterIndex; }
    }

    private void restoreSavedInstanceState(@Nullable Bundle state) {
        if (state == null) return;
        Object mangaValue = state.getSerializable("manga");
        if (mangaValue instanceof MangaPost) manga = (MangaPost) mangaValue;
        Object chaptersValue = state.getSerializable("chapters");
        if (chaptersValue instanceof ArrayList) {
            try { chapters = normalizeChapters((ArrayList<MangaChapter>) chaptersValue); } catch (Exception ignored) { if (chapters == null) chapters = new ArrayList<>(); }
        }
        if (chapters == null) chapters = new ArrayList<>();
        int requestedPos = Math.max(0, state.getInt("chapterPos", chapterPos));
        float selectedChapterIndex = state.getFloat("selectedChapterIndex", -1f);
        int found = findChapterPosition(chapters, selectedChapterIndex);
        chapterPos = found >= 0 ? found : Math.max(0, Math.min(requestedPos, Math.max(0, chapters.size() - 1)));
        int restorePage = state.getInt("restorePage", -1);
        if (restorePage >= 0) {
            forcedRestorePage = restorePage;
            if (selectedChapterIndex < 0f && chapterPos >= 0 && chapterPos < chapters.size()) selectedChapterIndex = chapters.get(chapterPos).index;
            forcedRestoreChapterIndex = selectedChapterIndex;
            resetPageOnNextChapter = false;
        }
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        restoreArguments();
        restoreSavedInstanceState(savedInstanceState);
        closingReader = false;
        readerExitCacheCleared = false;
        if (manga == null) manga = new MangaPost("", "Manga", "", "", "", "", "");
        if (chapters == null) chapters = new ArrayList<>();
        api = MangaSourceFactory.createFor(manga, requireContext());
        View root = inflater.inflate(R.layout.fragment_manga_reader_v2, container, false);
        readerRecyclerView = root.findViewById(R.id.readerRecyclerView);
        progress = root.findViewById(R.id.progressBar);
        pageIndicator = root.findViewById(R.id.readerPageIndicator);
        readerMangaTitle = root.findViewById(R.id.readerMangaTitle);
        readerChapterTitle = root.findViewById(R.id.readerChapterTitle);
        readerTopBar = root.findViewById(R.id.readerTopBar);
        readerFloatingControls = root.findViewById(R.id.readerFloatingControls);

        startReaderSession(root);
        setupReaderList();
        setupReaderHeader();
        setupReaderControls(root);
        root.post(() -> {
            setReaderControlsVisible(false);
            enableFullScreenMode();
        });
        load();
        return root;
    }

    @Override public void onResume() {
        super.onResume();
        startReaderSession(getView());
        enableFullScreenMode();
        setReaderControlsVisible(false);
        readerHandler.postDelayed(this::enableFullScreenMode, 250);
        if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).setAppBottomNavigationVisible(false);
    }

    private void setupReaderList() {
        int readerExtraLayoutSpace = Math.max(dp(720), requireContext().getResources().getDisplayMetrics().heightPixels);
        readerLayoutManager = new MangaWebtoonLayoutManager(requireContext(), readerExtraLayoutSpace);
        if (readerRecyclerView instanceof MangaWebtoonRecyclerView) {
            ((MangaWebtoonRecyclerView) readerRecyclerView).setZoomEnabled(MangaSettingsManager.isReaderPinchZoomEnabled(requireContext()));
            ((MangaWebtoonRecyclerView) readerRecyclerView).setDoubleTapZoomEnabled(MangaSettingsManager.isReaderDoubleTapZoomEnabled(requireContext()));
        }
        pageAdapter = new MangaReaderPageAdapter(manga == null ? null : manga.getSourceId());
        pageAdapter.setPhotoViewAllowed(false);
        readerRecyclerView.setLayoutManager(readerLayoutManager);
        readerRecyclerView.setAdapter(pageAdapter);
        readerRecyclerView.setHasFixedSize(false);
        readerRecyclerView.setItemViewCacheSize(8);
        readerRecyclerView.setDrawingCacheEnabled(false);
        readerRecyclerView.setItemAnimator(null);
        readerRecyclerView.setNestedScrollingEnabled(false);
        readerLayoutManager.setInitialPrefetchItemCount(0);
        readerRecyclerView.getRecycledViewPool().setMaxRecycledViews(MangaReaderPageAdapter.TYPE_PAGE, 10);
        readerRecyclerView.getRecycledViewPool().setMaxRecycledViews(MangaReaderPageAdapter.TYPE_HEADER, 4);
        readerRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (closingReader || restoringScroll) return;
                updateReaderProgressFromScroll(dy);
            }

            @Override public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (!closingReader && !restoringScroll && newState == RecyclerView.SCROLL_STATE_IDLE) {
                    int page = currentPage();
                    updatePageLabelIfChanged(page);
                    saveProgressIfPageChanged(page);
                    lastScrollProgressAdapterPosition = RecyclerView.NO_POSITION;
                    if (pageAdapter != null) pageAdapter.preloadAround(requireContext(), currentAdapterPositionForPreload());
                    checkNextChapterPreload(page);
                }
            }
        });
        if (readerRecyclerView instanceof MangaWebtoonRecyclerView) {
            ((MangaWebtoonRecyclerView) readerRecyclerView).setSingleTapListener(this::handleReaderSingleTap);
        } else {
            readerRecyclerView.setOnClickListener(v -> { });
        }
    }

    private void handleReaderSingleTap(float x, float y, int width, int height) {
        if (width <= 0) return;
        if (x >= width * 0.65f) setReaderControlsVisible(!controlsVisible);
    }

    private void setupReaderControls(View root) {
        ImageButton back = root.findViewById(R.id.readerBackButton);
        ImageButton prev = root.findViewById(R.id.readerPrevButton);
        ImageButton refresh = root.findViewById(R.id.readerRefreshButton);
        ImageButton list = root.findViewById(R.id.readerChapterListButton);
        ImageButton next = root.findViewById(R.id.readerNextButton);
        back.setOnClickListener(v -> safeBack());
        prev.setOnClickListener(v -> move(1));
        refresh.setOnClickListener(v -> refreshCurrentChapter());
        list.setOnClickListener(v -> showChapterBottomSheet());
        next.setOnClickListener(v -> move(-1));
    }

    private void setupReaderHeader() {
        if (manga != null && readerMangaTitle != null) readerMangaTitle.setText(manga.title == null ? "Manga" : manga.title);
        if (readerChapterTitle != null) readerChapterTitle.setSelected(true);
        if (chapters != null && !chapters.isEmpty() && readerChapterTitle != null) readerChapterTitle.setText(chapters.get(Math.max(0, Math.min(chapterPos, chapters.size() - 1))).title);
    }

    private int themeColor(Context context, int attr) {
        return com.google.android.material.color.MaterialColors.getColor(context, attr, Color.GRAY);
    }

    private com.google.android.material.card.MaterialCardView newStoreCard(Context context) {
        com.google.android.material.card.MaterialCardView card = new com.google.android.material.card.MaterialCardView(context);
        card.setCardBackgroundColor(themeColor(context, com.google.android.material.R.attr.colorSurfaceContainer));
        card.setRadius(dp(28));
        card.setCardElevation(0f);
        card.setStrokeWidth(0);
        return card;
    }

    private com.google.android.material.materialswitch.MaterialSwitch addReaderSettingRow(Context ctx, LinearLayout container, String title, String description, boolean checked, android.widget.CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(ctx);
        titleView.setText(title);
        titleView.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        titleView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleView.setTextColor(themeColor(ctx, com.google.android.material.R.attr.colorOnSurface));
        TextView descView = new TextView(ctx);
        descView.setText(description);
        descView.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        descView.setTextColor(themeColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant));
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descParams.setMargins(0, dp(5), 0, 0);
        texts.addView(titleView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        texts.addView(descView, descParams);
        com.google.android.material.materialswitch.MaterialSwitch toggle = new com.google.android.material.materialswitch.MaterialSwitch(ctx);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(listener);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textsParams.setMargins(0, 0, dp(12), 0);
        row.addView(texts, textsParams);
        row.addView(toggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, dp(14), 0, 0);
        container.addView(row, rowParams);
        return toggle;
    }

    private void showReaderSettingsSheet() {
        if (!isAdded()) return;
        Context ctx = requireContext();
        BottomSheetDialog settingsSheet = new BottomSheetDialog(ctx);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));
        box.setBackgroundResource(R.drawable.reader_v2_sheet_background);
        com.google.android.material.card.MaterialCardView card = newStoreCard(ctx);
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(18));
        TextView heading = new TextView(ctx);
        heading.setText("Pengaturan Pembaca");
        heading.setTextAppearance(R.style.TextAppearance_Miku_SectionTitle);
        heading.setTextColor(themeColor(ctx, com.google.android.material.R.attr.colorOnSurface));
        content.addView(heading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addReaderSettingRow(ctx, content, "Sembunyikan Teks Pemisah Chapter", "Teks Sebelumnya dan Saat ini disembunyikan.", MangaSettingsManager.isReaderHideBoundaryTextEnabled(ctx), (buttonView, isChecked) -> {
            MangaSettingsManager.setReaderHideBoundaryTextEnabled(ctx, isChecked);
            if (pageAdapter != null) pageAdapter.notifyDataSetChanged();
        });
        addReaderSettingRow(ctx, content, "Sembunyikan Nomor Halaman", "Sembunyikan angka seperti 12 / 19 di bawah layar.", MangaSettingsManager.isReaderHidePageCounterEnabled(ctx), (buttonView, isChecked) -> {
            MangaSettingsManager.setReaderHidePageCounterEnabled(ctx, isChecked);
            updatePageLabel(currentPage());
            if (!isChecked && pageIndicator != null) pageIndicator.setVisibility(View.VISIBLE);
        });
        addReaderSettingRow(ctx, content, "Zoom Cubit", "Cubit dua jari untuk zoom.", MangaSettingsManager.isReaderPinchZoomEnabled(ctx), (buttonView, isChecked) -> {
            MangaSettingsManager.setReaderZoomEnabled(ctx, isChecked);
            applyReaderZoomSettings();
        });
        addReaderSettingRow(ctx, content, "Zoom Ketuk Dua Kali", "Ketuk dua kali untuk zoom.", MangaSettingsManager.isReaderDoubleTapZoomEnabled(ctx), (buttonView, isChecked) -> {
            MangaSettingsManager.setReaderDoubleTapZoomEnabled(ctx, isChecked);
            applyReaderZoomSettings();
        });
        card.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        settingsSheet.setContentView(box);
        settingsSheet.setOnShowListener(d -> {
            try {
                View parent = (View) box.getParent();
                if (parent != null) parent.setBackgroundColor(Color.TRANSPARENT);
            } catch (Exception ignored) { }
        });
        settingsSheet.show();
    }

    private void applyReaderZoomSettings() {
        if (!isAdded() || !(readerRecyclerView instanceof MangaWebtoonRecyclerView)) return;
        Context ctx = requireContext();
        ((MangaWebtoonRecyclerView) readerRecyclerView).setZoomEnabled(MangaSettingsManager.isReaderPinchZoomEnabled(ctx));
        ((MangaWebtoonRecyclerView) readerRecyclerView).setDoubleTapZoomEnabled(MangaSettingsManager.isReaderDoubleTapZoomEnabled(ctx));
    }

    private void showChapterBottomSheet() {
        if (!isAdded() || chapters == null || chapters.isEmpty()) return;
        saveProgress();
        Context ctx = requireContext();
        int onSurface = themeColor(ctx, com.google.android.material.R.attr.colorOnSurface);
        int onSurfaceVariant = themeColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant);
        BottomSheetDialog sheet = new BottomSheetDialog(ctx);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(12));
        box.setBackgroundResource(R.drawable.reader_v2_sheet_background);

        com.google.android.material.card.MaterialCardView searchCard = newStoreCard(ctx);
        LinearLayout searchRow = new LinearLayout(ctx);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(dp(6), dp(4), dp(6), dp(4));
        ImageView searchIcon = new ImageView(ctx);
        searchIcon.setImageResource(R.drawable.ic_settings);
        searchIcon.setColorFilter(onSurfaceVariant);
        searchIcon.setPadding(dp(12), dp(12), dp(12), dp(12));
        android.util.TypedValue rippleValue = new android.util.TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, rippleValue, true);
        searchIcon.setBackgroundResource(rippleValue.resourceId);
        searchIcon.setContentDescription("Pengaturan pembaca");
        searchIcon.setOnClickListener(v -> showReaderSettingsSheet());
        android.widget.EditText search = new android.widget.EditText(ctx);
        search.setHint("Search");
        search.setSingleLine(true);
        search.setTextColor(onSurface);
        search.setHintTextColor(onSurfaceVariant);
        search.setTextSize(16f);
        search.setBackgroundColor(Color.TRANSPARENT);
        search.setPadding(dp(8), 0, 0, 0);
        ImageButton sort = new ImageButton(ctx);
        sort.setImageResource(R.drawable.ic_arrow_downward);
        sort.setColorFilter(onSurface);
        sort.setBackgroundColor(Color.TRANSPARENT);
        sort.setPadding(dp(12), dp(12), dp(12), dp(12));
        searchRow.addView(sort, new LinearLayout.LayoutParams(dp(48), dp(48)));
        searchRow.addView(search, new LinearLayout.LayoutParams(0, dp(48), 1f));
        searchRow.addView(searchIcon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        searchCard.addView(searchRow, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        RecyclerView list = new RecyclerView(ctx);
        LinearLayoutManager manager = new LinearLayoutManager(ctx);
        list.setLayoutManager(manager);
        list.setItemViewCacheSize(8);
        list.setHasFixedSize(false);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(8));
        list.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.min(dp(520), Math.max(dp(320), getResources().getDisplayMetrics().heightPixels - dp(280)))));
        ArrayList<ChapterEntry> visibleItems = buildChapterEntries("", chapterListDescending);
        ChapterSheetAdapter adapter = new ChapterSheetAdapter(visibleItems, manga, entry -> {
            if (entry == null || entry.position < 0 || entry.position >= chapters.size()) return;
            saveProgress();
            int oldPos = chapterPos;
            chapterPos = entry.position;
            setReaderControlsVisible(false);
            clearReaderCache(oldPos);
            sheet.dismiss();
            load();
        });
        list.setAdapter(adapter);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.setItems(buildChapterEntries(s == null ? "" : s.toString(), chapterListDescending));
            }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        sort.setRotation(chapterListDescending ? 0f : 180f);
        sort.setOnClickListener(v -> {
            chapterListDescending = !chapterListDescending;
            sort.setRotation(chapterListDescending ? 0f : 180f);
            adapter.setItems(buildChapterEntries(search.getText() == null ? "" : search.getText().toString(), chapterListDescending));
        });
        LinearLayout.LayoutParams searchCardParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchCardParams.setMargins(0, 0, 0, dp(4));
        box.addView(searchCard, searchCardParams);
        box.addView(list);
        sheet.setContentView(box);
        chapterSheet = sheet;
        sheet.setOnShowListener(d -> {
            try {
                View parent = (View) box.getParent();
                if (parent != null) parent.setBackgroundColor(Color.TRANSPARENT);
            } catch (Exception ignored) { }
            list.post(() -> scrollChapterListToCurrent(manager, adapter));
        });
        sheet.setOnDismissListener(d -> { if (chapterSheet == sheet) chapterSheet = null; });
        sheet.show();
    }

    private ArrayList<ChapterEntry> buildChapterEntries(String query, boolean descending) {
        ArrayList<ChapterEntry> result = new ArrayList<>();
        if (chapters == null) return result;
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < chapters.size(); i++) {
            MangaChapter chapter = chapters.get(i);
            String title = chapter == null || chapter.title == null ? "Chapter" : chapter.title;
            if (q.isEmpty() || title.toLowerCase(java.util.Locale.ROOT).contains(q)) result.add(new ChapterEntry(chapter, i));
        }
        if (!descending) Collections.reverse(result);
        return result;
    }

    private void scrollChapterListToCurrent(LinearLayoutManager manager, ChapterSheetAdapter adapter) {
        if (manager == null || adapter == null) return;
        int target = adapter.findPosition(chapterPos);
        if (target >= 0) manager.scrollToPositionWithOffset(target, dp(80));
    }

    private void scrollByReader(int delta) {
        if (readerRecyclerView == null) return;
        readerRecyclerView.smoothScrollBy(0, delta);
    }

    private void refreshCurrentChapter() {
        if (!isAdded() || manga == null) return;
        MangaSourceFactory.invalidateSourceCaches(manga.getSourceId());
        chapters = normalizeChapters(chapters);
        if (chapters == null || chapters.isEmpty()) { load(); return; }
        MangaReaderPageAdapter.PageInfo info = currentPageInfo();
        int restorePage;
        if (info != null && info.chapterPosition >= 0 && info.chapterPosition < chapters.size()) {
            applyReadingPageInfo(info, false);
            restorePage = Math.max(0, info.pageIndex);
        } else restorePage = currentPage();
        chapterPos = Math.max(0, Math.min(chapterPos, chapters.size() - 1));
        MangaChapter chapter = chapters.get(chapterPos);
        forcedRestorePage = restorePage;
        forcedRestoreChapterIndex = chapter.index;
        resetPageOnNextChapter = false;
        saveProgress(restorePage);
        load();
    }

    private void load() {
        if (!isAdded() || manga == null) return;
        chapters = normalizeChapters(chapters);
        if (chapters == null || chapters.isEmpty()) { reloadChaptersThenMove(0); return; }
        final int requestVersion = ++loadVersion;
        chapterPos = Math.max(0, Math.min(chapterPos, chapters.size() - 1));
        MangaChapter ch = chapters.get(chapterPos);
        final int forcedPage = forcedRestorePage >= 0 && Math.abs(forcedRestoreChapterIndex - ch.index) < 0.001f ? forcedRestorePage : -1;
        forcedRestorePage = -1;
        forcedRestoreChapterIndex = -1f;
        clearNextChapterSnackbar();
        pageAdapter.submit(new ArrayList<>());
        readerRecyclerView.stopScroll();
        readerRecyclerView.scrollToPosition(0);
        ReaderToast.show(requireContext(), "Kamu membaca: " + ch.title);
        progress.setVisibility(View.VISIBLE);
        totalPages = 0;
        loadedChapterPos = -1;
        loadedChapterIndex = -1f;
        lastSavedPage = -1;
        lastDisplayedPage = -1;
        inlineLoadingChapterPositions.clear();
        inlinePreloadedChapterPositions.clear();
        inlineAppendingChapterPositions.clear();
        inlinePreparedChapterPages.clear();
        inlineAppendWhenReadyChapterPositions.clear();
        lastReaderWindowChapterPos = -1;
        lastReadingToastChapterPos = chapterPos;
        updatePageLabel(0);
        boolean showControlsOnLoad = !keepReaderControlsHiddenOnNextLoad;
        keepReaderControlsHiddenOnNextLoad = false;
        setReaderControlsVisible(showControlsOnLoad);
        updateChapterLabels(ch.title);
        MangaRepository.INSTANCE.pagesFuture(manga.getSourceId(), readerSourceSlug(ch), ch.index).whenComplete((pages, error) -> {
            if (error == null) {
                if (closingReader || !isAdded() || requestVersion != loadVersion) return;
                progress.setVisibility(View.GONE);
                totalPages = pages == null ? 0 : pages.size();
                loadedChapterPos = chapterPos;
                loadedChapterIndex = ch.index;
                if (totalPages == 0) {
                    pageAdapter.showMessage("Gambar chapter tidak ditemukan. Coba refresh.");
                    return;
                }
                pageAdapter.submitChapter(pages, chapterPos, ch.index, safeChapterTitle(ch));
                rememberReaderChapterPages(chapterPos, pages);
                pruneOldReaderImageCache(chapterPos);
                trimReaderChapterWindow(chapterPos);
                int saved = forcedPage >= 0 ? Math.max(0, Math.min(forcedPage, Math.max(0, totalPages - 1))) : (resetPageOnNextChapter ? 0 : savedPageForChapter(ch, Math.max(0, totalPages - 1)));
                try { pageAdapter.preloadAround(requireContext(), adapterPositionForPage(saved)); } catch (Exception preloadException) { }
                resetPageOnNextChapter = false;
                final MangaPost historyManga = manga;
                final int historyPage = saved;
                final int historyTotal = Math.max(totalPages, 1);
                final Context historyAppContext = requireContext().getApplicationContext();
                MangaCoroutines.io(() -> MangaHistoryManager.saveReaderProgress(historyAppContext, historyManga, ch, historyPage, historyTotal));
                restoreSavedPageWhenReady(requestVersion, saved);

            } else {
                String msg = error.getMessage() == null ? "" : error.getMessage();
                if (closingReader || !isAdded() || requestVersion != loadVersion) return;
                progress.setVisibility(View.GONE);
                showReaderMessage("Halaman gagal dimuat: " + msg);

            }
        });
    }

    private int savedPageForChapter(MangaChapter chapter, int maxPage) {
        if (chapter == null || !isAdded()) return 0;
        int saved = MangaHistoryManager.getPage(requireContext(), manga, chapter.index);
        if (manga != null && Math.abs(manga.historyChapterIndex - chapter.index) < 0.001f && manga.historyPage > saved) saved = manga.historyPage;
        return Math.max(0, Math.min(saved, Math.max(0, maxPage)));
    }

    private void showReaderMessage(String message) {
        if (pageIndicator == null) return;
        String text = message == null || message.trim().isEmpty() ? "Gagal memuat halaman. Coba lagi." : message.trim();
        pageIndicator.setText(text);
        pageIndicator.setVisibility(View.VISIBLE);
        pageIndicator.setAlpha(0f);
        pageIndicator.animate().alpha(1f).setDuration(160).start();
        setReaderControlsVisible(true);
    }

    private void updateChapterLabels(String chapterTitle) {
        if (readerChapterTitle != null) {
            readerChapterTitle.setText(chapterTitle == null ? "Chapter" : chapterTitle);
            readerChapterTitle.setSelected(true);
        }
        View root = getView();
        if (root != null) {
            TextView chapterText = root.findViewById(R.id.nav_header_chapter_title);
            if (chapterText != null) chapterText.setText(chapterTitle);
        }
    }

    private void startReaderSession(@Nullable View root) {
        applyReaderKeepAlive(root);
        if (readerSessionActive) return;
        readerSessionActive = true;
        try { MangaCacheController.beginReaderSession(); } catch (Exception ignored) { }
    }

    private void stopReaderSession() {
        clearReaderKeepAlive();
        if (!readerSessionActive) return;
        readerSessionActive = false;
        try { MangaCacheController.endReaderSession(); } catch (Exception ignored) { }
    }

    private void applyReaderKeepAlive(@Nullable View root) {
        try { if (root != null) root.setKeepScreenOn(true); } catch (Exception ignored) { }
        try {
            if (getActivity() != null) getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Exception ignored) { }
    }

    private void clearReaderKeepAlive() {
        try {
            View root = getView();
            if (root != null) root.setKeepScreenOn(false);
        } catch (Exception ignored) { }
        try {
            if (getActivity() != null) getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Exception ignored) { }
    }

    private void clearReaderExitImageCache() {
        if (readerExitCacheCleared) return;
        readerExitCacheCleared = true;
        try { if (pageAdapter != null) pageAdapter.clearImages(readerRecyclerView); } catch (Exception ignored) { }
        try { if (readerRecyclerView != null) readerRecyclerView.getRecycledViewPool().clear(); } catch (Exception ignored) { }
        try { MangaImageLoader.cancelPreloads(); } catch (Exception ignored) { }
        try { Context context = getContext(); if (context != null) MangaCacheController.clearReaderImageCache(context); } catch (Exception ignored) { }
    }

    private void safeBack() {
        try {
            saveProgress(true);
            clearReaderExitImageCache();
            prepareFastExit();
            showSystemBars();
            FragmentManager fm = getParentFragmentManager();
            if (fm.getBackStackEntryCount() > 0) fm.popBackStack(); else requireActivity().finish();
        } catch (Exception ignored) { }
    }

    private void prepareFastExit() {
        closingReader = true;
        loadVersion++;
        try { readerHandler.removeCallbacksAndMessages(null); } catch (Exception ignored) { }
        try { if (readerRecyclerView != null) readerRecyclerView.stopScroll(); } catch (Exception ignored) { }
        try { if (readerRecyclerView != null) readerRecyclerView.clearOnScrollListeners(); } catch (Exception ignored) { }
        try { if (readerRecyclerView != null) readerRecyclerView.setOnClickListener(null); } catch (Exception ignored) { }
        try { if (readerRecyclerView instanceof MangaWebtoonRecyclerView) ((MangaWebtoonRecyclerView) readerRecyclerView).setSingleTapListener(null); } catch (Exception ignored) { }
        try { if (pageAdapter != null) pageAdapter.release(readerRecyclerView); } catch (Exception ignored) { }
        try { MangaImageLoader.cancelPreloads(); } catch (Exception ignored) { }
        clearReaderKeepAlive();
        clearNextChapterSnackbarOnly();
    }

    @Override public void onDestroyView() {
        showSystemBars();
        prepareFastExit();
        clearReaderExitImageCache();
        stopReaderSession();
        try { if (readerRecyclerView != null) readerRecyclerView.swapAdapter(null, false); } catch (Exception ignored) { }
        try { if (readerRecyclerView != null) readerRecyclerView.setLayoutManager(null); } catch (Exception ignored) { }
        clearNextChapterSnackbar();
        try { imageCachePruner.clear(); } catch (Exception ignored) { }
        if (chapterSheet != null) chapterSheet.dismiss();
        chapterSheet = null;
        readerRecyclerView = null;
        readerLayoutManager = null;
        pageAdapter = null;
        progress = null;
        pageIndicator = null;
        readerMangaTitle = null;
        readerChapterTitle = null;
        readerTopBar = null;
        readerFloatingControls = null;
        super.onDestroyView();
    }

    @Override public void onDestroy() {
        stopReaderSession();
        super.onDestroy();
    }

    @Override public void onPause() { super.onPause(); if (!closingReader) saveProgress(true); }

    @Override public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        try { if (!closingReader) saveProgress(true); } catch (Exception ignored) { }
        if (manga != null) outState.putSerializable("manga", manga);
        int stateChapterPos = chapterPos;
        float stateChapterIndex = -1f;
        int statePage = 0;
        MangaReaderPageAdapter.PageInfo info = currentPageInfo();
        if (info != null && info.chapterPosition >= 0) {
            stateChapterPos = info.chapterPosition;
            stateChapterIndex = info.chapterIndex;
            statePage = Math.max(0, info.pageIndex);
        } else {
            statePage = Math.max(0, currentPage());
            if (chapters != null && stateChapterPos >= 0 && stateChapterPos < chapters.size()) stateChapterIndex = chapters.get(stateChapterPos).index;
        }
        outState.putInt("chapterPos", stateChapterPos);
        outState.putFloat("selectedChapterIndex", stateChapterIndex);
        outState.putInt("restorePage", statePage);
    }

    private void setReaderControlsVisible(boolean visible) {
        if (controlsVisible == visible) return;
        controlsVisible = visible;
        float topTarget = visible ? 0f : -dp(96);
        float bottomTarget = visible ? 0f : dp(96);
        if (readerTopBar != null) {
            readerTopBar.animate().cancel();
            readerTopBar.animate().translationY(topTarget).alpha(visible ? 1f : 0f).setDuration(160).start();
        }
        if (readerFloatingControls != null) {
            readerFloatingControls.animate().cancel();
            readerFloatingControls.animate().translationY(bottomTarget).alpha(visible ? 1f : 0f).setDuration(160).start();
        }
        enableFullScreenMode();
    }

    private void enableFullScreenMode() {
        if (getActivity() == null || getActivity().getWindow() == null) return;
        try {
            Window window = getActivity().getWindow();
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                window.setAttributes(lp);
            }
        } catch (Exception ignored) { }
    }

    private void showSystemBars() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).restoreSystemBars();
            return;
        }
        if (getActivity() == null || getActivity().getWindow() == null) return;
        try {
            Window window = getActivity().getWindow();
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                int surface = com.google.android.material.color.MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorSurface);
                window.setStatusBarColor(surface);
                window.setNavigationBarColor(surface);
            }
        } catch (Exception ignored) { }
    }

    private void updateReaderProgressFromScroll(int dy) {
        if (Math.abs(dy) > dp(8) && controlsVisible) setReaderControlsVisible(false);
        long now = SystemClock.uptimeMillis();
        if (now - lastScrollProgressUpdateAt < 90L) return;
        lastScrollProgressUpdateAt = now;
        int adapterPosition = currentAdapterPositionForPreload();
        if (adapterPosition != lastScrollProgressAdapterPosition) {
            lastScrollProgressAdapterPosition = adapterPosition;
            MangaReaderPageAdapter.PageInfo info = pageAdapter == null ? null : pageAdapter.getPageInfoAround(adapterPosition);
            int page;
            if (info != null) {
                applyReadingPageInfo(info, false);
                page = Math.max(0, Math.min(info.pageIndex, Math.max(0, info.totalPages - 1)));
            } else page = currentPage();
            updatePageLabelIfChanged(page);
            if (pageAdapter != null) pageAdapter.preloadAround(requireContext(), adapterPosition);
            checkNextChapterPreload(page);
        }
    }

    private void scheduleProgressUpdate() {
        if (pendingProgressUpdate != null) readerHandler.removeCallbacks(pendingProgressUpdate);
        pendingProgressUpdate = () -> {
            if (!isAdded() || closingReader || restoringScroll) return;
            int page = currentPage();
            updatePageLabelIfChanged(page);
            saveProgressIfPageChanged(page);
            if (readerRecyclerView != null && readerRecyclerView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
                readerHandler.postDelayed(() -> {
                    if (!isAdded() || getView() == null || closingReader || restoringScroll || readerRecyclerView == null) return;
                    if (readerRecyclerView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) return;
                    int idlePage = currentPage();
                    if (pageAdapter != null) pageAdapter.preloadAround(requireContext(), currentAdapterPositionForPreload());
                    checkNextChapterPreload(idlePage);
                }, 180);
                return;
            }
            if (pageAdapter != null) pageAdapter.preloadAround(requireContext(), currentAdapterPositionForPreload());
            checkNextChapterPreload(page);
        };
        readerHandler.postDelayed(pendingProgressUpdate, 140);
    }

    private int currentPage() {
        MangaReaderPageAdapter.PageInfo info = currentPageInfo();
        if (info != null) {
            applyReadingPageInfo(info, true);
            return Math.max(0, Math.min(info.pageIndex, Math.max(0, info.totalPages - 1)));
        }
        if (readerRecyclerView == null || readerLayoutManager == null) return 0;
        int fallback = centeredAdapterPosition();
        if (fallback == RecyclerView.NO_POSITION) fallback = 0;
        return Math.max(0, Math.min(fallback, Math.max(0, totalPages - 1)));
    }

    private void scrollToPage(int page) {
        if (readerRecyclerView == null) return;
        int target = Math.max(0, Math.min(page, Math.max(0, totalPages - 1)));
        int adapterTarget = pageAdapter == null ? -1 : pageAdapter.findAdapterPosition(chapterPos, target);
        if (adapterTarget < 0) adapterTarget = target;
        if (readerLayoutManager != null) readerLayoutManager.scrollToPositionWithOffset(adapterTarget, dp(12));
        else readerRecyclerView.scrollToPosition(adapterTarget);
    }

    private void restoreSavedPageWhenReady(final int requestVersion, final int savedPage) {
        if (readerRecyclerView == null) return;
        pendingRestorePage = Math.max(0, savedPage);
        pendingRestoreChapterPos = chapterPos;
        pendingRestoreVersion = requestVersion;
        restoringScroll = true;
        runRestoreStep(requestVersion, pendingRestorePage);
        readerRecyclerView.post(() -> runRestoreStep(requestVersion, pendingRestorePage));
        readerHandler.postDelayed(() -> runRestoreStep(requestVersion, pendingRestorePage), 90);
        readerHandler.postDelayed(() -> runRestoreStep(requestVersion, pendingRestorePage), 260);
        readerHandler.postDelayed(() -> runRestoreStep(requestVersion, pendingRestorePage), 650);
        readerHandler.postDelayed(() -> runRestoreStep(requestVersion, pendingRestorePage), 1100);
        readerHandler.postDelayed(() -> finishRestoreSavedPage(requestVersion), 1500);
    }

    private boolean canRestoreSavedPage(int requestVersion) {
        return isAdded() && requestVersion == loadVersion && requestVersion == pendingRestoreVersion && pendingRestoreChapterPos == chapterPos && getView() != null && readerRecyclerView != null && pageAdapter != null && pageIndicator != null;
    }

    private void runRestoreStep(int requestVersion, int page) {
        if (!canRestoreSavedPage(requestVersion)) return;
        int safePage = Math.max(0, Math.min(page, Math.max(0, totalPages - 1)));
        scrollToPage(safePage);
        updatePageLabel(safePage);
        if (!isPageCounterHidden()) pageIndicator.setVisibility(View.VISIBLE);
        try { pageAdapter.preloadAround(requireContext(), adapterPositionForPage(safePage)); } catch (Exception ignored) { }
    }

    private void finishRestoreSavedPage(int requestVersion) {
        if (!canRestoreSavedPage(requestVersion)) return;
        int restoredPage = pendingRestorePage;
        runRestoreStep(requestVersion, restoredPage);
        lastSavedPage = Math.max(0, restoredPage);
        restoringScroll = false;
        pendingRestorePage = -1;
        pendingRestoreChapterPos = -1;
        pendingRestoreVersion = -1;
        checkNextChapterPreload(lastSavedPage);
        readerHandler.postDelayed(() -> {
            if (isAdded() && getView() != null) checkNextChapterPreload(currentPage());
        }, 250);
        readerHandler.postDelayed(() -> {
            if (isAdded() && getView() != null) checkNextChapterPreload(currentPage());
        }, 900);
    }

    private boolean isPageCounterHidden() {
        Context c = getContext();
        return c != null && MangaSettingsManager.isReaderHidePageCounterEnabled(c);
    }

    private void updatePageLabel(int page) {
        if (pageIndicator == null) return;
        if (isPageCounterHidden()) {
            pageIndicator.setVisibility(View.GONE);
            return;
        }
        pageIndicator.setText(totalPages <= 0 ? "0 / 0" : ((Math.min(page, totalPages - 1) + 1) + " / " + totalPages));
    }

    private void updatePageLabelIfChanged(int page) {
        boolean hidden = isPageCounterHidden();
        boolean visible = pageIndicator != null && pageIndicator.getVisibility() == View.VISIBLE;
        if (page == lastDisplayedPage && pageIndicator != null && visible != hidden) return;
        lastDisplayedPage = page;
        updatePageLabel(page);
        if (pageIndicator != null && !hidden) pageIndicator.setVisibility(View.VISIBLE);
    }

    private MangaReaderPageAdapter.PageInfo currentPageInfo() {
        if (readerRecyclerView == null || readerLayoutManager == null || pageAdapter == null) return null;
        int position = endVisibleAdapterPosition();
        if (position == RecyclerView.NO_POSITION) position = centeredAdapterPosition();
        if (position == RecyclerView.NO_POSITION) position = readerLayoutManager.findFirstVisibleItemPosition();
        if (position == RecyclerView.NO_POSITION) return null;
        return pageAdapter.getPageInfoAround(position);
    }

    private int centeredAdapterPosition() {
        if (readerRecyclerView == null || readerLayoutManager == null) return RecyclerView.NO_POSITION;
        int childCount = readerRecyclerView.getChildCount();
        if (childCount <= 0) return readerLayoutManager.findFirstVisibleItemPosition();
        int viewportTop = readerRecyclerView.getPaddingTop();
        int viewportBottom = readerRecyclerView.getHeight() - readerRecyclerView.getPaddingBottom();
        int anchor = viewportTop + dp(72);
        int firstVisible = RecyclerView.NO_POSITION;
        int bestPage = RecyclerView.NO_POSITION;
        int bestVisible = -1;
        for (int i = 0; i < childCount; i++) {
            View child = readerRecyclerView.getChildAt(i);
            int pos = readerRecyclerView.getChildAdapterPosition(child);
            if (pos == RecyclerView.NO_POSITION) continue;
            int visibleTop = Math.max(child.getTop(), viewportTop);
            int visibleBottom = Math.min(child.getBottom(), viewportBottom);
            int visible = visibleBottom - visibleTop;
            if (visible <= 0) continue;
            if (firstVisible == RecyclerView.NO_POSITION) firstVisible = pos;
            if (child.getTop() <= anchor && child.getBottom() > anchor) return pos;
            if (visible > bestVisible) {
                bestVisible = visible;
                bestPage = pos;
            }
        }
        if (bestPage != RecyclerView.NO_POSITION) return bestPage;
        if (firstVisible != RecyclerView.NO_POSITION) return firstVisible;
        return readerLayoutManager.findFirstVisibleItemPosition();
    }

    private int endVisibleAdapterPosition() {
        if (readerLayoutManager instanceof MangaWebtoonLayoutManager) return ((MangaWebtoonLayoutManager) readerLayoutManager).findLastEndVisibleItemPosition();
        return RecyclerView.NO_POSITION;
    }

    private int currentAdapterPositionForPreload() {
        int position = endVisibleAdapterPosition();
        if (position != RecyclerView.NO_POSITION) return position;
        position = centeredAdapterPosition();
        if (position != RecyclerView.NO_POSITION) return position;
        if (readerLayoutManager == null) return 0;
        int first = readerLayoutManager.findFirstVisibleItemPosition();
        return first == RecyclerView.NO_POSITION ? 0 : Math.max(0, first);
    }

    private int adapterPositionForPage(int page) {
        int safePage = Math.max(0, page);
        int target = pageAdapter == null ? -1 : pageAdapter.findAdapterPosition(chapterPos, safePage);
        if (target >= 0) return target;
        int current = currentAdapterPositionForPreload();
        return current == RecyclerView.NO_POSITION ? safePage : current;
    }

    private void applyReadingPageInfo(MangaReaderPageAdapter.PageInfo info, boolean showToast) {
        if (info == null || chapters == null || chapters.isEmpty()) return;
        if (info.chapterPosition < 0 || info.chapterPosition >= chapters.size()) return;
        int previousChapterPos = chapterPos;
        int previousTotalPages = totalPages;
        if (previousChapterPos >= 0 && previousChapterPos < chapters.size() && previousChapterPos != info.chapterPosition) {
            MangaChapter previousChapter = chapters.get(previousChapterPos);
            if (previousChapter != null && info.chapterIndex > previousChapter.index + 0.001f) markChapterCompleted(previousChapter, previousTotalPages);
        }
        boolean changed = chapterPos != info.chapterPosition || totalPages != info.totalPages;
        chapterPos = info.chapterPosition;
        totalPages = Math.max(info.totalPages, 0);
        loadedChapterPos = info.chapterPosition;
        loadedChapterIndex = info.chapterIndex;
        if (changed) {
            lastSavedPage = -1;
            clearNextChapterSnackbar();
            updateChapterLabels(info.chapterTitle);
        }
        scheduleReaderWindowMaintenance(info.chapterPosition);
        boolean inlineChapterPreload = MangaSettingsManager.isReaderInlineChapterPreloadEnabled(requireContext());
        if (showToast && !inlineChapterPreload && lastReadingToastChapterPos != info.chapterPosition) {
            ReaderToast.show(requireContext(), "Kamu membaca: " + info.chapterTitle);
            lastReadingToastChapterPos = info.chapterPosition;
        }
        if (inlineChapterPreload) lastReadingToastChapterPos = info.chapterPosition;
    }

    private void markChapterCompleted(MangaChapter chapter, int chapterTotalPages) {
        if (!isAdded() || manga == null || chapter == null || chapterTotalPages <= 0) return;
        MangaPost currentManga = manga;
        Context appContext = requireContext().getApplicationContext();
        int total = Math.max(1, chapterTotalPages);
        long readAt = System.currentTimeMillis();
        MangaCoroutines.io(() -> MangaHistoryManager.saveReaderProgress(appContext, currentManga, chapter, total - 1, total, readAt));
    }

    private void checkNextChapterPreload(int page) {
        if (!isAdded() || restoringScroll || totalPages <= 0 || manga == null || chapters == null || chapters.isEmpty()) return;
        if (getView() == null) return;
        MangaReaderPageAdapter.PageInfo info = currentPageInfo();
        if (info != null) {
            applyReadingPageInfo(info, true);
            page = info.pageIndex;
            if (MangaSettingsManager.isReaderInlineChapterPreloadEnabled(requireContext())) {
                checkInlineChapterPreload(info);
                return;
            }
        }
        if (pageAdapter == null || pageAdapter.getPageCount() != totalPages) return;
        boolean autoEnabled = MangaSettingsManager.isReaderNextChapterAutoEnabled(requireContext());
        boolean manualEnabled = MangaSettingsManager.isReaderNextChapterManualEnabled(requireContext());
        if (!autoEnabled && !manualEnabled) return;
        if (chapterPos <= 0 || chapterPos >= chapters.size()) return;
        MangaChapter current = chapters.get(chapterPos);
        if (loadedChapterPos != chapterPos || current == null || Math.abs(loadedChapterIndex - current.index) >= 0.001f) return;
        int threshold = Math.max(1, MangaSettingsManager.getReaderNextChapterThreshold(requireContext()));
        int triggerPage = totalPages <= threshold ? Math.max(0, totalPages - 1) : Math.max(0, totalPages - threshold);
        int visiblePage = preloadTriggerPage(page);
        if (visiblePage < triggerPage) return;
        if (nextChapterSnackbarShown && nextChapterSnackbarChapterPos == chapterPos && nextChapterToast != null) return;
        MangaChapter next = chapters.get(chapterPos - 1);
        String title = next.title == null || next.title.trim().isEmpty() ? "Chapter berikutnya" : next.title.trim();
        nextChapterSnackbarShown = true;
        nextChapterSnackbarChapterPos = chapterPos;
        if (autoEnabled) showAutoNextChapterSnackbar(title);
        else showManualNextChapterSnackbar(title);
    }

    private void checkInlineChapterPreload(MangaReaderPageAdapter.PageInfo info) {
        if (info == null || pageAdapter == null || chapters == null || chapters.isEmpty()) return;
        int threshold = Math.max(1, MangaSettingsManager.getReaderNextChapterThreshold(requireContext()));
        int warmDistance = Math.max(18, threshold + 16);
        int minVisible = visiblePageBoundary(info.chapterPosition, false, info.pageIndex);
        int maxVisible = visiblePageBoundary(info.chapterPosition, true, info.pageIndex);
        int triggerPage = info.totalPages <= threshold ? Math.max(0, info.totalPages - 1) : Math.max(0, info.totalPages - threshold);
        int warmTriggerPage = Math.max(0, info.totalPages - warmDistance);
        int previousWarmPage = Math.max(0, warmDistance - 1);
        int previousTriggerPage = Math.max(0, threshold - 1);
        int nextPos = info.chapterPosition - 1;
        if (maxVisible >= warmTriggerPage && nextPos >= 0) prepareInlineChapter(nextPos, false, true);
        if (maxVisible >= triggerPage) {
            if (nextPos >= 0) requestInlineChapter(nextPos, false);
            else appendNoNextChapterHeader(info);
        }
        int previousPos = info.chapterPosition + 1;
        if (minVisible <= previousWarmPage) prepareInlineChapter(previousPos, true, true);
        if (minVisible <= previousTriggerPage) requestInlineChapter(previousPos, true);
    }

    private void appendNoNextChapterHeader(MangaReaderPageAdapter.PageInfo info) {
        if (info == null || pageAdapter == null) return;
        if (readerRecyclerView != null && readerRecyclerView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            readerHandler.postDelayed(() -> appendNoNextChapterHeader(info), 180);
            return;
        }
        pageAdapter.appendNoNextChapterHeader(info.chapterPosition, info.chapterIndex, info.chapterTitle);
    }

    private int visiblePageBoundary(int targetChapterPos, boolean max, int fallback) {
        int result = fallback;
        if (readerLayoutManager == null || pageAdapter == null) return result;
        int first = readerLayoutManager.findFirstVisibleItemPosition();
        int last = readerLayoutManager.findLastVisibleItemPosition();
        int start = Math.min(first == RecyclerView.NO_POSITION ? last : first, last == RecyclerView.NO_POSITION ? first : last);
        int end = Math.max(first == RecyclerView.NO_POSITION ? last : first, last == RecyclerView.NO_POSITION ? first : last);
        if (start == RecyclerView.NO_POSITION || end == RecyclerView.NO_POSITION) return result;
        for (int i = start; i <= end; i++) {
            MangaReaderPageAdapter.PageInfo item = pageAdapter.getPageInfoAround(i);
            if (item != null && item.chapterPosition == targetChapterPos) result = max ? Math.max(result, item.pageIndex) : Math.min(result, item.pageIndex);
        }
        return result;
    }

    private void requestInlineChapter(final int targetPos, final boolean prepend) {
        prepareInlineChapter(targetPos, prepend, true);
    }

    private void prepareInlineChapter(final int targetPos, final boolean prepend, final boolean appendWhenReady) {
        if (closingReader || !isAdded() || manga == null || pageAdapter == null || chapters == null) return;
        if (targetPos < 0 || targetPos >= chapters.size()) return;
        if (pageAdapter.hasChapterPosition(targetPos)) return;
        ArrayList<String> prepared = inlinePreparedChapterPages.get(targetPos);
        if (prepared != null && !prepared.isEmpty()) {
            if (appendWhenReady) appendInlineChapterPages(targetPos, prepared, prepend);
            return;
        }
        if (inlineLoadingChapterPositions.contains(targetPos)) {
            if (appendWhenReady) inlineAppendWhenReadyChapterPositions.put(targetPos, prepend);
            return;
        }
        final MangaChapter target = chapters.get(targetPos);
        final int requestVersion = loadVersion;
        inlineLoadingChapterPositions.add(targetPos);
        if (appendWhenReady) inlineAppendWhenReadyChapterPositions.put(targetPos, prepend);
        MangaRepository.INSTANCE.pagesFuture(manga.getSourceId(), readerSourceSlug(target), target.index).whenComplete((pages, error) -> {
            if (error == null) {
                if (closingReader || !isAdded() || requestVersion != loadVersion || pageAdapter == null || targetPos < 0 || targetPos >= chapters.size()) return;
                inlineLoadingChapterPositions.remove(targetPos);
                if (pages == null || pages.isEmpty() || pageAdapter.hasChapterPosition(targetPos)) {
                    inlineAppendWhenReadyChapterPositions.remove(targetPos);
                    return;
                }
                inlinePreparedChapterPages.put(targetPos, pages);
                rememberReaderChapterPages(targetPos, pages);
                postReaderWindowMaintenance(chapterPos);
                while (inlinePreparedChapterPages.size() > 3) {
                    Integer firstKey = inlinePreparedChapterPages.keySet().iterator().next();
                    inlinePreparedChapterPages.remove(firstKey);
                    inlinePreloadedChapterPositions.remove(firstKey);
                    inlineAppendingChapterPositions.remove(firstKey);
                    inlineAppendWhenReadyChapterPositions.remove(firstKey);
                    inlineLoadingChapterPositions.remove(firstKey);
                }
                preloadInlineChapterImages(targetPos, pages, prepend);
                Boolean queuedPrepend = inlineAppendWhenReadyChapterPositions.remove(targetPos);
                if (appendWhenReady || queuedPrepend != null) appendInlineChapterPages(targetPos, pages, queuedPrepend != null ? queuedPrepend : prepend);

            } else {
                String msg = error.getMessage() == null ? "" : error.getMessage();
                inlineLoadingChapterPositions.remove(targetPos);
                inlineAppendWhenReadyChapterPositions.remove(targetPos);

            }
        });
    }

    private void appendInlineChapterPages(final int targetPos, ArrayList<String> pages, final boolean prepend) {
        if (closingReader || !isAdded() || pageAdapter == null || chapters == null) return;
        if (targetPos < 0 || targetPos >= chapters.size()) return;
        if (pages == null || pages.isEmpty() || pageAdapter.hasChapterPosition(targetPos)) return;
        final ArrayList<String> readyPages = new ArrayList<>(pages);
        preloadInlineChapterImages(targetPos, readyPages, prepend);
        if (prepend && readerRecyclerView != null && readerRecyclerView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            readerHandler.postDelayed(() -> appendInlineChapterPages(targetPos, readyPages, true), 120);
            return;
        }
        if (!inlineAppendingChapterPositions.add(targetPos)) return;
        Runnable task = () -> appendInlineChapterPagesNow(targetPos, readyPages, prepend);
        if (readerRecyclerView != null) readerRecyclerView.postOnAnimation(task);
        else readerHandler.post(task);
    }

    private void appendInlineChapterPagesNow(final int targetPos, ArrayList<String> pages, final boolean prepend) {
        try {
            if (closingReader || !isAdded() || pageAdapter == null || chapters == null) return;
            if (targetPos < 0 || targetPos >= chapters.size()) return;
            if (pages == null || pages.isEmpty() || pageAdapter.hasChapterPosition(targetPos)) return;
            MangaChapter target = chapters.get(targetPos);
            String title = safeChapterTitle(target);
            preloadInlineChapterImages(targetPos, pages, prepend);
            if (prepend) {
                int firstPos = RecyclerView.NO_POSITION;
                int firstTop = 0;
                if (readerRecyclerView != null && readerRecyclerView.getChildCount() > 0) {
                    View firstChild = readerRecyclerView.getChildAt(0);
                    firstPos = readerRecyclerView.getChildAdapterPosition(firstChild);
                    firstTop = firstChild.getTop();
                }
                int inserted = pageAdapter.prependChapter(pages, targetPos, target.index, title, "Sebelumnya " + title);
                if (inserted > 0 && firstPos != RecyclerView.NO_POSITION && readerLayoutManager != null) readerLayoutManager.scrollToPositionWithOffset(firstPos + inserted, firstTop);
            } else pageAdapter.appendChapter(pages, targetPos, target.index, title, title);
            postReaderWindowMaintenance(chapterPos);
        } finally {
            inlineAppendingChapterPositions.remove(targetPos);
        }
    }

    private void preloadInlineChapterImages(int targetPos, ArrayList<String> pages, boolean prepend) {
        if (!isAdded() || targetPos < 0 || pages == null || pages.isEmpty()) return;
        if (!inlinePreloadedChapterPositions.add(targetPos)) return;
        int count = Math.min(pages.size(), Math.max(6, MangaSettingsManager.getReaderNextChapterThreshold(requireContext()) + 4));
        int start = prepend ? Math.max(0, pages.size() - count) : 0;
        int end = prepend ? pages.size() - 1 : Math.min(pages.size() - 1, count - 1);
        String sourceId = manga == null ? null : manga.getSourceId();
        Context context = requireContext().getApplicationContext();
        final int startIndex = start;
        final int endIndex = end;
        final ArrayList<String> readyPages = new ArrayList<>(pages);
        MangaCoroutines.io(() -> MangaImageLoader.preloadRange(context, readyPages, sourceId, startIndex, Math.max(1, endIndex - startIndex)));
    }

    private void rememberReaderChapterPages(int targetPos, ArrayList<String> pages) {
        try { imageCachePruner.rememberChapterPages(targetPos, pages); } catch (Exception ignored) { }
    }

    private void pruneOldReaderImageCache(int currentPos) {
        try { if (isAdded()) imageCachePruner.prune(requireContext(), manga == null ? null : manga.getSourceId(), currentPos); } catch (Exception ignored) { }
    }

    private void scheduleReaderWindowMaintenance(final int currentPos) {
        if (currentPos < 0 || currentPos == lastReaderWindowChapterPos) return;
        lastReaderWindowChapterPos = currentPos;
        postReaderWindowMaintenance(currentPos);
    }

    private void postReaderWindowMaintenance(final int currentPos) {
        if (currentPos < 0) return;
        readerHandler.postDelayed(() -> {
            if (!isAdded() || closingReader || currentPos != chapterPos) return;
            if (readerRecyclerView != null && readerRecyclerView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
                readerHandler.postDelayed(() -> postReaderWindowMaintenance(currentPos), 220);
                return;
            }
            pruneOldReaderImageCache(currentPos);
            trimReaderChapterWindow(currentPos);
        }, 320);
    }

    private void trimReaderChapterWindow(int currentPos) {
        if (currentPos < 0) return;
        trimInlinePreparedChapters(currentPos);
        try {
            if (pageAdapter != null) pageAdapter.trimToChapterWindow(currentPos, MangaReaderImageCachePruner.RETAIN_CHAPTER_DISTANCE, readerRecyclerView, readerLayoutManager);
        } catch (Exception ignored) { }
    }

    private void trimInlinePreparedChapters(int currentPos) {
        if (currentPos < 0 || inlinePreparedChapterPages.isEmpty()) return;
        java.util.Iterator<Integer> iterator = inlinePreparedChapterPages.keySet().iterator();
        while (iterator.hasNext()) {
            Integer key = iterator.next();
            if (key == null || Math.abs(key - currentPos) > MangaReaderImageCachePruner.RETAIN_CHAPTER_DISTANCE) {
                iterator.remove();
                if (key != null) {
                    inlinePreloadedChapterPositions.remove(key);
                    inlineAppendWhenReadyChapterPositions.remove(key);
                    inlineLoadingChapterPositions.remove(key);
                }
            }
        }
    }

    private String readerSourceSlug(MangaChapter chapter) {
        String sourceId = manga == null ? "" : manga.getSourceId();
        if (MangaSettingsManager.MANGA_SOURCE_MGKOMIK.equals(sourceId) && chapter != null) {
            if (chapter.chapterId != null && !chapter.chapterId.trim().isEmpty()) return chapter.chapterId;
            if (chapter.slug != null && !chapter.slug.trim().isEmpty()) return chapter.slug;
        }
        if (MangaSettingsManager.MANGA_SOURCE_COSMICSCANS.equals(sourceId) && chapter != null && chapter.slug != null && !chapter.slug.trim().isEmpty()) return chapter.slug;
        if ((MangaSettingsManager.MANGA_SOURCE_NGOMIK.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_KOMIKTAP.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_MANHWAINDO.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_SOULSCANS.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_KUROMANGA.equals(sourceId) || MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK.equals(sourceId)) && chapter != null) {
            if (chapter.chapterId != null && !chapter.chapterId.trim().isEmpty()) return chapter.chapterId;
            if (chapter.slug != null && !chapter.slug.trim().isEmpty()) return chapter.slug;
        }
        return manga == null ? "" : manga.slug;
    }

    private String safeChapterTitle(MangaChapter chapter) {
        if (chapter == null || chapter.title == null || chapter.title.trim().isEmpty()) return "Chapter";
        return chapter.title.trim();
    }

    private int preloadTriggerPage(int page) {
        int result = page;
        if (readerLayoutManager != null) {
            int first = readerLayoutManager.findFirstVisibleItemPosition();
            int last = readerLayoutManager.findLastVisibleItemPosition();
            if (first != RecyclerView.NO_POSITION) result = Math.max(result, first);
            if (last != RecyclerView.NO_POSITION) result = Math.max(result, last);
        }
        return Math.max(0, Math.min(result, Math.max(0, totalPages - 1)));
    }

    private String buildNextChapterMessage(String title) {
        String cleanTitle = title == null ? "" : title.trim();
        if (cleanTitle.isEmpty()) cleanTitle = "Chapter berikutnya";
        return "Selanjutnya " + cleanTitle;
    }

    private void showAutoNextChapterSnackbar(String title) {
        clearNextChapterSnackbarOnly();
        View root = getView();
        if (root == null) return;
        nextChapterToast = ReaderNextChapterToast.showAuto(root, buildNextChapterMessage(title), this::clearNextChapterSnackbar);
        nextChapterAutoRunnable = () -> {
            if (closingReader || !isAdded()) return;
            clearNextChapterSnackbarOnly();
            move(-1);
        };
        readerHandler.postDelayed(nextChapterAutoRunnable, MangaSettingsManager.getReaderNextChapterDurationMillis(requireContext()));
    }

    private void showManualNextChapterSnackbar(String title) {
        clearNextChapterSnackbarOnly();
        View root = getView();
        if (root == null) return;
        nextChapterToast = ReaderNextChapterToast.showManual(root, buildNextChapterMessage(title), this::clearNextChapterSnackbar, this::moveToNextChapterFromSnackbar);
    }

    private void moveToNextChapterFromSnackbar() {
        keepReaderControlsHiddenOnNextLoad = true;
        setReaderControlsVisible(false);
        clearNextChapterSnackbarOnly();
        move(-1);
    }

    private void clearNextChapterSnackbar() {
        nextChapterSnackbarShown = false;
        nextChapterSnackbarChapterPos = -1;
        clearNextChapterSnackbarOnly();
    }

    private void clearNextChapterSnackbarOnly() {
        if (nextChapterAutoRunnable != null) {
            readerHandler.removeCallbacks(nextChapterAutoRunnable);
            nextChapterAutoRunnable = null;
        }
        if (nextChapterToast != null) {
            nextChapterToast.dismiss();
            nextChapterToast = null;
        }
    }

    private void saveProgressIfPageChanged(int page) {
        if (page == lastSavedPage) return;
        lastSavedPage = page;
        scheduleSilentProgressSave(page);
    }

    private void scheduleSilentProgressSave(int page) {
        if (restoringScroll && pendingRestorePage >= 0) return;
        if (!isAdded() || chapters == null || chapters.isEmpty() || manga == null || totalPages <= 0) return;
        int safePage = Math.max(0, Math.min(page, Math.max(0, totalPages - 1)));
        int total = Math.max(totalPages, 1);
        MangaChapter currentChapter = chapters.get(Math.max(0, Math.min(chapterPos, chapters.size() - 1)));
        MangaPost currentManga = manga;
        Context appContext = requireContext().getApplicationContext();
        currentManga.historyChapterIndex = currentChapter.index;
        currentManga.historyPage = safePage;
        currentManga.historyTotalPages = total;
        long readAt = System.currentTimeMillis();
        currentManga.historyLastRead = readAt;
        if (pendingSilentProgressSave != null) readerHandler.removeCallbacks(pendingSilentProgressSave);
        pendingSilentProgressPage = safePage;
        pendingSilentProgressSave = () -> {
            if (closingReader) {
                pendingSilentProgressSave = null;
                pendingSilentProgressPage = -1;
                return;
            }
            if (readerRecyclerView != null && readerRecyclerView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
                readerHandler.postDelayed(pendingSilentProgressSave, 650L);
                return;
            }
            pendingSilentProgressSave = null;
            pendingSilentProgressPage = -1;
            MangaCoroutines.io(() -> MangaHistoryManager.saveReaderProgress(appContext, currentManga, currentChapter, safePage, total, readAt));
        };
        long delay = readerRecyclerView != null && readerRecyclerView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE ? 900L : 650L;
        readerHandler.postDelayed(pendingSilentProgressSave, delay);
    }

    private void cancelPendingSilentProgressSave() {
        if (pendingSilentProgressSave == null) return;
        readerHandler.removeCallbacks(pendingSilentProgressSave);
        pendingSilentProgressSave = null;
        pendingSilentProgressPage = -1;
    }

    private void saveProgress() {
        saveProgress(false);
    }

    private void saveProgress(boolean notifyUi) {
        if (restoringScroll && pendingRestorePage >= 0) return;
        saveProgress(currentPage(), notifyUi);
    }

    private void saveProgress(int page) {
        saveProgress(page, false);
    }

    private void saveProgress(int page, boolean notifyUi) {
        if (restoringScroll && pendingRestorePage >= 0) return;
        cancelPendingSilentProgressSave();
        if (!isAdded() || chapters == null || chapters.isEmpty() || manga == null || totalPages <= 0) return;
        int safePage = Math.max(0, Math.min(page, Math.max(0, totalPages - 1)));
        int total = Math.max(totalPages, 1);
        MangaChapter currentChapter = chapters.get(Math.max(0, Math.min(chapterPos, chapters.size() - 1)));
        MangaPost currentManga = manga;
        Context appContext = requireContext().getApplicationContext();
        currentManga.historyChapterIndex = currentChapter.index;
        currentManga.historyPage = safePage;
        currentManga.historyTotalPages = total;
        long readAt = System.currentTimeMillis();
        currentManga.historyLastRead = readAt;
        MangaCoroutines.io(() -> {
            if (notifyUi) MangaHistoryManager.save(appContext, currentManga, currentChapter, safePage, total, readAt);
            else MangaHistoryManager.saveReaderProgress(appContext, currentManga, currentChapter, safePage, total, readAt);
        });
    }

    private void move(int delta) {
        saveProgress();
        if (manga == null) return;
        chapters = normalizeChapters(chapters);
        if (chapters == null || chapters.isEmpty()) { reloadChaptersThenMove(delta); return; }
        if (chapters.size() <= 1) { showChapterBoundaryMessage(delta); return; }
        MangaChapter current = chapters.get(Math.max(0, Math.min(chapterPos, chapters.size() - 1)));
        int realPos = findChapterPosition(chapters, current.index);
        if (realPos >= 0) chapterPos = realPos;
        int nextPos = chapterPos + delta;
        if (nextPos < 0) { showChapterBoundaryMessage(delta); return; }
        if (nextPos >= chapters.size()) { showChapterBoundaryMessage(delta); return; }
        int oldPos = chapterPos;
        chapterPos = nextPos;
        resetPageOnNextChapter = true;
        setReaderControlsVisible(false);
        clearReaderCache(oldPos);
        load();
    }

    private void clearReaderCache(int oldChapterPos) {
        try { if (pageAdapter != null) pageAdapter.clearImages(readerRecyclerView); } catch (Exception ignored) { }
        try { MangaImageLoader.cancelPreloads(); } catch (Exception ignored) { }
    }

    private void showChapterBoundaryMessage(int delta) {
        if (closingReader || !isAdded()) return;
        AppSnackbar.show(requireContext(), delta < 0 ? "Ini chapter terakhir" : "Ini chapter pertama");
    }

    private void reloadChaptersThenMove(final int delta) {
        if (!isAdded() || manga == null || manga.slug == null || manga.slug.isEmpty()) return;
        progress.setVisibility(View.VISIBLE);
        api.chapters(manga.slug, new KomikcastClient.Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> data, boolean hasNext) {
                if (closingReader || !isAdded()) return;
                progress.setVisibility(View.GONE);
                MangaChapter current = null;
                if (chapters != null && !chapters.isEmpty()) current = chapters.get(Math.max(0, Math.min(chapterPos, chapters.size() - 1)));
                chapters = normalizeChapters(data);
                if (chapters == null || chapters.isEmpty()) { AppSnackbar.show(requireContext(), "Daftar chapter tidak tersedia"); return; }
                if (current != null) {
                    int found = findChapterPosition(chapters, current.index);
                    if (found >= 0) chapterPos = found;
                } else if (pendingSelectedChapterIndex >= 0f) {
                    int found = findChapterPosition(chapters, pendingSelectedChapterIndex);
                    chapterPos = found >= 0 ? found : Math.max(0, Math.min(chapterPos, chapters.size() - 1));
                    pendingSelectedChapterIndex = -1f;
                } else chapterPos = Math.max(0, Math.min(chapterPos, chapters.size() - 1));
                if (delta != 0 && chapters.size() <= 1) { showChapterBoundaryMessage(delta); return; }
                if (delta == 0) load(); else move(delta);
            }
            @Override public void onError(String message) {
                if (closingReader || !isAdded()) return;
                progress.setVisibility(View.GONE);
                showReaderMessage("Daftar chapter gagal dimuat: " + message);
            }
        });
    }

    private ArrayList<MangaChapter> normalizeChapters(ArrayList<MangaChapter> source) {
        ArrayList<MangaChapter> result = new ArrayList<>();
        if (source == null) return result;
        Map<String, MangaChapter> unique = new LinkedHashMap<>();
        for (MangaChapter ch : source) {
            if (ch == null || ch.index < 0) continue;
            unique.put(MangaChapter.formatIndex(ch.index), ch);
        }
        result.addAll(unique.values());
        Collections.sort(result, (a, b) -> Float.compare(b.index, a.index));
        return result;
    }

    private int findChapterPosition(ArrayList<MangaChapter> list, float index) {
        if (list == null) return -1;
        for (int i = 0; i < list.size(); i++) if (Math.abs(list.get(i).index - index) < 0.001f) return i;
        return -1;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private interface ChapterClickListener { void onClick(int position); }

    private interface ChapterEntryClickListener { void onClick(ChapterEntry entry); }

    private static class ChapterEntry {
        final MangaChapter chapter;
        final int position;
        ChapterEntry(MangaChapter chapter, int position) {
            this.chapter = chapter;
            this.position = position;
        }
    }

    private class ChapterSheetAdapter extends RecyclerView.Adapter<ChapterSheetAdapter.Holder> {
        private final ArrayList<ChapterEntry> items;
        private final MangaPost mangaData;
        private final ChapterEntryClickListener listener;

        ChapterSheetAdapter(ArrayList<ChapterEntry> items, MangaPost mangaData, ChapterEntryClickListener listener) {
            this.items = items == null ? new ArrayList<>() : items;
            this.mangaData = mangaData;
            this.listener = listener;
        }

        void setItems(ArrayList<ChapterEntry> nextItems) {
            items.clear();
            if (nextItems != null) items.addAll(nextItems);
            notifyDataSetChanged();
        }

        int findPosition(int originalPosition) {
            for (int i = 0; i < items.size(); i++) if (items.get(i).position == originalPosition) return i;
            return -1;
        }

        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context ctx = parent.getContext();
            com.google.android.material.card.MaterialCardView card = newStoreCard(ctx);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, dp(12), 0, 0);
            card.setLayoutParams(params);
            card.setClickable(true);
            card.setFocusable(true);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(18), dp(16), dp(18), dp(16));
            TextView text = new TextView(ctx);
            text.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
            text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            text.setTextColor(themeColor(ctx, com.google.android.material.R.attr.colorOnSurface));
            text.setSingleLine(false);
            text.setMaxLines(Integer.MAX_VALUE);
            text.setEllipsize(null);
            TextView progressText = new TextView(ctx);
            progressText.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
            progressText.setTextColor(themeColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant));
            LinearProgressIndicator progressBar = new LinearProgressIndicator(ctx);
            progressBar.setMax(100);
            progressBar.setIndeterminate(false);
            progressBar.setPadding(0, dp(6), 0, 0);
            row.addView(text, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams progressTextParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            progressTextParams.setMargins(0, dp(5), 0, 0);
            row.addView(progressText, progressTextParams);
            LinearLayout.LayoutParams progressBarParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(9));
            progressBarParams.setMargins(0, dp(6), 0, 0);
            row.addView(progressBar, progressBarParams);
            card.addView(row, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new Holder(card, text, progressText, progressBar);
        }

        @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
            ChapterEntry entry = items.get(position);
            MangaChapter chapter = entry.chapter;
            String title = chapter == null || chapter.title == null || chapter.title.trim().isEmpty() ? "Chapter" : chapter.title.trim();
            holder.text.setText(title);
            int savedPage = mangaData == null || chapter == null ? 0 : MangaHistoryManager.getPage(holder.itemView.getContext(), mangaData, chapter.index);
            int savedTotal = mangaData == null || chapter == null ? 0 : MangaHistoryManager.getTotalPages(holder.itemView.getContext(), mangaData, chapter.index);
            boolean hasReadProgress = savedTotal > 0;
            int progressColor = themeColor(holder.itemView.getContext(), androidx.appcompat.R.attr.colorPrimary);
            int onSurfaceColor = themeColor(holder.itemView.getContext(), com.google.android.material.R.attr.colorOnSurface);
            holder.progressText.setTextColor(progressColor);
            holder.progressBar.setIndicatorColor(progressColor);
            holder.progressBar.setTrackColor(Color.argb(0x33, Color.red(progressColor), Color.green(progressColor), Color.blue(progressColor)));
            if (hasReadProgress) {
                int current = Math.min(savedPage + 1, savedTotal);
                holder.progressText.setVisibility(View.VISIBLE);
                holder.progressBar.setVisibility(View.VISIBLE);
                holder.progressText.setText("Progres baca: " + current + " / " + savedTotal);
                holder.progressBar.setProgress(Math.max(1, Math.min(100, (current * 100) / Math.max(1, savedTotal))));
            } else {
                holder.progressText.setVisibility(View.GONE);
                holder.progressBar.setVisibility(View.GONE);
                holder.progressBar.setProgress(0);
            }
            int fg = hasReadProgress || entry.position == chapterPos ? progressColor : onSurfaceColor;
            holder.text.setTextColor(fg);
            if (holder.itemView instanceof com.google.android.material.card.MaterialCardView) {
                com.google.android.material.card.MaterialCardView card = (com.google.android.material.card.MaterialCardView) holder.itemView;
                boolean current = entry.position == chapterPos;
                card.setStrokeColor(progressColor);
                card.setStrokeWidth(current ? dp(1) : 0);
            }
            holder.itemView.setOnClickListener(v -> { if (listener != null) listener.onClick(entry); });
        }

        @Override public int getItemCount() { return items.size(); }

        class Holder extends RecyclerView.ViewHolder {
            final TextView text;
            final TextView progressText;
            final LinearProgressIndicator progressBar;
            Holder(View itemView, TextView text, TextView progressText, LinearProgressIndicator progressBar) {
                super(itemView);
                this.text = text;
                this.progressText = progressText;
                this.progressBar = progressBar;
            }
        }
    }

    private static class MangaPageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_PAGE = 1;
        private static final int TYPE_MESSAGE = 2;
        private final ArrayList<String> pages = new ArrayList<>();
        private String message;
        private final String sourceId;
        private int lastPreloadCenter = -1;

        MangaPageAdapter(String sourceId) { this.sourceId = sourceId; }

        void submit(ArrayList<String> newPages) {
            message = null;
            pages.clear();
            if (newPages != null) pages.addAll(newPages);
            lastPreloadCenter = -1;
            notifyDataSetChanged();
        }

        void preloadAround(android.content.Context context, int center) {
            if (context == null || pages.isEmpty()) return;
            int safeCenter = Math.max(0, Math.min(center, pages.size() - 1));
            if (Math.abs(safeCenter - lastPreloadCenter) < 2) return;
            lastPreloadCenter = safeCenter;
            int start = Math.max(0, safeCenter - 1);
            int end = Math.min(pages.size() - 1, safeCenter + 2);
            for (int i = start; i <= end; i++) MangaImageLoader.preload(context, pages.get(i), sourceId);
        }

        void showMessage(String text) {
            pages.clear();
            message = text;
            notifyDataSetChanged();
        }

        int getPageCount() { return message == null ? pages.size() : 0; }

        @Override public int getItemViewType(int position) { return message == null ? TYPE_PAGE : TYPE_MESSAGE; }
        @Override public int getItemCount() { return message == null ? pages.size() : 1; }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TYPE_MESSAGE) {
                TextView text = new TextView(parent.getContext());
                text.setGravity(Gravity.CENTER);
                text.setTextColor(Color.WHITE);
                text.setTextSize(15f);
                text.setPadding(48, 220, 48, 220);
                text.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new MessageHolder(text);
            }
            FrameLayout root = new FrameLayout(parent.getContext());
            root.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.setBackgroundColor(Color.WHITE);
            ImageView img;
            MangaWebtoonImageView webtoonImage = new MangaWebtoonImageView(parent.getContext());
            webtoonImage.setCropBorderEnabled(MangaSettingsManager.isReaderCropBorderEnabled(parent.getContext()));
            webtoonImage.setReaderScaleType(MangaSettingsManager.getReaderImageScale(parent.getContext()));
            img = webtoonImage;
            img.setAdjustViewBounds(false);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setBackgroundColor(Color.WHITE);
            img.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ProgressBar loading = new ProgressBar(parent.getContext());
            FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(dp(parent.getContext(), 36), dp(parent.getContext(), 36), Gravity.CENTER);
            loading.setLayoutParams(loadingParams);
            loading.setIndeterminate(true);
            loading.setVisibility(View.GONE);
            root.addView(img);
            root.addView(loading);
            return new PageHolder(root, img, loading);
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder instanceof MessageHolder) ((MessageHolder) holder).text.setText(message);
            else if (holder instanceof PageHolder) {
                PageHolder pageHolder = (PageHolder) holder;
                pageHolder.loading.setVisibility(View.VISIBLE);
                MangaImageLoader.loadForSource(pageHolder.image, pages.get(position), sourceId, false, new MangaImageLoader.Callback() {
                    @Override public void onSuccess() {
                        pageHolder.loading.setVisibility(View.GONE);
                        if (MangaSettingsManager.isReaderPageTransitionEnabled(pageHolder.image.getContext())) {
                            pageHolder.image.setAlpha(0f);
                            pageHolder.image.animate().alpha(1f).setDuration(180L).start();
                        } else pageHolder.image.setAlpha(1f);
                    }
                    @Override public void onError() { pageHolder.loading.setVisibility(View.GONE); pageHolder.image.setAlpha(1f); }
                });
            }
        }

        @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
            if (holder instanceof PageHolder) {
                PageHolder pageHolder = (PageHolder) holder;
                pageHolder.loading.setVisibility(View.GONE);
                MangaImageLoader.clear(pageHolder.image);
            }
            super.onViewRecycled(holder);
        }

        void clearImages(RecyclerView rv) {
            if (rv == null) return;
            for (int i = 0; i < rv.getChildCount(); i++) {
                View child = rv.getChildAt(i);
                RecyclerView.ViewHolder holder = rv.getChildViewHolder(child);
                if (holder instanceof PageHolder) {
                    PageHolder pageHolder = (PageHolder) holder;
                    pageHolder.loading.setVisibility(View.GONE);
                    MangaImageLoader.clear(pageHolder.image);
                }
            }
        }

        static class PageHolder extends RecyclerView.ViewHolder {
            final ImageView image;
            final ProgressBar loading;
            PageHolder(View itemView, ImageView image, ProgressBar loading) {
                super(itemView);
                this.image = image;
                this.loading = loading;
            }
        }

        static int dp(Context context, int value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }

        static class MessageHolder extends RecyclerView.ViewHolder { final TextView text; MessageHolder(TextView text) { super(text); this.text = text; } }
    }
}
