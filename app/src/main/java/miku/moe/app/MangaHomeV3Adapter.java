package miku.moe.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.Chip;
import com.google.android.material.imageview.ShapeableImageView;
import java.util.ArrayList;

public class MangaHomeV3Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public interface Listener extends MangaHomeV1Adapter.Listener {
        void onSelectSource(String sourceId);
        void onSelectPopularSort(String sort);
        /** Tombol play kartu "Lanjutkan membaca": langsung buka reader di chapter terakhir dibaca. */
        void onContinueRead(MangaHistoryManager.Entry entry);
    }

    private static final int TYPE_HEADER = 1;
    private static final int TYPE_HERO = 2;
    private static final int TYPE_CONTINUE = 3;
    private static final int TYPE_LATEST_HEADER = 4;
    private static final int TYPE_LATEST_ITEM = 5;
    private static final int TYPE_POPULAR_HEADER = 6;
    private static final int TYPE_POPULAR_RAIL = 7;
    private static final int LATEST_LIMIT = 8;
    private static final int SKELETON_LATEST = 4;
    private static final int SKELETON_RAIL = 5;
    private static final int SKELETON_RAIL_MORE = 2;
    private final Context context;
    private final String sourceId;
    private final ArrayList<MangaPost> popular;
    private final ArrayList<MangaPost> latest;
    private final ArrayList<MangaPost> heroData = new ArrayList<>();
    private final ArrayList<String[]> popularSorts;
    private ArrayList<MangaPost> railData;
    private String popularSort = MangaHomeV3Sorts.DEFAULT;
    private final Listener listener;
    private MangaHistoryManager.Entry continueEntry;
    private boolean loadingInitial;
    private boolean loadingPopularMore;
    private String errorMessage = "";
    private final java.util.Set<MangaPost> chapterRequested = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<MangaPost, Boolean>());

    public MangaHomeV3Adapter(Context context, String sourceId, ArrayList<MangaPost> popular, ArrayList<MangaPost> latest, Listener listener) {
        this.context = context;
        this.sourceId = sourceId == null ? "" : sourceId;
        this.popular = popular;
        this.latest = latest;
        this.listener = listener;
        this.popularSorts = MangaHomeV3Sorts.forSource(this.sourceId);
        this.railData = popular;
        setHasStableIds(true);
    }

    public String getPopularSort() {
        return popularSort;
    }

    public String getPopularSortLabel() {
        return MangaHomeV3Sorts.labelFor(sourceId, popularSort);
    }

    public void setPopularSort(String sort, ArrayList<MangaPost> data) {
        this.popularSort = sort == null || sort.isEmpty() ? MangaHomeV3Sorts.DEFAULT : sort;
        this.railData = data == null ? popular : data;
    }

    public static String genreText(MangaPost post) {
        if (post == null) return "";
        String value = post.genre == null ? "" : post.genre.trim();
        if (value.isEmpty()) return post.typeLabel == null ? "" : post.typeLabel.trim();
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (String part : value.split(",")) {
            String clean = part.trim();
            if (clean.isEmpty()) continue;
            if (out.length() > 0) out.append(" · ");
            out.append(clean);
            count++;
            if (count >= 3) break;
        }
        return out.toString();
    }

    public void updateState(MangaHistoryManager.Entry continueEntry, boolean loadingInitial, boolean loadingPopularMore, boolean loadingMore, String errorMessage) {
        this.continueEntry = continueEntry;
        this.loadingInitial = loadingInitial;
        this.loadingPopularMore = loadingPopularMore;
        this.errorMessage = errorMessage == null ? "" : errorMessage.trim();
        rebuildHero();
        notifyDataSetChanged();
    }

    private void rebuildHero() {
        heroData.clear();
        ArrayList<MangaPost> source = popular.isEmpty() ? latest : popular;
        for (int i = 0; i < source.size() && heroData.size() < MangaHomeV3HeroAdapter.MAX_ITEMS; i++) heroData.add(source.get(i));
    }

    public int getSpanSize(int position) {
        return 3;
    }

    public void notifyChapterChanged(MangaPost post) {
        if (getItemCount() > 1) notifyItemChanged(1);
        String key = itemKey(post);
        int start = latestStartPosition();
        int count = Math.min(latestCount(), latest.size());
        for (int i = 0; i < count; i++) {
            if (key.equals(itemKey(latest.get(i)))) notifyItemChanged(start + i);
        }
        int rail = railPosition();
        if (rail < getItemCount()) notifyItemChanged(rail);
    }

    private void notifyGenreChanged(MangaPost post) {
        String key = itemKey(post);
        int start = latestStartPosition();
        int count = Math.min(latestCount(), latest.size());
        for (int i = 0; i < count; i++) {
            if (key.equals(itemKey(latest.get(i)))) notifyItemChanged(start + i);
        }
    }

    private String itemKey(MangaPost post) {
        if (post == null) return "";
        String value = post.slug == null || post.slug.trim().isEmpty() ? post.title : post.slug;
        return post.getSourceId() + "|" + (value == null ? "" : value.trim());
    }

    private boolean hasContinue() {
        return continueEntry != null && continueEntry.manga != null;
    }

    private boolean showLatestSkeleton() {
        return latest.isEmpty() && loadingInitial;
    }

    private int latestCount() {
        return showLatestSkeleton() ? SKELETON_LATEST : Math.min(latest.size(), LATEST_LIMIT);
    }

    private int latestHeaderPosition() {
        return hasContinue() ? 3 : 2;
    }

    private int latestStartPosition() {
        return latestHeaderPosition() + 1;
    }

    private int popularHeaderPosition() {
        return latestStartPosition() + latestCount();
    }

    private int railPosition() {
        return popularHeaderPosition() + 1;
    }

    @Override public int getItemCount() {
        return railPosition() + 1;
    }

    @Override public long getItemId(int position) {
        if (getItemViewType(position) == TYPE_LATEST_ITEM) {
            int latestIndex = position - latestStartPosition();
            if (latestIndex >= latest.size()) return 0x200000000L + latestIndex;
            MangaPost post = latest.get(latestIndex);
            String value = post.slug == null || post.slug.isEmpty() ? post.title : post.slug;
            return 0x100000000L + (post.getSourceId() + ":" + value).hashCode();
        }
        return Long.MIN_VALUE + getItemViewType(position);
    }

    @Override public int getItemViewType(int position) {
        if (position == 0) return TYPE_HEADER;
        if (position == 1) return TYPE_HERO;
        if (hasContinue() && position == 2) return TYPE_CONTINUE;
        if (position == latestHeaderPosition()) return TYPE_LATEST_HEADER;
        if (position < popularHeaderPosition()) return TYPE_LATEST_ITEM;
        if (position == popularHeaderPosition()) return TYPE_POPULAR_HEADER;
        return TYPE_POPULAR_RAIL;
    }

    @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(context);
        if (viewType == TYPE_HEADER) return new HeaderHolder(inflater.inflate(R.layout.manga_home_v3_header, parent, false));
        if (viewType == TYPE_HERO) return new HeroHolder(inflater.inflate(R.layout.manga_home_v3_hero_section, parent, false));
        if (viewType == TYPE_CONTINUE) return new ContinueHolder(inflater.inflate(R.layout.manga_home_v3_continue, parent, false));
        if (viewType == TYPE_LATEST_ITEM) return new LatestHolder(inflater.inflate(R.layout.manga_home_v3_latest_item, parent, false));
        if (viewType == TYPE_POPULAR_RAIL) return new RailHolder(inflater.inflate(R.layout.manga_home_v3_rail, parent, false));
        return new SectionHolder(inflater.inflate(R.layout.manga_home_v3_section_header, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        int type = getItemViewType(position);
        if (type == TYPE_HEADER) bindHeader((HeaderHolder) holder);
        else if (type == TYPE_HERO) bindHero((HeroHolder) holder);
        else if (type == TYPE_CONTINUE) bindContinue((ContinueHolder) holder);
        else if (type == TYPE_LATEST_HEADER) bindSection((SectionHolder) holder, "Update Terbaru Hari ini", "latest");
        else if (type == TYPE_LATEST_ITEM) bindLatest((LatestHolder) holder, position - latestStartPosition());
        else if (type == TYPE_POPULAR_HEADER) bindSection((SectionHolder) holder, getPopularSortLabel() + " minggu ini", "popular");
        else bindRail((RailHolder) holder);
    }

    private void bindHeader(HeaderHolder holder) {
        holder.search.setOnClickListener(v -> {
            if (listener != null) listener.onBrowseSource();
        });
        holder.chips.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(context);
        View selectedView = null;
        for (String id : MangaSourceFactory.allSourceIds()) {
            TextView chip = (TextView) inflater.inflate(R.layout.manga_home_v3_source_chip, holder.chips, false);
            chip.setText(MangaSourceFactory.labelForSourceId(id));
            boolean selected = id.equals(sourceId);
            chip.setSelected(selected);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(dp(8));
            chip.setLayoutParams(params);
            chip.setOnClickListener(v -> {
                if (!id.equals(sourceId) && listener != null) listener.onSelectSource(id);
            });
            holder.chips.addView(chip);
            if (selected) selectedView = chip;
        }
        if (selectedView != null && !holder.positioned) {
            holder.positioned = true;
            View target = selectedView;
            holder.scroll.post(() -> holder.scroll.scrollTo(Math.max(0, target.getLeft() - dp(16)), 0));
        }
        if (errorMessage.isEmpty()) {
            holder.status.setText("");
            holder.status.setVisibility(View.GONE);
        } else {
            holder.status.setText(errorMessage);
            holder.status.setVisibility(View.VISIBLE);
        }
    }

    private void bindHero(HeroHolder holder) {
        if (holder.adapter == null) {
            LinearLayoutManager manager = new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false);
            holder.recycler.setLayoutManager(manager);
            holder.recycler.setItemAnimator(null);
            holder.recycler.setItemViewCacheSize(3);
            holder.adapter = new MangaHomeV3HeroAdapter(context, heroData, new MangaHomeV3HeroAdapter.Listener() {
                @Override public void onClick(MangaPost post) {
                    if (listener != null) listener.onMangaClick(post);
                }

                @Override public void onNeedChapter(MangaPost post) {
                    if (listener != null) listener.onNeedChapter(post);
                }

                @Override public void onChapterClick(MangaPost post) {
                    if (listener != null) listener.onChapterClick(post);
                }
            });
            holder.recycler.setAdapter(holder.adapter);
            PagerSnapHelper snapHelper = new PagerSnapHelper();
            snapHelper.attachToRecyclerView(holder.recycler);
            holder.recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                    if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                    View snapView = snapHelper.findSnapView(manager);
                    if (snapView == null) return;
                    updateDots(holder, manager.getPosition(snapView));
                }
            });
        }
        holder.adapter.setSkeleton(loadingInitial && heroData.isEmpty());
        holder.adapter.notifyDataSetChanged();
        buildDots(holder);
        holder.progress.setVisibility(View.GONE);
    }

    private void buildDots(HeroHolder holder) {
        int count = Math.min(heroData.size(), MangaHomeV3HeroAdapter.MAX_ITEMS);
        holder.dots.removeAllViews();
        holder.dots.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < count; i++) {
            View dot = new View(context);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(6), dp(6));
            params.setMargins(dp(3), 0, dp(3), 0);
            dot.setLayoutParams(params);
            holder.dots.addView(dot);
        }
        RecyclerView.LayoutManager manager = holder.recycler.getLayoutManager();
        int current = 0;
        if (manager instanceof LinearLayoutManager) {
            int first = ((LinearLayoutManager) manager).findFirstCompletelyVisibleItemPosition();
            if (first >= 0) current = first;
        }
        updateDots(holder, current);
    }

    private void updateDots(HeroHolder holder, int selected) {
        for (int i = 0; i < holder.dots.getChildCount(); i++) {
            View dot = holder.dots.getChildAt(i);
            boolean on = i == selected;
            dot.setBackgroundResource(on ? R.drawable.home_v3_dot_on : R.drawable.home_v3_dot_off);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) dot.getLayoutParams();
            params.width = dp(on ? 20 : 6);
            dot.setLayoutParams(params);
        }
    }

    private void bindContinue(ContinueHolder holder) {
        if (continueEntry == null || continueEntry.manga == null) return;
        MangaPost manga = continueEntry.manga;
        holder.title.setText(manga.title == null ? "" : manga.title);
        MangaTitleStyle.apply(holder.title, context);
        String chapter = continueEntry.chapterTitle == null ? "" : continueEntry.chapterTitle.trim();
        if (chapter.isEmpty()) chapter = "Chapter " + formatChapter(continueEntry.chapterIndex);
        if (continueEntry.totalPages > 0) {
            int page = Math.max(1, Math.min(continueEntry.page + 1, continueEntry.totalPages));
            holder.chapter.setText(chapter + " · halaman " + page + " dari " + continueEntry.totalPages);
            holder.progress.setProgress(Math.round(page * 100f / continueEntry.totalPages));
            holder.progress.setVisibility(View.VISIBLE);
        } else {
            holder.chapter.setText(chapter);
            holder.progress.setVisibility(View.GONE);
        }
        String cover = manga.coverImage == null ? "" : manga.coverImage.trim();
        MangaImageLoader.loadShimmerCover(holder.image, cover, manga.getSourceId());
        View.OnClickListener open = v -> {
            if (listener != null) listener.onMangaClick(manga);
        };
        holder.itemView.setOnClickListener(open);
        final MangaHistoryManager.Entry entry = continueEntry;
        holder.play.setOnClickListener(v -> {
            if (listener != null) listener.onContinueRead(entry);
        });
    }

    private void bindSection(SectionHolder holder, String title, String kind) {
        holder.title.setText(title);
        holder.action.setOnClickListener(v -> {
            if (listener != null) listener.onExplore(kind);
        });
    }

    private void bindLatest(LatestHolder holder, int index) {
        if (index >= latest.size()) {
            bindLatestSkeleton(holder, index);
            return;
        }
        MangaPost post = latest.get(index);
        ShimmerUtil.hide(holder.title);
        ShimmerUtil.hide(holder.genre);
        ShimmerUtil.hide(holder.chapter);
        ShimmerUtil.hide(holder.time);
        holder.statusShimmer.setBackground(null);
        holder.statusShimmer.setVisibility(View.GONE);
        holder.title.setText(post.title == null ? "" : post.title);
        MangaTitleStyle.apply(holder.title, context);
        MangaHomeV3GenreResolver.applyCached(post);
        boolean needsDetail = MangaHomeV3GenreResolver.needsResolveWithStatus(post);
        if (needsDetail) MangaHomeV3GenreResolver.request(post, this::notifyGenreChanged, true);
        boolean detailPending = needsDetail && MangaHomeV3GenreResolver.isPending(post);

        String genre = genreText(post);
        if (!genre.isEmpty()) {
            holder.genre.setText(genre);
            holder.genre.setVisibility(View.VISIBLE);
        } else if (detailPending) {
            ShimmerUtil.show(holder.genre, 0, 14, 6f, 0.5f);
        } else {
            holder.genre.setText("");
            holder.genre.setVisibility(View.GONE);
        }

        MangaHomeV3Labels.bindStatus(holder.statusGroup, holder.statusChip, post);
        if (MangaHomeV3Labels.statusText(post).isEmpty() && detailPending) {
            holder.statusShimmer.setBackground(new ShimmerDrawable(context, 12f));
            holder.statusShimmer.setVisibility(View.VISIBLE);
        }

        String chapter = MangaLatestChapterResolver.normalize(post.latestChapter);
        holder.chapter.setOnClickListener(null);
        holder.chapter.setClickable(false);
        holder.chapter.setFocusable(false);
        holder.chapter.setPadding(dp(10), dp(5), dp(10), dp(5));
        String date = MangaDateFormatter.format(post.latestChapterDate);
        boolean needChapterData = chapter.isEmpty() || date.isEmpty();
        if (needChapterData && listener != null && chapterRequested.add(post)) listener.onNeedChapter(post);
        boolean chapterPending = needChapterData && MangaLatestChapterResolver.isPending(post);
        if (chapter.isEmpty()) {
            MangaLabelUtils.bindChapter(holder.chapter, "", context, false);
            if (chapterPending) ShimmerUtil.show(holder.chapter, 88, 26, 10f, 1f);
        } else {
            MangaLabelUtils.bindChapter(holder.chapter, chapter, context, false);
            if (holder.chapter.getVisibility() == View.VISIBLE && listener != null) {
                holder.chapter.setClickable(true);
                holder.chapter.setFocusable(true);
                holder.chapter.setOnClickListener(v -> listener.onChapterClick(post));
            }
        }
        if (!date.isEmpty()) {
            holder.time.setText(date);
            holder.time.setVisibility(View.VISIBLE);
        } else if (chapterPending) {
            ShimmerUtil.show(holder.time, 0, 14, 6f, 0.55f, 1, true);
        } else {
            holder.time.setText("");
            holder.time.setVisibility(View.GONE);
        }
        String url = post.coverImage == null ? "" : post.coverImage.trim();
        MangaImageLoader.loadShimmerCover(holder.image, url, post.getSourceId());
        holder.timeline.setEdges(index == 0, index == latestCount() - 1);
        holder.card.setOnClickListener(v -> {
            if (listener != null) listener.onMangaClick(post);
        });
    }

    private void bindLatestSkeleton(LatestHolder holder, int index) {
        holder.card.setOnClickListener(null);
        holder.card.setClickable(false);
        holder.chapter.setOnClickListener(null);
        holder.chapter.setClickable(false);
        holder.statusGroup.setVisibility(View.GONE);
        holder.statusShimmer.setBackground(new ShimmerDrawable(context, 12f));
        holder.statusShimmer.setVisibility(View.VISIBLE);
        ShimmerUtil.showCover(holder.image);
        ShimmerUtil.show(holder.title, 0, 20, 6f, 0.7f);
        ShimmerUtil.show(holder.genre, 0, 14, 6f, 0.5f);
        holder.chapter.setPadding(dp(10), dp(5), dp(10), dp(5));
        ShimmerUtil.show(holder.chapter, 88, 26, 10f, 1f);
        ShimmerUtil.show(holder.time, 0, 14, 6f, 0.55f, 1, true);
        holder.timeline.setEdges(index == 0, index == latestCount() - 1);
    }

    private void bindRail(RailHolder holder) {
        bindPopularSorts(holder);
        if (holder.adapter == null) {
            LinearLayoutManager manager = new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false);
            holder.recycler.setLayoutManager(manager);
            holder.recycler.setItemAnimator(null);
            holder.recycler.setItemViewCacheSize(4);
            holder.adapter = new MangaHomeV3RankAdapter(context, railData, new MangaHomeV3RankAdapter.Listener() {
                @Override public void onClick(MangaPost post) {
                    if (listener != null) listener.onMangaClick(post);
                }

                @Override public void onNeedChapter(MangaPost post) {
                    if (listener != null) listener.onNeedChapter(post);
                }
            });
            holder.recycler.setAdapter(holder.adapter);
            holder.recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    if (dx <= 0) return;
                    int last = manager.findLastVisibleItemPosition();
                    if (last >= Math.max(0, railData.size() - 3) && listener != null) listener.onPopularNearEnd();
                }
            });
            holder.boundSort = popularSort;
            holder.adapter.setSkeletonCount(railSkeletonCount());
        } else {
            holder.adapter.setSkeletonCount(railSkeletonCount());
            holder.adapter.setData(railData);
            holder.adapter.notifyDataSetChanged();
            if (!popularSort.equals(holder.boundSort)) {
                holder.boundSort = popularSort;
                holder.recycler.scrollToPosition(0);
            }
        }
        holder.progress.setVisibility(View.GONE);
    }

    private int railSkeletonCount() {
        if (railData.isEmpty()) return (loadingInitial || loadingPopularMore) ? SKELETON_RAIL : 0;
        return loadingPopularMore ? SKELETON_RAIL_MORE : 0;
    }

    private void bindPopularSorts(RailHolder holder) {
        holder.sortChips.removeAllViews();
        if (popularSorts.size() <= 1) {
            holder.sortScroll.setVisibility(View.GONE);
            return;
        }
        holder.sortScroll.setVisibility(View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(context);
        for (String[] item : popularSorts) {
            String key = item[0];
            TextView chip = (TextView) inflater.inflate(R.layout.manga_home_v3_source_chip, holder.sortChips, false);
            chip.setText(item[1]);
            chip.setSelected(key.equals(popularSort));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(dp(8));
            chip.setLayoutParams(params);
            chip.setOnClickListener(v -> {
                if (!key.equals(popularSort) && listener != null) listener.onSelectPopularSort(key);
            });
            holder.sortChips.addView(chip);
        }
    }

    private String formatChapter(float value) {
        if (value < 0f) return "-";
        if (value == (int) value) return String.valueOf((int) value);
        String text = String.valueOf(value);
        while (text.endsWith("0")) text = text.substring(0, text.length() - 1);
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof LatestHolder) {
            LatestHolder latestHolder = (LatestHolder) holder;
            latestHolder.chapter.setOnClickListener(null);
            ShimmerUtil.hide(latestHolder.title);
            ShimmerUtil.hide(latestHolder.genre);
            ShimmerUtil.hide(latestHolder.chapter);
            ShimmerUtil.hide(latestHolder.time);
            latestHolder.image.animate().cancel();
            MangaImageLoader.clear(latestHolder.image);
        } else if (holder instanceof ContinueHolder) {
            MangaImageLoader.clear(((ContinueHolder) holder).image);
        }
        super.onViewRecycled(holder);
    }

    static class HeaderHolder extends RecyclerView.ViewHolder {
        final View search;
        final HorizontalScrollView scroll;
        final LinearLayout chips;
        final TextView status;
        boolean positioned;

        HeaderHolder(@NonNull View itemView) {
            super(itemView);
            search = itemView.findViewById(R.id.homeV3SearchButton);
            scroll = itemView.findViewById(R.id.homeV3SourceScroll);
            chips = itemView.findViewById(R.id.homeV3SourceChips);
            status = itemView.findViewById(R.id.homeV3StatusTextView);
        }
    }

    static class HeroHolder extends RecyclerView.ViewHolder {
        final RecyclerView recycler;
        final ProgressBar progress;
        final LinearLayout dots;
        MangaHomeV3HeroAdapter adapter;

        HeroHolder(@NonNull View itemView) {
            super(itemView);
            recycler = itemView.findViewById(R.id.homeV3HeroRecycler);
            progress = itemView.findViewById(R.id.homeV3HeroProgress);
            dots = itemView.findViewById(R.id.homeV3Dots);
        }
    }

    static class ContinueHolder extends RecyclerView.ViewHolder {
        final ShapeableImageView image;
        final TextView title;
        final TextView chapter;
        final ProgressBar progress;
        final ImageView play;

        ContinueHolder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.homeV3ContinueImage);
            title = itemView.findViewById(R.id.homeV3ContinueTitle);
            chapter = itemView.findViewById(R.id.homeV3ContinueChapter);
            progress = itemView.findViewById(R.id.homeV3ContinueProgress);
            play = itemView.findViewById(R.id.homeV3ContinuePlay);
        }
    }

    static class SectionHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView action;

        SectionHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.homeV3SectionTitle);
            action = itemView.findViewById(R.id.homeV3SectionAction);
        }
    }

    static class LatestHolder extends RecyclerView.ViewHolder {
        final ShapeableImageView image;
        final TextView title;
        final TextView genre;
        final TextView chapter;
        final TextView time;
        final View card;
        final HomeV3TimelineView timeline;
        final View statusGroup;
        final Chip statusChip;
        final View statusShimmer;

        LatestHolder(@NonNull View itemView) {
            super(itemView);
            statusShimmer = itemView.findViewById(R.id.homeV3LatestStatusShimmer);
            card = itemView.findViewById(R.id.homeV3LatestCard);
            timeline = itemView.findViewById(R.id.homeV3LatestTimeline);
            statusGroup = itemView.findViewById(R.id.homeV3LatestStatusGroup);
            statusChip = itemView.findViewById(R.id.homeV3LatestStatusChip);
            statusChip.setClickable(false);
            statusChip.setFocusable(false);
            image = itemView.findViewById(R.id.homeV3LatestImage);
            title = itemView.findViewById(R.id.homeV3LatestTitle);
            genre = itemView.findViewById(R.id.homeV3LatestGenre);
            chapter = itemView.findViewById(R.id.homeV3LatestChapter);
            time = itemView.findViewById(R.id.homeV3LatestTime);
        }
    }

    static class RailHolder extends RecyclerView.ViewHolder {
        final RecyclerView recycler;
        final ProgressBar progress;
        final HorizontalScrollView sortScroll;
        final LinearLayout sortChips;
        MangaHomeV3RankAdapter adapter;
        String boundSort = "";

        RailHolder(@NonNull View itemView) {
            super(itemView);
            sortScroll = itemView.findViewById(R.id.homeV3PopularSortScroll);
            sortChips = itemView.findViewById(R.id.homeV3PopularSortChips);
            recycler = itemView.findViewById(R.id.homeV3RankRecycler);
            progress = itemView.findViewById(R.id.homeV3RankProgress);
        }
    }
}
