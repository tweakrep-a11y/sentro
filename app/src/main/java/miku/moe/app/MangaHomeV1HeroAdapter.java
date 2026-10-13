package miku.moe.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.imageview.ShapeableImageView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public class MangaHomeV1HeroAdapter extends RecyclerView.Adapter<MangaHomeV1HeroAdapter.Holder> {
    public interface Listener {
        void onClick(MangaPost post);
        void onNeedChapter(MangaPost post);
        void onChapterClick(MangaPost post);
    }

    private final Context context;
    private final ArrayList<MangaPost> data;
    private final Listener listener;
    private static final HashMap<String, String> GENRE_CACHE = new HashMap<>();
    private static final HashSet<String> GENRE_PENDING = new HashSet<>();
    private static final HashMap<String, Long> GENRE_FAILED_AT = new HashMap<>();
    private static final long GENRE_RETRY_MS = 120000L;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Set<MangaPost> chapterRequested = Collections.newSetFromMap(new WeakHashMap<MangaPost, Boolean>());
    private int skeletonCount;

    public MangaHomeV1HeroAdapter(Context context, ArrayList<MangaPost> data, Listener listener) {
        this.context = context;
        this.data = data;
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setSkeletonCount(int count) { skeletonCount = Math.max(0, count); }

    @Override public long getItemId(int position) {
        if (position >= Math.min(data.size(), 8)) return Long.MIN_VALUE + position;
        MangaPost post = data.get(position);
        String key = post.getSourceId() + ":" + (post.slug == null || post.slug.isEmpty() ? post.title : post.slug);
        return key.hashCode();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.manga_home_v1_hero_item, parent, false);
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(dp(420), Math.max(dp(280), screenWidth - dp(48)));
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(width, dp(218));
        params.setMargins(dp(5), 0, dp(5), 0);
        view.setLayoutParams(params);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        hideShimmer(holder);
        if (position >= Math.min(data.size(), 8)) {
            bindSkeleton(holder);
            return;
        }
        MangaPost post = data.get(position);
        holder.boundPost = post;
        holder.title.setText(post.title == null ? "" : post.title);
        bindGenre(holder, post);
        bindChapter(holder, post);
        bindCover(holder, post);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(post);
        });
        MangaItemWaveAnimator.reset(holder.itemView);
    }

    private void bindChapter(Holder holder, MangaPost post) {
        String chapter = actualChapter(post.latestChapter);
        ShimmerUtil.hide(holder.chapter);
        holder.chapter.setOnClickListener(null);
        holder.chapter.setClickable(false);
        holder.chapter.setFocusable(false);
        if (chapter.isEmpty()) {
            MangaLabelUtils.bindChapter(holder.chapter, "", context, true);
            if (MangaSettingsManager.shouldLoadLatestChapterLabel(context) && listener != null && chapterRequested.add(post)) listener.onNeedChapter(post);
            if (MangaLatestChapterResolver.isPending(post)) ShimmerUtil.show(holder.chapter, 88, 26, 10f, 1f);
            return;
        }
        MangaLabelUtils.bindChapter(holder.chapter, chapter, context, true);
        if (holder.chapter.getVisibility() == View.VISIBLE && listener != null) {
            holder.chapter.setClickable(true);
            holder.chapter.setFocusable(true);
            holder.chapter.setOnClickListener(v -> listener.onChapterClick(post));
        }
    }

    private void bindGenre(Holder holder, MangaPost post) {
        ShimmerUtil.hide(holder.genre);
        String key = genreKey(post);
        if (isBlank(post.genre) && !key.isEmpty()) {
            String cached = GENRE_CACHE.get(key);
            if (!isBlank(cached)) post.genre = cached;
        }
        String text = formatGenre(post.genre);
        if (text.isEmpty()) {
            // Genre belum ada dari list: tampilkan tipe (Manhwa/Manga/...) kalau ada, lalu ambil genre dari detail.
            text = isBlank(post.typeLabel) ? "" : post.typeLabel.trim();
            requestGenre(post, key);
        }
        holder.genre.setText(text);
        if (text.isEmpty() && isGenrePending(key)) ShimmerUtil.show(holder.genre, 0, 14, 6f, 0.55f);
        else holder.genre.setVisibility(text.isEmpty() ? View.INVISIBLE : View.VISIBLE);
    }

    private void requestGenre(MangaPost post, String key) {
        if (key.isEmpty()) return;
        long now = System.currentTimeMillis();
        synchronized (GENRE_PENDING) {
            Long failedAt = GENRE_FAILED_AT.get(key);
            if (failedAt != null && now - failedAt < GENRE_RETRY_MS) return;
            if (!GENRE_PENDING.add(key)) return;
        }
        String sourceId = post.getSourceId();
        String slug = post.slug.trim();
        MangaSourceFactory.createBySourceId(sourceId).detail(slug, new KomikcastClient.Result<MangaPost>() {
            @Override public void onSuccess(MangaPost detail, boolean hasNext) {
                String genre = detail == null || detail.genre == null ? "" : detail.genre.trim();
                synchronized (GENRE_PENDING) {
                    GENRE_PENDING.remove(key);
                    if (genre.isEmpty()) GENRE_FAILED_AT.put(key, System.currentTimeMillis());
                    else GENRE_FAILED_AT.remove(key);
                }
                mainHandler.post(() -> {
                    int index = data.indexOf(post);
                    if (!genre.isEmpty()) {
                        GENRE_CACHE.put(key, genre);
                        post.genre = genre;
                    }
                    if (index >= 0) notifyItemChanged(index);
                });
            }

            @Override public void onError(String message) {
                synchronized (GENRE_PENDING) {
                    GENRE_PENDING.remove(key);
                    GENRE_FAILED_AT.put(key, System.currentTimeMillis());
                }
                mainHandler.post(() -> {
                    int index = data.indexOf(post);
                    if (index >= 0) notifyItemChanged(index);
                });
            }
        });
    }

    private boolean isGenrePending(String key) {
        synchronized (GENRE_PENDING) { return GENRE_PENDING.contains(key); }
    }

    private static String genreKey(MangaPost post) {
        if (post == null || post.slug == null || post.slug.trim().isEmpty()) return "";
        String sourceId = post.getSourceId();
        if (sourceId == null || sourceId.trim().isEmpty()) return "";
        return sourceId.trim() + "|" + post.slug.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String formatGenre(String value) {
        if (isBlank(value)) return "";
        String[] parts = value.split(",");
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (String part : parts) {
            String clean = part.trim();
            if (clean.isEmpty()) continue;
            if (out.length() > 0) out.append(" · ");
            out.append(clean);
            count++;
            if (count >= 3) break;
        }
        return out.toString();
    }

    private String actualChapter(String value) {
        return MangaLatestChapterResolver.normalize(value);
    }

    private void bindCover(Holder holder, MangaPost post) {
        String url = post.coverImage == null ? "" : post.coverImage.trim();
        String key = post.getSourceId() + "|" + url;
        holder.boundCoverKey = key;
        holder.progress.setVisibility(View.GONE);
        MangaImageLoader.loadShimmerCover(holder.image, url, post.getSourceId());
    }

    @Override public int getItemCount() { return Math.min(data.size(), 8) + skeletonCount; }

    @Override public void onViewRecycled(@NonNull Holder holder) {
        MangaPost post = holder.boundPost;
        holder.boundPost = null;
        if (post != null) {
            MangaLatestChapterResolver.cancelQueued(post);
            chapterRequested.remove(post);
        }
        holder.boundCoverKey = "";
        holder.chapter.setOnClickListener(null);
        holder.chapter.setClickable(false);
        holder.chapter.setFocusable(false);
        holder.progress.setVisibility(View.GONE);
        hideShimmer(holder);
        holder.image.animate().cancel();
        MangaImageLoader.clear(holder.image);
        MangaItemWaveAnimator.reset(holder.itemView);
        super.onViewRecycled(holder);
    }

    private void bindSkeleton(Holder holder) {
        holder.itemView.setOnClickListener(null);
        holder.itemView.setClickable(false);
        holder.chapter.setOnClickListener(null);
        holder.chapter.setClickable(false);
        holder.progress.setVisibility(View.GONE);
        MangaImageLoader.clear(holder.image);
        ShimmerUtil.showCover(holder.image);
        ShimmerUtil.show(holder.title, 0, 20, 6f, 0.72f);
        ShimmerUtil.show(holder.genre, 0, 14, 6f, 0.5f);
        if (MangaSettingsManager.shouldLoadLatestChapterLabel(context)) ShimmerUtil.show(holder.chapter, 88, 26, 10f, 1f);
        else holder.chapter.setVisibility(View.GONE);
    }

    private void hideShimmer(Holder holder) {
        ShimmerUtil.hide(holder.title);
        ShimmerUtil.hide(holder.genre);
        ShimmerUtil.hide(holder.chapter);
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    static class Holder extends RecyclerView.ViewHolder {
        final ShapeableImageView image;
        final ProgressBar progress;
        final TextView title;
        final TextView genre;
        final TextView chapter;
        String boundCoverKey = "";
        MangaPost boundPost;

        Holder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.heroImageView);
            progress = itemView.findViewById(R.id.heroImageProgress);
            title = itemView.findViewById(R.id.heroTitleTextView);
            genre = itemView.findViewById(R.id.heroGenreTextView);
            chapter = itemView.findViewById(R.id.heroChapterTextView);
        }
    }
}
