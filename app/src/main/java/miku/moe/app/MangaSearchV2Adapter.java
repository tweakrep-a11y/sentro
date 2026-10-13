package miku.moe.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.imageview.ShapeableImageView;
import java.util.List;

public final class MangaSearchV2Adapter extends ListAdapter<MangaSearchV2Adapter.Row, RecyclerView.ViewHolder> {
    public interface Listener {
        void onMangaClick(MangaPost post);
        void onChapterClick(MangaPost post);
        void onBannerClick();
        void onFooterClick(String sourceId, String sourceLabel);
    }

    public static final int KIND_RESULT = 0;
    public static final int KIND_BANNER = 1;
    public static final int KIND_FOOTER = 2;
    public static final int KIND_SKELETON = 3;

    public static final class Row {
        final int kind;
        final String key;
        final MangaPost post;
        final String text;
        final String sourceId;
        final String signature;

        private Row(int kind, String key, MangaPost post, String text, String sourceId, String signature) {
            this.kind = kind;
            this.key = key;
            this.post = post;
            this.text = text;
            this.sourceId = sourceId;
            this.signature = signature;
        }

        public static Row result(MangaPost post) {
            String sig = nz(post.title) + "|" + nz(post.coverImage) + "|" + nz(post.genre) + "|" + nz(post.status)
                    + "|" + nz(post.typeLabel) + "|" + nz(post.latestChapter) + "|" + nz(post.latestChapterDate);
            return new Row(KIND_RESULT, keyOf(post), post, "", post.getSourceId(), sig);
        }

        public static Row skeleton(int index) {
            return new Row(KIND_SKELETON, "skeleton|" + index, null, "", "", "s");
        }

        public static Row banner(String text) {
            return new Row(KIND_BANNER, "banner", null, text, "", text);
        }

        public static Row footer(String sourceId, String text) {
            return new Row(KIND_FOOTER, "footer|" + sourceId, null, text, sourceId, text);
        }
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    static String keyOf(MangaPost post) {
        String value = post.slug == null || post.slug.trim().isEmpty() ? post.title : post.slug;
        return post.getSourceId() + "|" + (value == null ? "" : value.trim());
    }

    private static final DiffUtil.ItemCallback<Row> DIFF = new DiffUtil.ItemCallback<Row>() {
        @Override public boolean areItemsTheSame(@NonNull Row oldItem, @NonNull Row newItem) {
            return oldItem.kind == newItem.kind && oldItem.key.equals(newItem.key);
        }

        @Override public boolean areContentsTheSame(@NonNull Row oldItem, @NonNull Row newItem) {
            return oldItem.signature.equals(newItem.signature);
        }
    };

    private final Context context;
    private final Listener listener;
    private final java.util.Set<MangaPost> chapterRequested = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<MangaPost, Boolean>());

    public MangaSearchV2Adapter(Context context, Listener listener) {
        super(DIFF);
        this.context = context;
        this.listener = listener;
    }

    @Override public int getItemViewType(int position) {
        return getItem(position).kind;
    }

    @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(context);
        if (viewType == KIND_BANNER) return new TextHolder(inflater.inflate(R.layout.item_search_v2_banner, parent, false), R.id.searchBannerText);
        if (viewType == KIND_FOOTER) return new TextHolder(inflater.inflate(R.layout.item_search_v2_footer, parent, false), R.id.searchFooterText);
        return new ResultHolder(inflater.inflate(R.layout.item_search_v2_result, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = getItem(position);
        if (holder instanceof ResultHolder) {
            if (row.kind == KIND_SKELETON) bindSkeleton((ResultHolder) holder);
            else bindResult((ResultHolder) holder, row.post);
        } else if (holder instanceof TextHolder) {
            TextHolder textHolder = (TextHolder) holder;
            textHolder.text.setText(row.text);
            if (row.kind == KIND_BANNER) {
                textHolder.itemView.setOnClickListener(v -> { if (listener != null) listener.onBannerClick(); });
            } else {
                View.OnClickListener click = v -> { if (listener != null) listener.onFooterClick(row.sourceId, MangaSourceFactory.labelForSourceId(row.sourceId)); };
                textHolder.text.setOnClickListener(click);
            }
        }
    }

    private void releasePost(ResultHolder holder) {
        MangaPost previous = holder.boundPost;
        holder.boundPost = null;
        if (previous == null) return;
        // Item keluar layar: hentikan permintaan detail/chapter yang masih mengantre agar antrean tidak menumpuk.
        MangaHomeV3GenreResolver.cancelQueued(previous);
        MangaLatestChapterResolver.cancelQueued(previous);
        chapterRequested.remove(previous);
    }

    private void resetShimmers(ResultHolder holder) {
        ShimmerUtil.hide(holder.genre);
        ShimmerUtil.hide(holder.type);
        ShimmerUtil.hide(holder.status);
        ShimmerUtil.hide(holder.chapter);
        ShimmerUtil.hide(holder.time);
        ShimmerUtil.hide(holder.title);
    }

    private void bindSkeleton(ResultHolder holder) {
        releasePost(holder);
        resetShimmers(holder);
        holder.itemView.setOnClickListener(null);
        holder.itemView.setClickable(false);
        holder.chapter.setOnClickListener(null);
        holder.chapter.setClickable(false);
        ShimmerUtil.showCover(holder.cover);
        ShimmerUtil.show(holder.title, 0, 40, 6f, 0.85f, 2, false);
        ShimmerUtil.show(holder.genre, 0, 14, 6f, 0.55f);
        holder.source.setText("");
        ShimmerUtil.show(holder.source, 70, 24, 10f, 1f);
        holder.type.setText("");
        ShimmerUtil.show(holder.type, 56, 24, 10f, 1f);
        holder.status.setVisibility(View.GONE);
        holder.chapterRow.setVisibility(View.VISIBLE);
        ShimmerUtil.show(holder.chapter, 88, 24, 10f, 1f);
        ShimmerUtil.show(holder.time, 0, 14, 6f, 0.5f, 1, true);
    }

    private void bindResult(ResultHolder holder, MangaPost post) {
        if (holder.boundPost != post) releasePost(holder);
        holder.boundPost = post;
        ShimmerUtil.hide(holder.source);
        resetShimmers(holder);
        MangaHomeV3GenreResolver.applyCached(post);
        holder.title.setText(post.title == null ? "" : post.title);
        MangaTitleStyle.apply(holder.title, context);

        boolean loadType = MangaSettingsManager.shouldLoadTypeLabel(context);
        boolean needsDetail = MangaHomeV3GenreResolver.needsResolveWithStatus(post) || (loadType && MangaHomeV3GenreResolver.needsType(post));
        if (needsDetail) MangaHomeV3GenreResolver.request(post, this::notifyPost, true, loadType);
        boolean detailPending = needsDetail && MangaHomeV3GenreResolver.isPending(post);

        String genreRaw = post.genre == null ? "" : post.genre.trim();
        String genre = genreRaw.isEmpty() ? "" : MangaHomeV3Adapter.genreText(post);
        if (!genre.isEmpty()) {
            holder.genre.setText(genre);
            holder.genre.setVisibility(View.VISIBLE);
        } else if (detailPending) {
            ShimmerUtil.show(holder.genre, 0, 14, 6f, 0.55f);
        } else {
            holder.genre.setText("");
            holder.genre.setVisibility(View.GONE);
        }

        holder.source.setText(post.getSourceLabel());

        String type = loadType ? MangaHomeV3Labels.typeText(post) : "";
        if (!type.isEmpty()) {
            holder.type.setText(type);
            holder.type.setVisibility(View.VISIBLE);
        } else if (loadType && MangaHomeV3GenreResolver.needsType(post) && detailPending) {
            ShimmerUtil.show(holder.type, 56, 24, 10f, 1f);
        } else {
            holder.type.setText("");
            holder.type.setVisibility(View.GONE);
        }

        String status = MangaHomeV3Labels.statusText(post);
        if (!status.isEmpty()) {
            holder.status.setText(status);
            holder.status.setTextColor(MangaHomeV3Labels.statusColor(status));
            holder.status.setVisibility(View.VISIBLE);
        } else if (detailPending) {
            ShimmerUtil.show(holder.status, 0, 14, 6f, 0.5f);
        } else {
            holder.status.setVisibility(View.GONE);
        }

        boolean loadChapter = MangaSettingsManager.shouldLoadLatestChapterLabel(context);
        String chapter = loadChapter ? MangaLatestChapterResolver.normalize(post.latestChapter) : "";
        holder.chapter.setOnClickListener(null);
        holder.chapter.setClickable(false);
        if (chapter.isEmpty()) {
            holder.chapterRow.setVisibility(View.GONE);
            if (loadChapter) {
                if (chapterRequested.add(post)) {
                    MangaLatestChapterResolver.resolve(post, true, (resolved, changed) -> notifyPost(resolved));
                }
                if (MangaLatestChapterResolver.isPending(post)) {
                    holder.chapterRow.setVisibility(View.VISIBLE);
                    holder.chapter.setText("");
                    ShimmerUtil.show(holder.chapter, 88, 24, 10f, 1f);
                    ShimmerUtil.show(holder.time, 0, 14, 6f, 0.5f, 1, true);
                }
            }
        } else {
            holder.chapterRow.setVisibility(View.VISIBLE);
            holder.chapter.setText(chapter);
            holder.chapter.setClickable(true);
            holder.chapter.setOnClickListener(v -> { if (listener != null) listener.onChapterClick(post); });
            String date = MangaDateFormatter.format(post.latestChapterDate);
            holder.time.setText(date);
            holder.time.setVisibility(date.isEmpty() ? View.GONE : View.VISIBLE);
        }

        String url = post.coverImage == null ? "" : post.coverImage.trim();
        MangaImageLoader.loadShimmerCover(holder.cover, url, post.getSourceId());

        holder.itemView.setOnClickListener(v -> { if (listener != null) listener.onMangaClick(post); });
    }

    private void notifyPost(MangaPost post) {
        if (post == null) return;
        String key = keyOf(post);
        List<Row> list = getCurrentList();
        for (int i = 0; i < list.size(); i++) {
            Row row = list.get(i);
            if (row.kind == KIND_RESULT && key.equals(row.key)) notifyItemChanged(i);
        }
    }

    @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof ResultHolder) {
            ResultHolder resultHolder = (ResultHolder) holder;
            releasePost(resultHolder);
            resetShimmers(resultHolder);
            ShimmerUtil.hide(resultHolder.source);
            resultHolder.cover.animate().cancel();
            MangaImageLoader.clear(resultHolder.cover);
        }
        super.onViewRecycled(holder);
    }

    static final class ResultHolder extends RecyclerView.ViewHolder {
        final ShapeableImageView cover;
        final TextView title;
        final TextView genre;
        final TextView source;
        final TextView type;
        final TextView status;
        final View chapterRow;
        final TextView chapter;
        final TextView time;
        MangaPost boundPost;

        ResultHolder(@NonNull View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.searchResultCover);
            title = itemView.findViewById(R.id.searchResultTitle);
            genre = itemView.findViewById(R.id.searchResultGenre);
            source = itemView.findViewById(R.id.searchResultSource);
            type = itemView.findViewById(R.id.searchResultType);
            status = itemView.findViewById(R.id.searchResultStatus);
            chapterRow = itemView.findViewById(R.id.searchResultChapterRow);
            chapter = itemView.findViewById(R.id.searchResultChapter);
            time = itemView.findViewById(R.id.searchResultTime);
        }
    }

    static final class TextHolder extends RecyclerView.ViewHolder {
        final TextView text;

        TextHolder(@NonNull View itemView, int textId) {
            super(itemView);
            text = itemView.findViewById(textId);
        }
    }
}
