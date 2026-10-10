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

public class MangaHomeV3HeroAdapter extends RecyclerView.Adapter<MangaHomeV3HeroAdapter.Holder> {
    public interface Listener {
        void onClick(MangaPost post);
        void onNeedChapter(MangaPost post);
        void onChapterClick(MangaPost post);
    }

    public static final int MAX_ITEMS = 5;
    private final Context context;
    private final ArrayList<MangaPost> data;
    private final Listener listener;
    private final java.util.Set<MangaPost> chapterRequested = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<MangaPost, Boolean>());
    private boolean skeleton;

    public MangaHomeV3HeroAdapter(Context context, ArrayList<MangaPost> data, Listener listener) {
        this.context = context;
        this.data = data;
        this.listener = listener;
        setHasStableIds(true);
    }

    /** Saat true dan data kosong, satu kartu hero shimmer ditampilkan. */
    public void setSkeleton(boolean skeleton) {
        this.skeleton = skeleton;
    }

    private boolean isSkeletonPosition(int position) {
        return position >= Math.min(data.size(), MAX_ITEMS);
    }

    @Override public long getItemId(int position) {
        if (isSkeletonPosition(position)) return Long.MIN_VALUE + 2000L + position;
        MangaPost post = data.get(position);
        String key = post.getSourceId() + ":" + (post.slug == null || post.slug.isEmpty() ? post.title : post.slug);
        return key.hashCode();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.manga_home_v3_hero_item, parent, false);
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int width = Math.max(dp(240), screenWidth - dp(42));
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT);
        params.setMargins(dp(5), 0, dp(5), 0);
        view.setLayoutParams(params);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        if (isSkeletonPosition(position)) {
            bindSkeleton(holder);
            return;
        }
        MangaPost post = data.get(position);
        ShimmerUtil.hide(holder.title);
        holder.read.setVisibility(View.VISIBLE);
        holder.title.setText(post.title == null ? "" : post.title);
        MangaTitleStyle.apply(holder.title, context);
        bindGenre(holder, post);
        bindChapter(holder, post);
        bindCover(holder, post);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(post);
        });
        holder.read.setOnClickListener(v -> {
            if (listener != null) listener.onChapterClick(post);
        });
    }

    private void bindSkeleton(Holder holder) {
        holder.itemView.setOnClickListener(null);
        holder.itemView.setClickable(false);
        holder.read.setOnClickListener(null);
        holder.read.setVisibility(View.GONE);
        holder.chapter.setOnClickListener(null);
        holder.chapter.setClickable(false);
        holder.progress.setVisibility(View.GONE);
        ShimmerUtil.showCover(holder.image);
        ShimmerUtil.show(holder.title, 0, 26, 8f, 0.7f);
        ShimmerUtil.show(holder.genre, 0, 14, 6f, 0.45f);
        ShimmerUtil.show(holder.chapter, 96, 32, 12f, 1f);
    }

    private void bindChapter(Holder holder, MangaPost post) {
        ShimmerUtil.hide(holder.chapter);
        String chapter = MangaLatestChapterResolver.normalize(post.latestChapter);
        holder.chapter.setOnClickListener(null);
        holder.chapter.setClickable(false);
        holder.chapter.setFocusable(false);
        if (chapter.isEmpty()) {
            MangaLabelUtils.bindChapter(holder.chapter, "", context, false);
            if (listener != null && chapterRequested.add(post)) listener.onNeedChapter(post);
            if (MangaLatestChapterResolver.isPending(post)) ShimmerUtil.show(holder.chapter, 96, 32, 12f, 1f);
            return;
        }
        MangaLabelUtils.bindChapter(holder.chapter, chapter, context, false);
        if (holder.chapter.getVisibility() == View.VISIBLE && listener != null) {
            holder.chapter.setClickable(true);
            holder.chapter.setFocusable(true);
            holder.chapter.setOnClickListener(v -> listener.onChapterClick(post));
        }
    }

    private void bindGenre(Holder holder, MangaPost post) {
        ShimmerUtil.hide(holder.genre);
        MangaHomeV3GenreResolver.applyCached(post);
        boolean needs = MangaHomeV3GenreResolver.needsResolve(post);
        if (needs) {
            MangaHomeV3GenreResolver.request(post, resolved -> {
                int index = data.indexOf(resolved);
                if (index >= 0) notifyItemChanged(index);
            });
        }
        String text = MangaHomeV3Adapter.genreText(post);
        if (!text.isEmpty()) {
            holder.genre.setText(text);
            holder.genre.setVisibility(View.VISIBLE);
        } else if (needs && MangaHomeV3GenreResolver.isPending(post)) {
            ShimmerUtil.show(holder.genre, 0, 14, 6f, 0.45f);
        } else {
            holder.genre.setText("");
            holder.genre.setVisibility(View.INVISIBLE);
        }
    }

    private void bindCover(Holder holder, MangaPost post) {
        String url = post.coverImage == null ? "" : post.coverImage.trim();
        holder.progress.setVisibility(View.GONE);
        MangaImageLoader.loadShimmerCover(holder.image, url, post.getSourceId());
    }

    @Override public int getItemCount() {
        int real = Math.min(data.size(), MAX_ITEMS);
        return real == 0 && skeleton ? 1 : real;
    }

    @Override public void onViewRecycled(@NonNull Holder holder) {
        holder.boundCoverKey = "";
        ShimmerUtil.hide(holder.title);
        ShimmerUtil.hide(holder.genre);
        ShimmerUtil.hide(holder.chapter);
        holder.chapter.setOnClickListener(null);
        holder.chapter.setClickable(false);
        holder.chapter.setFocusable(false);
        holder.progress.setVisibility(View.GONE);
        holder.image.animate().cancel();
        MangaImageLoader.clear(holder.image);
        super.onViewRecycled(holder);
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    static class Holder extends RecyclerView.ViewHolder {
        final ShapeableImageView image;
        final ProgressBar progress;
        final TextView title;
        final TextView genre;
        final TextView chapter;
        final TextView read;
        String boundCoverKey = "";

        Holder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.homeV3HeroImage);
            progress = itemView.findViewById(R.id.homeV3HeroImageProgress);
            title = itemView.findViewById(R.id.homeV3HeroTitle);
            genre = itemView.findViewById(R.id.homeV3HeroGenre);
            chapter = itemView.findViewById(R.id.homeV3HeroChapter);
            read = itemView.findViewById(R.id.homeV3HeroRead);
        }
    }
}
