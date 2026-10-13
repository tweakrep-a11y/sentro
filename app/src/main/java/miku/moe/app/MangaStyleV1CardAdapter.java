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
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public class MangaStyleV1CardAdapter extends RecyclerView.Adapter<MangaStyleV1CardAdapter.Holder> {
    public interface Listener { void onClick(MangaPost post); }
    public interface ChapterListener { void onNeedChapter(MangaPost post); }
    public interface ChapterClickListener { void onChapterClick(MangaPost post); }

    public static final int MODE_GRID = 0;
    public static final int MODE_HORIZONTAL = 1;
    public static final int MODE_RESULT = 2;
    private final Context context;
    private final ArrayList<MangaPost> data;
    private final int mode;
    private final Listener listener;
    private final ChapterListener chapterListener;
    private final ChapterClickListener chapterClickListener;
    private final Set<MangaPost> chapterRequested = Collections.newSetFromMap(new WeakHashMap<MangaPost, Boolean>());
    private int skeletonCount;

    public MangaStyleV1CardAdapter(Context context, ArrayList<MangaPost> data, int mode, Listener listener) {
        this(context, data, mode, listener, null, null);
    }

    public MangaStyleV1CardAdapter(Context context, ArrayList<MangaPost> data, int mode, Listener listener, ChapterListener chapterListener) {
        this(context, data, mode, listener, chapterListener, null);
    }

    public MangaStyleV1CardAdapter(Context context, ArrayList<MangaPost> data, int mode, Listener listener, ChapterListener chapterListener, ChapterClickListener chapterClickListener) {
        this.context = context;
        this.data = data;
        this.mode = mode;
        this.listener = listener;
        this.chapterListener = chapterListener;
        this.chapterClickListener = chapterClickListener;
        setHasStableIds(true);
    }

    public void setSkeletonCount(int count) { skeletonCount = Math.max(0, count); }

    @Override public long getItemId(int position) {
        if (position >= data.size()) return Long.MIN_VALUE + position;
        MangaPost post = data.get(position);
        String key = post.getSourceId() + ":" + (post.slug == null || post.slug.isEmpty() ? post.title : post.slug);
        return key.hashCode();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.manga_style_v1_card_item, parent, false);
        int width = ViewGroup.LayoutParams.MATCH_PARENT;
        if (mode == MODE_HORIZONTAL) {
            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            width = Math.max(dp(104), screenWidth / 3 - dp(10));
        }
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (mode == MODE_HORIZONTAL) params.setMargins(dp(5), 0, dp(5), 0);
        else if (mode == MODE_RESULT) params.setMargins(dp(5), 0, dp(5), dp(8));
        else params.setMargins(dp(5), 0, dp(5), dp(8));
        view.setLayoutParams(params);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        hideSkeleton(holder);
        if (position >= data.size()) {
            bindSkeleton(holder);
            return;
        }
        MangaPost post = data.get(position);
        holder.boundPost = post;
        holder.title.setText(post.title == null ? "" : post.title);
        holder.title.getPaint().setFakeBoldText(false);
        bindChapter(holder, post);
        bindCover(holder, post);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(post);
        });
        MangaItemWaveAnimator.reset(holder.itemView);
    }

    private void bindChapter(Holder holder, MangaPost post) {
        String chapter = MangaLatestChapterResolver.normalize(post.latestChapter);
        ShimmerUtil.hide(holder.chapter);
        holder.chapter.setOnClickListener(null);
        holder.chapter.setClickable(false);
        holder.chapter.setFocusable(false);
        if (chapter.isEmpty()) {
            MangaLabelUtils.bindChapter(holder.chapter, "", context, true);
            if (MangaSettingsManager.shouldLoadLatestChapterLabel(context) && chapterListener != null && chapterRequested.add(post)) chapterListener.onNeedChapter(post);
            if (MangaLatestChapterResolver.isPending(post)) ShimmerUtil.show(holder.chapter, 64, 18, 7f, 1f);
            return;
        }
        MangaLabelUtils.bindChapter(holder.chapter, chapter, context, true);
        if (holder.chapter.getVisibility() == View.VISIBLE && chapterClickListener != null) {
            holder.chapter.setClickable(true);
            holder.chapter.setFocusable(true);
            holder.chapter.setOnClickListener(v -> chapterClickListener.onChapterClick(post));
        }
    }

    private void bindCover(Holder holder, MangaPost post) {
        String url = post.coverImage == null ? "" : post.coverImage.trim();
        String key = post.getSourceId() + "|" + url;
        holder.boundCoverKey = key;
        holder.image.setBackgroundResource(R.drawable.cover_placeholder);
        if (url.isEmpty()) {
            MangaImageLoader.loadShimmerCover(holder.image, url, post.getSourceId());
            holder.progress.setVisibility(View.GONE);
            return;
        }
        holder.progress.setVisibility(View.GONE);
        MangaImageLoader.loadShimmerCover(holder.image, url, post.getSourceId());
    }

    @Override public int getItemCount() { return data.size() + skeletonCount; }

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
        holder.image.animate().cancel();
        MangaImageLoader.clear(holder.image);
        hideSkeleton(holder);
        holder.itemView.setOnClickListener(null);
        MangaItemWaveAnimator.reset(holder.itemView);
        super.onViewRecycled(holder);
    }

    private void bindSkeleton(Holder holder) {
        MangaImageLoader.clear(holder.image);
        ShimmerUtil.showCover(holder.image);
        ShimmerUtil.show(holder.title, 0, 18, 5f, 0.82f);
        if (MangaSettingsManager.shouldLoadLatestChapterLabel(context)) ShimmerUtil.show(holder.chapter, 64, 18, 7f, 1f);
        else holder.chapter.setVisibility(View.GONE);
        holder.progress.setVisibility(View.GONE);
        holder.itemView.setOnClickListener(null);
        holder.itemView.setClickable(false);
    }

    private void hideSkeleton(Holder holder) {
        ShimmerUtil.hide(holder.title);
        ShimmerUtil.hide(holder.chapter);
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    static class Holder extends RecyclerView.ViewHolder {
        final ShapeableImageView image;
        final ProgressBar progress;
        final TextView title;
        final TextView chapter;
        String boundCoverKey = "";
        MangaPost boundPost;

        Holder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.styleV1ImageView);
            progress = itemView.findViewById(R.id.styleV1ImageProgress);
            title = itemView.findViewById(R.id.styleV1TitleTextView);
            chapter = itemView.findViewById(R.id.styleV1ChapterTextView);
        }
    }
}
