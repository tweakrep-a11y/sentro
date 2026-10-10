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
import java.util.Locale;

/**
 * Adapter hasil Pencarian Anime Global. Tampilan kartu, skeleton shimmer, banner dan footer
 * dibuat identik dengan {@link MangaSearchV2Adapter} supaya UI/UX anime dan manga sinkron.
 */
public final class AnimeSearchV2Adapter extends ListAdapter<AnimeSearchV2Adapter.Row, RecyclerView.ViewHolder> {
    public interface Listener {
        void onAnimeClick(AnimePost post);
        void onEpisodeClick(AnimePost post);
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
        final AnimePost post;
        final String text;
        final String sourceId;
        final String signature;

        private Row(int kind, String key, AnimePost post, String text, String sourceId, String signature) {
            this.kind = kind;
            this.key = key;
            this.post = post;
            this.text = text;
            this.sourceId = sourceId;
            this.signature = signature;
        }

        public static Row result(AnimePost post) {
            String sig = nz(post.categoryName) + "|" + nz(post.imgUrl) + "|" + nz(post.genre) + "|" + nz(post.statusVideo)
                    + "|" + post.ongoing + "|" + nz(post.channelName) + "|" + nz(post.episodeCount) + "|" + post.totalEpisodes
                    + "|" + nz(post.rating) + "|" + post.year;
            return new Row(KIND_RESULT, keyOf(post), post, "", nz(post.sourceId), sig);
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

    static String keyOf(AnimePost post) {
        String value = post.slug == null || post.slug.trim().isEmpty() ? post.categoryId + ":" + nz(post.categoryName) : post.slug;
        return nz(post.sourceId) + "|" + value.trim();
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

    public AnimeSearchV2Adapter(Context context, Listener listener) {
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
                View.OnClickListener click = v -> { if (listener != null) listener.onFooterClick(row.sourceId, AnimeSettingsManager.labelForSourceId(row.sourceId)); };
                textHolder.text.setOnClickListener(click);
            }
        }
    }

    private void resetShimmers(ResultHolder holder) {
        ShimmerUtil.hide(holder.genre);
        ShimmerUtil.hide(holder.type);
        ShimmerUtil.hide(holder.status);
        ShimmerUtil.hide(holder.chapter);
        ShimmerUtil.hide(holder.time);
        ShimmerUtil.hide(holder.title);
        ShimmerUtil.hide(holder.source);
    }

    private void bindSkeleton(ResultHolder holder) {
        resetShimmers(holder);
        holder.itemView.setOnClickListener(null);
        holder.itemView.setClickable(false);
        holder.chapter.setOnClickListener(null);
        holder.chapter.setClickable(false);
        AnimeImageLoader.clear(holder.cover);
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

    private void bindResult(ResultHolder holder, AnimePost post) {
        resetShimmers(holder);
        holder.itemView.setClickable(true);
        String title = post.categoryName == null ? "" : post.categoryName.trim();
        holder.title.setText(title);
        MangaTitleStyle.apply(holder.title, context);

        String genre = genreText(post);
        if (!genre.isEmpty()) {
            holder.genre.setText(genre);
            holder.genre.setVisibility(View.VISIBLE);
        } else {
            holder.genre.setText("");
            holder.genre.setVisibility(View.GONE);
        }

        holder.source.setText(AnimeSettingsManager.labelForSourceId(post.sourceId));

        String rating = ratingText(post);
        if (!rating.isEmpty()) {
            holder.type.setText(rating);
            holder.type.setVisibility(View.VISIBLE);
        } else {
            holder.type.setText("");
            holder.type.setVisibility(View.GONE);
        }

        String status = statusText(post);
        if (!status.isEmpty()) {
            holder.status.setText(status);
            holder.status.setTextColor(MangaHomeV3Labels.statusColor(status));
            holder.status.setVisibility(View.VISIBLE);
        } else {
            holder.status.setVisibility(View.GONE);
        }

        boolean showEpisode = AnimeSettingsManager.shouldShowLatestEpisodeLabel(context);
        String episode = showEpisode ? AnimeEpisodeLabelUtils.latestLabel(post) : "";
        holder.chapter.setOnClickListener(null);
        holder.chapter.setClickable(false);
        if (episode.isEmpty()) {
            holder.chapterRow.setVisibility(View.GONE);
        } else {
            holder.chapterRow.setVisibility(View.VISIBLE);
            holder.chapter.setText(episode);
            holder.chapter.setClickable(true);
            holder.chapter.setOnClickListener(v -> { if (listener != null) listener.onEpisodeClick(post); });
            String year = post.year > 0 ? String.valueOf(post.year) : "";
            holder.time.setText(year);
            holder.time.setVisibility(year.isEmpty() ? View.GONE : View.VISIBLE);
        }

        // Shimmer saat memuat cover, teks "Gambar Rusak" bila gagal / URL kosong (sama seperti manga).
        AnimeImageLoader.loadShimmerCover(holder.cover, post.imgUrl == null ? "" : post.imgUrl.trim());

        holder.itemView.setOnClickListener(v -> { if (listener != null) listener.onAnimeClick(post); });
    }

    static String genreText(AnimePost post) {
        String value = post.genre == null ? "" : post.genre.trim();
        if (value.isEmpty()) return "";
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

    static String statusText(AnimePost post) {
        String value = post.statusVideo == null ? "" : post.statusVideo.trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value)) return post.ongoing ? "Ongoing" : "";
        return value;
    }

    private static String ratingText(AnimePost post) {
        String value = post.rating == null ? "" : post.rating.trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value) || "0".equals(value) || "0.0".equals(value)) return "";
        return String.format(Locale.US, "★ %s", value);
    }

    @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof ResultHolder) {
            ResultHolder resultHolder = (ResultHolder) holder;
            resetShimmers(resultHolder);
            resultHolder.cover.animate().cancel();
            AnimeImageLoader.clear(resultHolder.cover);
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
