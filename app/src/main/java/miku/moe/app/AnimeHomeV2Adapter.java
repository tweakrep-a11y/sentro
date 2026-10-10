package miku.moe.app;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import java.util.ArrayList;

public class AnimeHomeV2Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public interface Listener {
        void onAnimeClick(AnimePost post);
        void onExplore(String kind);
        void onBrowseSource();
        void onPopularNearEnd();
        void onSelectSource(String sourceId);
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
    private final ArrayList<AnimePost> popular;
    private final ArrayList<AnimePost> latest;
    private final ArrayList<AnimePost> heroData = new ArrayList<>();
    private final Listener listener;
    private HistoryItem continueEntry;
    private boolean loadingInitial;
    private boolean loadingPopularMore;
    private String errorMessage = "";
    private String errorDetails = "";

    public AnimeHomeV2Adapter(Context context, String sourceId, ArrayList<AnimePost> popular, ArrayList<AnimePost> latest, Listener listener) {
        this.context = context;
        this.sourceId = sourceId == null ? "" : sourceId;
        this.popular = popular;
        this.latest = latest;
        this.listener = listener;
        setHasStableIds(true);
    }

    public void updateState(HistoryItem continueEntry, boolean loadingInitial, boolean loadingPopularMore, boolean loadingMore, String errorMessage, String errorDetails) {
        this.continueEntry = continueEntry;
        this.loadingInitial = loadingInitial;
        this.loadingPopularMore = loadingPopularMore;
        this.errorMessage = errorMessage == null ? "" : errorMessage.trim();
        this.errorDetails = errorDetails == null ? "" : errorDetails.trim();
        rebuildHero();
        notifyDataSetChanged();
    }

    private void rebuildHero() {
        heroData.clear();
        ArrayList<AnimePost> source = popular.isEmpty() ? latest : popular;
        for (int i = 0; i < source.size() && heroData.size() < AnimeHomeV2HeroAdapter.MAX_ITEMS; i++) heroData.add(source.get(i));
    }

    public int getSpanSize(int position) {
        return 3;
    }

    private void notifyDetailChanged(AnimePost post) {
        String key = AnimeHomeV2Labels.itemKey(post);
        int start = latestStartPosition();
        int count = Math.min(latestCount(), latest.size());
        for (int i = 0; i < count; i++) {
            if (key.equals(AnimeHomeV2Labels.itemKey(latest.get(i)))) notifyItemChanged(start + i);
        }
    }

    private boolean hasContinue() {
        return continueEntry != null;
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
            return 0x100000000L + AnimeHomeV2Labels.itemKey(latest.get(latestIndex)).hashCode();
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
        if (viewType == TYPE_CONTINUE) return new ContinueHolder(inflater.inflate(R.layout.anime_home_v2_continue, parent, false));
        if (viewType == TYPE_LATEST_ITEM) return new LatestHolder(inflater.inflate(R.layout.manga_home_v3_latest_item, parent, false));
        if (viewType == TYPE_POPULAR_RAIL) return new RailHolder(inflater.inflate(R.layout.manga_home_v3_rail, parent, false));
        return new SectionHolder(inflater.inflate(R.layout.manga_home_v3_section_header, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        int type = holder.getItemViewType();
        if (type == TYPE_HEADER) bindHeader((HeaderHolder) holder);
        else if (type == TYPE_HERO) bindHero((HeroHolder) holder);
        else if (type == TYPE_CONTINUE) bindContinue((ContinueHolder) holder);
        else if (type == TYPE_LATEST_HEADER) bindSection((SectionHolder) holder, "Rilis terbaru", "latest");
        else if (type == TYPE_LATEST_ITEM) bindLatest((LatestHolder) holder, position - latestStartPosition());
        else if (type == TYPE_POPULAR_HEADER) bindSection((SectionHolder) holder, "Populer minggu ini", "popular");
        else bindRail((RailHolder) holder);
    }

    private void bindHeader(HeaderHolder holder) {
        holder.search.setOnClickListener(v -> {
            if (listener != null) listener.onBrowseSource();
        });
        holder.chips.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(context);
        View selectedView = null;
        for (String id : AnimeSettingsManager.allSourceIds()) {
            TextView chip = (TextView) inflater.inflate(R.layout.manga_home_v3_source_chip, holder.chips, false);
            chip.setText(AnimeSettingsManager.labelForSourceId(id));
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
            holder.status.setOnClickListener(null);
            holder.status.setVisibility(View.GONE);
        } else {
            holder.status.setText(errorMessage);
            holder.status.setVisibility(View.VISIBLE);
            holder.status.setOnClickListener(v -> showErrorDetails());
            holder.status.setContentDescription("Tekan untuk melihat log lengkap kesalahan pemuatan anime");
        }
    }

    private void showErrorDetails() {
        String details = errorDetails.isEmpty() ? errorMessage : errorDetails;
        new MaterialAlertDialogBuilder(context)
                .setTitle("Log lengkap Anime X Nonton")
                .setMessage(details)
                .setPositiveButton("Tutup", null)
                .setNeutralButton("Salin log", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Log Anime X Nonton", details));
                        Toast.makeText(context, "Log lengkap disalin", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void bindHero(HeroHolder holder) {
        if (holder.adapter == null) {
            LinearLayoutManager manager = new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false);
            holder.recycler.setLayoutManager(manager);
            holder.recycler.setItemAnimator(null);
            holder.recycler.setItemViewCacheSize(3);
            holder.adapter = new AnimeHomeV2HeroAdapter(context, heroData, post -> {
                if (listener != null) listener.onAnimeClick(post);
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
        int count = Math.min(heroData.size(), AnimeHomeV2HeroAdapter.MAX_ITEMS);
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
        if (continueEntry == null) return;
        HistoryItem entry = continueEntry;
        String animeTitle = clean(entry.categoryName);
        if (animeTitle.isEmpty()) animeTitle = clean(entry.title);
        holder.title.setText(animeTitle);
        MangaTitleStyle.apply(holder.title, context);
        String episode = AnimeEpisodeLabelUtils.historyLabel(entry.title);
        if (episode.isEmpty()) episode = "Lanjutkan episode terakhir";
        if (entry.duration > 0 && entry.position > 0) {
            int percent = (int) Math.min(100L, Math.max(1L, entry.position * 100L / entry.duration));
            holder.chapter.setText(episode + " \u00B7 " + percent + "% ditonton");
            holder.progress.setProgress(percent);
            holder.progress.setVisibility(View.VISIBLE);
        } else {
            holder.chapter.setText(episode);
            holder.progress.setVisibility(View.GONE);
        }
        AnimeImageLoader.loadShimmerCover(holder.image, clean(entry.imageUrl));
        View.OnClickListener open = v -> {
            if (listener != null) listener.onAnimeClick(toAnimePost(entry));
        };
        holder.itemView.setOnClickListener(open);
        holder.play.setOnClickListener(open);
    }

    private AnimePost toAnimePost(HistoryItem item) {
        String title = clean(item.categoryName);
        if (title.isEmpty()) title = clean(item.title);
        AnimePost post = new AnimePost(clean(item.imageUrl), title, item.categoryId, item.channelId);
        post.sourceId = AnimeSettingsManager.isValidSource(item.sourceId) ? item.sourceId : AnimeSettingsManager.SOURCE_DEFAULT;
        post.slug = clean(item.slug);
        String episode = AnimeEpisodeLabelUtils.normalize(item.title);
        post.channelName = episode;
        post.episodeCount = episode;
        return post;
    }

    private void bindSection(SectionHolder holder, String title, String kind) {
        holder.title.setText(title);
        holder.action.setOnClickListener(v -> {
            if (listener != null) listener.onExplore(kind);
        });
    }

    private void bindLatest(LatestHolder holder, int index) {
        if (index < 0 || index >= latest.size()) {
            bindLatestSkeleton(holder, Math.max(0, index));
            return;
        }
        AnimePost post = latest.get(index);
        ShimmerUtil.hide(holder.title);
        ShimmerUtil.hide(holder.genre);
        ShimmerUtil.hide(holder.chapter);
        ShimmerUtil.hide(holder.time);
        holder.statusShimmer.setBackground(null);
        holder.statusShimmer.setVisibility(View.GONE);
        holder.title.setText(clean(post.categoryName));
        MangaTitleStyle.apply(holder.title, context);
        AnimeHomeV2Resolver.applyCached(post);
        boolean needsDetail = AnimeHomeV2Resolver.needsResolve(post);
        if (needsDetail) AnimeHomeV2Resolver.request(post, this::notifyDetailChanged);
        boolean detailPending = needsDetail && AnimeHomeV2Resolver.isPending(post);

        String genre = AnimeHomeV2Labels.genreText(post);
        if (!genre.isEmpty()) {
            holder.genre.setText(genre);
            holder.genre.setVisibility(View.VISIBLE);
        } else if (detailPending) {
            ShimmerUtil.show(holder.genre, 0, 14, 6f, 0.5f);
        } else {
            holder.genre.setText("");
            holder.genre.setVisibility(View.GONE);
        }

        AnimeHomeV2Labels.bindStatus(holder.statusGroup, holder.statusChip, post);
        if (AnimeHomeV2Labels.statusText(post).isEmpty() && detailPending) {
            holder.statusShimmer.setBackground(new ShimmerDrawable(context, 12f));
            holder.statusShimmer.setVisibility(View.VISIBLE);
        }

        holder.chapter.setOnClickListener(null);
        holder.chapter.setClickable(false);
        holder.chapter.setFocusable(false);
        holder.chapter.setPadding(dp(10), dp(5), dp(10), dp(5));
        MangaLabelUtils.bindChapter(holder.chapter, AnimeHomeV2Labels.episodeText(context, post), context, false);
        if (holder.chapter.getVisibility() == View.VISIBLE && listener != null) {
            holder.chapter.setClickable(true);
            holder.chapter.setFocusable(true);
            holder.chapter.setOnClickListener(v -> listener.onAnimeClick(post));
        }
        holder.time.setText("");
        holder.time.setVisibility(View.GONE);
        AnimeImageLoader.loadShimmerCover(holder.image, post.imgUrl);
        holder.timeline.setEdges(index == 0, index == latestCount() - 1);
        holder.card.setOnClickListener(v -> {
            if (listener != null) listener.onAnimeClick(post);
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
        AnimeImageLoader.clear(holder.image);
        ShimmerUtil.showCover(holder.image);
        ShimmerUtil.show(holder.title, 0, 20, 6f, 0.7f);
        ShimmerUtil.show(holder.genre, 0, 14, 6f, 0.5f);
        holder.chapter.setPadding(dp(10), dp(5), dp(10), dp(5));
        ShimmerUtil.show(holder.chapter, 88, 26, 10f, 1f);
        ShimmerUtil.show(holder.time, 0, 14, 6f, 0.55f, 1, true);
        holder.timeline.setEdges(index == 0, index == latestCount() - 1);
    }

    private void bindRail(RailHolder holder) {
        holder.sortChips.removeAllViews();
        holder.sortScroll.setVisibility(View.GONE);
        if (holder.adapter == null) {
            LinearLayoutManager manager = new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false);
            holder.recycler.setLayoutManager(manager);
            holder.recycler.setItemAnimator(null);
            holder.recycler.setItemViewCacheSize(4);
            holder.adapter = new AnimeHomeV2RankAdapter(context, popular, post -> {
                if (listener != null) listener.onAnimeClick(post);
            });
            holder.recycler.setAdapter(holder.adapter);
            holder.recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    if (dx <= 0) return;
                    int last = manager.findLastVisibleItemPosition();
                    if (last >= Math.max(0, popular.size() - 3) && listener != null) listener.onPopularNearEnd();
                }
            });
            holder.adapter.setSkeletonCount(railSkeletonCount());
        } else {
            holder.adapter.setSkeletonCount(railSkeletonCount());
            holder.adapter.setData(popular);
            holder.adapter.notifyDataSetChanged();
        }
        holder.progress.setVisibility(View.GONE);
    }

    private int railSkeletonCount() {
        if (popular.isEmpty()) return (loadingInitial || loadingPopularMore) ? SKELETON_RAIL : 0;
        return loadingPopularMore ? SKELETON_RAIL_MORE : 0;
    }

    private String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof LatestHolder) {
            LatestHolder latestHolder = (LatestHolder) holder;
            latestHolder.chapter.setOnClickListener(null);
            ShimmerUtil.hide(latestHolder.title);
            ShimmerUtil.hide(latestHolder.genre);
            ShimmerUtil.hide(latestHolder.chapter);
            ShimmerUtil.hide(latestHolder.time);
            latestHolder.image.animate().cancel();
            AnimeImageLoader.clear(latestHolder.image);
        } else if (holder instanceof ContinueHolder) {
            AnimeImageLoader.clear(((ContinueHolder) holder).image);
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
        AnimeHomeV2HeroAdapter adapter;

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
        AnimeHomeV2RankAdapter adapter;

        RailHolder(@NonNull View itemView) {
            super(itemView);
            sortScroll = itemView.findViewById(R.id.homeV3PopularSortScroll);
            sortChips = itemView.findViewById(R.id.homeV3PopularSortChips);
            recycler = itemView.findViewById(R.id.homeV3RankRecycler);
            progress = itemView.findViewById(R.id.homeV3RankProgress);
        }
    }
}
