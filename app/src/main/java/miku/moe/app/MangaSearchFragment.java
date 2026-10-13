package miku.moe.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;

/**
 * Pencarian Manga Global v2.
 * Hasil dari semua source digabung dalam satu daftar vertikal, diurutkan berdasarkan kecocokan judul,
 * bisa difilter per source, dan mencari otomatis saat mengetik.
 */
public class MangaSearchFragment extends Fragment {
    private static final int STATUS_LOADING = 0;
    private static final int STATUS_DONE = 1;
    private static final int STATUS_ERROR = 2;
    private static final int STATUS_CLOUDFLARE = 3;

    private static final int SEARCH_LIMIT = 20;
    private static final long DEBOUNCE_MS = 550L;
    private static final int MAX_PARALLEL_SOURCES = 4;
    private static final long SOURCE_TIMEOUT_MS = 25000L;
    private static final long RENDER_THROTTLE_MS = 120L;
    private static final int SKELETON_ROWS = 5;
    private static final int SKELETON_MORE_ROWS = 2;
    private static final java.util.regex.Pattern NON_WORD = java.util.regex.Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final int MAX_RECENT = 12;
    private static final String PREFS = "miku_manga_search_v2";
    private static final String KEY_RECENT = "recent";

    private static final class SourceState {
        final String id;
        final String label;
        int status = STATUS_LOADING;
        boolean finished;
        Runnable timeout;
        final ArrayList<MangaPost> items = new ArrayList<>();
        final ArrayList<Integer> scores = new ArrayList<>();

        SourceState(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private static final class Scored {
        final MangaPost post;
        final int score;
        final int sourceIndex;
        final int position;

        Scored(MangaPost post, int score, int sourceIndex, int position) {
            this.post = post;
            this.score = score;
            this.sourceIndex = sourceIndex;
            this.position = position;
        }
    }

    /** Satu putaran pencarian: sumber dijalankan bergantian (maks. MAX_PARALLEL_SOURCES sekaligus), bukan semuanya serentak. */
    private static final class SearchRun {
        final int id;
        final java.util.ArrayDeque<SourceState> pending = new java.util.ArrayDeque<>();
        int active;

        SearchRun(int id) { this.id = id; }
    }

    private EditText searchEditText;
    private View clearButton;
    private LinearProgressIndicator searchProgress;
    private HorizontalScrollView filterScroll;
    private LinearLayout filterChips;
    private TextView statusText;
    private RecyclerView resultList;
    private View idleState;
    private TextView idleTitle;
    private TextView idleSubtitle;
    private View recentSection;
    private ChipGroup recentGroup;
    private ProgressBar progressBar;
    private MangaSearchV2Adapter adapter;

    private final ArrayList<SourceState> states = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable debounceRunnable = () -> searchAll(false);
    private String activeQuery = "";
    private String selectedSource = "";
    private int generation = 0;
    private int chapterRequestGeneration = 0;
    private boolean ignoreTextChange = false;
    private SearchRun currentRun;
    private String activeQueryNorm = "";
    private boolean renderScheduled = false;
    private String lastFilterSignature = "";
    private final Runnable renderRunnable = () -> {
        renderScheduled = false;
        render();
    };

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_manga_search_v2, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        searchEditText = view.findViewById(R.id.searchEditText);
        clearButton = view.findViewById(R.id.searchClearButton);
        searchProgress = view.findViewById(R.id.searchProgress);
        filterScroll = view.findViewById(R.id.searchFilterScroll);
        filterChips = view.findViewById(R.id.searchFilterChips);
        statusText = view.findViewById(R.id.searchStatusText);
        resultList = view.findViewById(R.id.searchResultList);
        idleState = view.findViewById(R.id.searchIdleState);
        idleTitle = view.findViewById(R.id.searchIdleTitle);
        idleSubtitle = view.findViewById(R.id.searchIdleSubtitle);
        recentSection = view.findViewById(R.id.searchRecentSection);
        recentGroup = view.findViewById(R.id.searchRecentGroup);
        progressBar = view.findViewById(R.id.progressBar);

        adapter = new MangaSearchV2Adapter(requireContext(), new MangaSearchV2Adapter.Listener() {
            @Override public void onMangaClick(MangaPost post) {
                if (!isAdded() || post == null) return;
                saveRecent(activeQuery);
                hideKeyboard();
                ((MainActivity) requireActivity()).openMangaDetail(post);
            }

            @Override public void onChapterClick(MangaPost post) { openLatestChapter(post); }

            @Override public void onBannerClick() { showCloudflareChooser(); }

            @Override public void onFooterClick(String sourceId, String sourceLabel) { openViewAll(sourceId, sourceLabel); }
        });
        resultList.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false));
        resultList.setItemAnimator(null);
        resultList.setHasFixedSize(true);
        resultList.setItemViewCacheSize(8);
        resultList.setAdapter(adapter);
        resultList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard();
            }
        });

        searchEditText.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchAll(true);
                return true;
            }
            return false;
        });
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override public void afterTextChanged(Editable s) {
                if (ignoreTextChange) return;
                String value = s == null ? "" : s.toString().trim();
                clearButton.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
                handler.removeCallbacks(debounceRunnable);
                if (value.isEmpty()) {
                    resetToIdle();
                    return;
                }
                if (value.equals(activeQuery)) return;
                if (value.length() >= 2) handler.postDelayed(debounceRunnable, DEBOUNCE_MS);
            }
        });
        clearButton.setOnClickListener(v -> {
            searchEditText.setText("");
            searchEditText.requestFocus();
        });
        view.findViewById(R.id.searchRecentClear).setOnClickListener(v -> {
            clearRecents();
            refreshRecents();
        });
        resetToIdle();
    }

    public void refreshSourceSettings() {
        if (searchEditText == null) return;
        String value = currentQuery();
        if (!value.isEmpty()) searchAll(false);
    }

    // ---------------------------------------------------------------- pencarian

    private String currentQuery() {
        return searchEditText == null || searchEditText.getText() == null ? "" : searchEditText.getText().toString().trim();
    }

    private void searchAll(boolean explicit) {
        if (!isAdded()) return;
        handler.removeCallbacks(debounceRunnable);
        String query = currentQuery();
        int run = ++generation;
        chapterRequestGeneration++;
        states.clear();
        currentRun = null;
        selectedSource = "";
        lastFilterSignature = "";
        if (query.isEmpty()) {
            resetToIdle();
            return;
        }
        activeQuery = query;
        activeQueryNorm = normalize(query);
        if (explicit) {
            saveRecent(query);
            hideKeyboard();
        }
        ArrayList<String> sourceIds = MangaSourceFactory.enabledSourceIds(requireContext());
        for (String sourceId : sourceIds) states.add(new SourceState(sourceId, MangaSourceFactory.labelForSourceId(sourceId)));
        render();
        if (states.isEmpty()) return;
        SearchRun searchRun = new SearchRun(run);
        searchRun.pending.addAll(states);
        currentRun = searchRun;
        pumpSearches(searchRun, query);
    }

    private void pumpSearches(SearchRun searchRun, String query) {
        while (isAdded() && searchRun == currentRun && searchRun.active < MAX_PARALLEL_SOURCES && !searchRun.pending.isEmpty()) {
            SourceState state = searchRun.pending.poll();
            if (state == null) continue;
            searchRun.active++;
            searchSource(searchRun, state, query);
        }
    }

    private void finishSource(SearchRun searchRun, SourceState state, int status, ArrayList<MangaPost> data) {
        if (state.finished) return;
        state.finished = true;
        if (state.timeout != null) {
            handler.removeCallbacks(state.timeout);
            state.timeout = null;
        }
        if (!isAdded() || searchRun != currentRun) return;
        searchRun.active = Math.max(0, searchRun.active - 1);
        if (status == STATUS_DONE) applySourceData(state, data);
        else {
            state.items.clear();
            state.scores.clear();
            state.status = status;
        }
        scheduleRender();
        pumpSearches(searchRun, activeQuery);
    }

    private void searchSource(SearchRun searchRun, SourceState state, String query) {
        // Sumber yang macet tidak boleh menahan antrean: lewat batas waktu dianggap gagal.
        state.timeout = () -> {
            state.timeout = null;
            finishSource(searchRun, state, STATUS_ERROR, null);
        };
        handler.postDelayed(state.timeout, SOURCE_TIMEOUT_MS);
        try {
            KomikcastClient api = MangaSourceFactory.createBySourceId(state.id);
            api.list(1, "latest", query, "", new KomikcastClient.Result<ArrayList<MangaPost>>() {
                @Override public void onSuccess(ArrayList<MangaPost> data, boolean hasNext) {
                    finishSource(searchRun, state, STATUS_DONE, data);
                }

                @Override public void onError(String message) {
                    boolean cloudflare = CloudflareHelper.isCloudflareRequiredMessage(message) || CloudflareHelper.needsResolution(state.label);
                    finishSource(searchRun, state, cloudflare ? STATUS_CLOUDFLARE : STATUS_ERROR, null);
                }
            });
        } catch (Exception e) {
            finishSource(searchRun, state, STATUS_ERROR, null);
        }
    }

    private void applySourceData(SourceState state, ArrayList<MangaPost> data) {
        state.items.clear();
        state.scores.clear();
        state.status = STATUS_DONE;
        if (data == null) return;
        Context context = getContext();
        boolean hideChapter = context != null && !MangaSettingsManager.shouldLoadLatestChapterLabel(context);
        boolean hideType = context != null && !MangaSettingsManager.shouldLoadTypeLabel(context);
        HashSet<String> keys = new HashSet<>();
        for (MangaPost post : data) {
            if (post == null) continue;
            post.withSource(state.id, state.label);
            if (hideChapter) {
                post.latestChapter = "";
                post.latestChapterDate = "";
                post.totalChapters = 0;
            }
            if (hideType) post.typeLabel = "";
            String key = post.slug == null || post.slug.trim().isEmpty() ? post.title : post.slug;
            if (key == null || key.trim().isEmpty() || !keys.add(key.trim())) continue;
            state.items.add(post);
            state.scores.add(score(post.title, activeQueryNorm));
            if (state.items.size() >= SEARCH_LIMIT) break;
        }
    }

    private void resetToIdle() {
        generation++;
        chapterRequestGeneration++;
        handler.removeCallbacks(debounceRunnable);
        states.clear();
        currentRun = null;
        selectedSource = "";
        activeQuery = "";
        activeQueryNorm = "";
        lastFilterSignature = "";
        render();
    }

    /** Menggabungkan banyak pembaruan beruntun jadi satu render agar UI tidak tersendat. */
    private void scheduleRender() {
        if (renderScheduled) return;
        renderScheduled = true;
        handler.postDelayed(renderRunnable, RENDER_THROTTLE_MS);
    }

    // ---------------------------------------------------------------- tampilan

    private void render() {
        renderScheduled = false;
        handler.removeCallbacks(renderRunnable);
        if (!isAdded() || adapter == null) return;
        if (activeQuery.isEmpty()) {
            filterScroll.setVisibility(View.GONE);
            statusText.setVisibility(View.GONE);
            searchProgress.setVisibility(View.GONE);
            resultList.setVisibility(View.GONE);
            adapter.submitList(new ArrayList<>());
            idleTitle.setText("Cari di semua sumber");
            idleSubtitle.setText("Hasil dari semua sumber aktif digabung dan diurutkan berdasarkan kecocokan judul.");
            idleState.setVisibility(View.VISIBLE);
            refreshRecents();
            return;
        }
        SourceState selected = findState(selectedSource);
        if (!selectedSource.isEmpty() && (selected == null || (selected.status != STATUS_LOADING && selected.items.isEmpty()))) {
            selectedSource = "";
            selected = null;
        }
        ArrayList<MangaSearchV2Adapter.Row> rows = buildRows(selected);
        boolean loading = anyLoading();
        boolean hasResultRows = false;
        for (MangaSearchV2Adapter.Row row : rows) {
            if (row.kind == MangaSearchV2Adapter.KIND_RESULT) { hasResultRows = true; break; }
        }
        if (loading && selected == null) {
            // Shimmer: penuh saat belum ada hasil, dua kartu di bawah saat sumber lain masih diproses.
            int skeletons = hasResultRows ? SKELETON_MORE_ROWS : SKELETON_ROWS;
            for (int i = 0; i < skeletons; i++) rows.add(MangaSearchV2Adapter.Row.skeleton(i));
        }
        adapter.submitList(rows);
        updateFilterChips();
        updateStatus();

        if (rows.isEmpty()) {
            resultList.setVisibility(View.GONE);
            idleState.setVisibility(View.VISIBLE);
            recentSection.setVisibility(View.GONE);
            if (states.isEmpty()) {
                idleTitle.setText("Belum ada sumber aktif");
                idleSubtitle.setText("Aktifkan minimal satu sumber manga di pengaturan.");
            } else if (loading) {
                idleTitle.setText("Mencari…");
                idleSubtitle.setText("Menunggu hasil dari " + states.size() + " sumber.");
            } else {
                idleTitle.setText("Tidak ada hasil untuk “" + activeQuery + "”");
                idleSubtitle.setText("Coba kata kunci lain atau periksa sumber yang aktif.");
            }
        } else {
            idleState.setVisibility(View.GONE);
            resultList.setVisibility(View.VISIBLE);
        }
    }

    private ArrayList<MangaSearchV2Adapter.Row> buildRows(@Nullable SourceState selected) {
        ArrayList<MangaSearchV2Adapter.Row> rows = new ArrayList<>();
        if (selected != null) {
            for (MangaPost post : selected.items) rows.add(MangaSearchV2Adapter.Row.result(post));
            if (!selected.items.isEmpty()) rows.add(MangaSearchV2Adapter.Row.footer(selected.id, "Lihat semua hasil di " + selected.label));
            return rows;
        }
        int cloudflare = countStatus(STATUS_CLOUDFLARE);
        if (cloudflare > 0) rows.add(MangaSearchV2Adapter.Row.banner(cloudflare + " sumber butuh verifikasi Cloudflare. Ketuk untuk menyelesaikan."));
        ArrayList<Scored> scored = new ArrayList<>();
        for (int s = 0; s < states.size(); s++) {
            SourceState state = states.get(s);
            for (int p = 0; p < state.items.size(); p++) {
                MangaPost post = state.items.get(p);
                int value = p < state.scores.size() ? state.scores.get(p) : 4;
                scored.add(new Scored(post, value, s, p));
            }
        }
        Collections.sort(scored, new Comparator<Scored>() {
            @Override public int compare(Scored a, Scored b) {
                if (a.score != b.score) return a.score < b.score ? -1 : 1;
                if (a.sourceIndex != b.sourceIndex) return a.sourceIndex < b.sourceIndex ? -1 : 1;
                return Integer.compare(a.position, b.position);
            }
        });
        for (Scored item : scored) rows.add(MangaSearchV2Adapter.Row.result(item.post));
        return rows;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return NON_WORD.matcher(value.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    /** query harus sudah dinormalisasi (lihat activeQueryNorm). */
    private static int score(String title, String q) {
        String t = normalize(title);
        if (q.isEmpty() || t.isEmpty()) return 4;
        if (t.equals(q)) return 0;
        if (t.startsWith(q)) return 1;
        if (t.contains(q)) return 2;
        for (String token : q.split(" ")) {
            if (!token.isEmpty() && !t.contains(token)) return 4;
        }
        return 3;
    }

    private void updateFilterChips() {
        int total = totalResults();
        boolean showFilters = total > 0 || anyLoading();
        filterScroll.setVisibility(showFilters ? View.VISIBLE : View.GONE);
        if (!showFilters) {
            filterChips.removeAllViews();
            lastFilterSignature = "";
            return;
        }
        StringBuilder signature = new StringBuilder();
        signature.append("Semua ").append(total).append('|').append(selectedSource.isEmpty());
        ArrayList<String[]> chips = new ArrayList<>();
        for (SourceState state : states) {
            if (state.status == STATUS_ERROR || state.status == STATUS_CLOUDFLARE) continue;
            if (state.status == STATUS_DONE && state.items.isEmpty()) continue;
            String count = state.status == STATUS_LOADING ? "…" : String.valueOf(state.items.size());
            chips.add(new String[]{state.id, state.label + " " + count});
            signature.append(';').append(state.id).append(':').append(count).append(':').append(state.id.equals(selectedSource));
        }
        String sig = signature.toString();
        if (sig.equals(lastFilterSignature)) return;
        lastFilterSignature = sig;
        filterChips.removeAllViews();
        addFilterChip("", "Semua " + total, selectedSource.isEmpty());
        for (String[] chip : chips) addFilterChip(chip[0], chip[1], chip[0].equals(selectedSource));
    }

    private void addFilterChip(String sourceId, String label, boolean selected) {
        TextView chip = (TextView) LayoutInflater.from(requireContext()).inflate(R.layout.manga_home_v3_source_chip, filterChips, false);
        chip.setText(label);
        chip.setSelected(selected);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(dp(8));
        chip.setLayoutParams(params);
        chip.setOnClickListener(v -> {
            if (sourceId.equals(selectedSource)) return;
            selectedSource = sourceId;
            render();
            if (resultList != null) resultList.scrollToPosition(0);
        });
        filterChips.addView(chip);
    }

    private void updateStatus() {
        int total = states.size();
        int finished = 0;
        for (SourceState state : states) if (state.status != STATUS_LOADING) finished++;
        int results = totalResults();
        boolean loading = finished < total;
        if (loading) {
            searchProgress.setVisibility(View.VISIBLE);
            searchProgress.setProgressCompat(total == 0 ? 0 : (finished * 100 / total), true);
            String text = "Mencari… " + finished + "/" + total + " sumber";
            if (results > 0) text += " · " + results + " hasil";
            statusText.setText(text);
        } else {
            searchProgress.setVisibility(View.GONE);
            int sourcesWithResults = 0;
            for (SourceState state : states) if (!state.items.isEmpty()) sourcesWithResults++;
            int failed = countStatus(STATUS_ERROR);
            int cloudflare = countStatus(STATUS_CLOUDFLARE);
            StringBuilder text = new StringBuilder();
            if (results == 0) text.append("Tidak ada hasil");
            else text.append(results).append(" hasil dari ").append(sourcesWithResults).append(" sumber");
            if (failed > 0) text.append(" · ").append(failed).append(" gagal");
            if (cloudflare > 0 && results == 0) text.append(" · ").append(cloudflare).append(" butuh Cloudflare");
            statusText.setText(text.toString());
        }
        statusText.setVisibility(total == 0 ? View.GONE : View.VISIBLE);
    }

    private SourceState findState(String sourceId) {
        if (sourceId == null || sourceId.isEmpty()) return null;
        for (SourceState state : states) if (state.id.equals(sourceId)) return state;
        return null;
    }

    private boolean anyLoading() {
        for (SourceState state : states) if (state.status == STATUS_LOADING) return true;
        return false;
    }

    private int countStatus(int status) {
        int count = 0;
        for (SourceState state : states) if (state.status == status) count++;
        return count;
    }

    private int totalResults() {
        int count = 0;
        for (SourceState state : states) count += state.items.size();
        return count;
    }

    // ---------------------------------------------------------------- riwayat

    private SharedPreferences prefs() {
        return requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private ArrayList<String> loadRecents() {
        ArrayList<String> out = new ArrayList<>();
        if (!isAdded()) return out;
        String raw = prefs().getString(KEY_RECENT, "");
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split("\n")) {
            String clean = part.trim();
            if (!clean.isEmpty()) out.add(clean);
        }
        return out;
    }

    private void storeRecents(ArrayList<String> values) {
        if (!isAdded()) return;
        prefs().edit().putString(KEY_RECENT, android.text.TextUtils.join("\n", values)).apply();
    }

    private void saveRecent(String query) {
        if (!isAdded() || query == null) return;
        String clean = query.trim().replace("\n", " ");
        if (clean.isEmpty()) return;
        ArrayList<String> recents = loadRecents();
        for (int i = recents.size() - 1; i >= 0; i--) if (recents.get(i).equalsIgnoreCase(clean)) recents.remove(i);
        recents.add(0, clean);
        while (recents.size() > MAX_RECENT) recents.remove(recents.size() - 1);
        storeRecents(recents);
    }

    private void removeRecent(String query) {
        ArrayList<String> recents = loadRecents();
        for (int i = recents.size() - 1; i >= 0; i--) if (recents.get(i).equalsIgnoreCase(query)) recents.remove(i);
        storeRecents(recents);
    }

    private void clearRecents() {
        if (isAdded()) prefs().edit().remove(KEY_RECENT).apply();
    }

    private void refreshRecents() {
        if (!isAdded() || recentGroup == null) return;
        recentGroup.removeAllViews();
        ArrayList<String> recents = loadRecents();
        recentSection.setVisibility(recents.isEmpty() ? View.GONE : View.VISIBLE);
        int surface = MaterialColors.getColor(recentGroup, com.google.android.material.R.attr.colorSurfaceContainerHigh);
        int onSurface = MaterialColors.getColor(recentGroup, com.google.android.material.R.attr.colorOnSurface);
        int onSurfaceVariant = MaterialColors.getColor(recentGroup, com.google.android.material.R.attr.colorOnSurfaceVariant);
        for (String query : recents) {
            Chip chip = new Chip(requireContext());
            chip.setText(query);
            chip.setTextSize(13f);
            chip.setTextColor(onSurface);
            chip.setChipBackgroundColor(ColorStateList.valueOf(surface));
            chip.setChipStrokeWidth(0f);
            chip.setChipCornerRadius(dp(10));
            chip.setEnsureMinTouchTargetSize(false);
            chip.setCloseIconResource(R.drawable.ic_close);
            chip.setCloseIconSize(dp(14));
            chip.setCloseIconTint(ColorStateList.valueOf(onSurfaceVariant));
            chip.setCloseIconVisible(true);
            chip.setOnClickListener(v -> {
                ignoreTextChange = true;
                searchEditText.setText(query);
                searchEditText.setSelection(query.length());
                ignoreTextChange = false;
                clearButton.setVisibility(View.VISIBLE);
                searchAll(true);
            });
            chip.setOnCloseIconClickListener(v -> {
                removeRecent(query);
                refreshRecents();
            });
            recentGroup.addView(chip);
        }
    }

    // ---------------------------------------------------------------- aksi

    private void hideKeyboard() {
        if (!isAdded() || searchEditText == null) return;
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchEditText.getWindowToken(), 0);
    }

    private int dp(int value) {
        return Math.round(value * requireContext().getResources().getDisplayMetrics().density);
    }

    private void openViewAll(String sourceId, String sourceLabel) {
        if (!isAdded() || sourceId == null) return;
        if (requireActivity() instanceof MainActivity) ((MainActivity) requireActivity()).openMangaBrowseSource(sourceId, sourceLabel, activeQuery);
    }

    private void showCloudflareChooser() {
        if (!isAdded()) return;
        ArrayList<SourceState> blocked = new ArrayList<>();
        for (SourceState state : states) if (state.status == STATUS_CLOUDFLARE) blocked.add(state);
        if (blocked.isEmpty()) return;
        if (blocked.size() == 1) {
            openCloudflare(blocked.get(0));
            return;
        }
        String[] names = new String[blocked.size()];
        for (int i = 0; i < blocked.size(); i++) names[i] = blocked.get(i).label;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Selesaikan Cloudflare")
                .setItems(names, (dialog, which) -> openCloudflare(blocked.get(which)))
                .show();
    }

    private void openCloudflare(SourceState state) {
        if (!isAdded() || state == null) return;
        boolean opened = CloudflareHelper.openResolverForSource(requireContext(), state.id, state.label);
        if (!opened) Toast.makeText(requireContext(), "Gagal membuka halaman Cloudflare", Toast.LENGTH_SHORT).show();
    }

    private void openLatestChapter(MangaPost post) {
        if (!isAdded() || post == null || post.slug == null || post.slug.trim().isEmpty()) return;
        int requestToken = ++chapterRequestGeneration;
        int searchToken = generation;
        hideKeyboard();
        if (progressBar != null) progressBar.setVisibility(View.VISIBLE);
        MangaSourceFactory.createBySourceId(post.getSourceId()).chapters(post.slug, new KomikcastClient.Result<ArrayList<MangaChapter>>() {
            @Override public void onSuccess(ArrayList<MangaChapter> chapters, boolean hasNext) {
                if (!isAdded() || !isVisible() || requestToken != chapterRequestGeneration || searchToken != generation) return;
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                if (chapters == null || chapters.isEmpty()) {
                    Toast.makeText(requireContext(), "Chapter belum tersedia", Toast.LENGTH_SHORT).show();
                    return;
                }
                ((MainActivity) requireActivity()).openMangaReader(post, new ArrayList<>(chapters), findChapterPosition(chapters, post.latestChapter));
            }

            @Override public void onError(String message) {
                if (!isAdded() || !isVisible() || requestToken != chapterRequestGeneration || searchToken != generation) return;
                if (progressBar != null) progressBar.setVisibility(View.GONE);
                Toast.makeText(requireContext(), message == null || message.trim().isEmpty() ? "Gagal membuka chapter" : message, Toast.LENGTH_SHORT).show();
            }
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

    @Override public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden) {
            chapterRequestGeneration++;
            hideKeyboard();
            if (progressBar != null) progressBar.setVisibility(View.GONE);
        }
    }

    @Override public void onDestroyView() {
        generation++;
        chapterRequestGeneration++;
        handler.removeCallbacksAndMessages(null);
        currentRun = null;
        renderScheduled = false;
        lastFilterSignature = "";
        if (resultList != null) resultList.setAdapter(null);
        resultList = null;
        adapter = null;
        searchEditText = null;
        clearButton = null;
        searchProgress = null;
        filterScroll = null;
        filterChips = null;
        statusText = null;
        idleState = null;
        idleTitle = null;
        idleSubtitle = null;
        recentSection = null;
        recentGroup = null;
        progressBar = null;
        super.onDestroyView();
    }
}
