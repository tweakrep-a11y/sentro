package miku.moe.app;

import android.content.Context;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.imageview.ShapeableImageView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public class MangaHomeCardAdapter extends RecyclerView.Adapter<MangaHomeCardAdapter.Holder> {
    public interface Listener { void onClick(MangaPost post); void onChapterClick(MangaPost post); }
    private static final float DPI_600 = 600f;
    private final Context context;
    private final ArrayList<MangaPost> data;
    private final Listener listener;
    private final Set<MangaPost> chapterRequested = Collections.newSetFromMap(new WeakHashMap<MangaPost, Boolean>());
    private int skeletonCount;

    public MangaHomeCardAdapter(Context context, ArrayList<MangaPost> data, Listener listener) {
        this.context = context;
        this.data = data;
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setSkeletonCount(int count) { skeletonCount = Math.max(0, count); }

    @Override public long getItemId(int position) {
        if (position >= data.size()) return Long.MIN_VALUE + position;
        MangaPost post = data.get(position);
        String key = post == null ? "" : (post.getSourceId() + ":" + (post.slug == null || post.slug.isEmpty() ? post.title : post.slug));
        return key == null ? position : key.hashCode();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(context).inflate(R.layout.manga_home_card_item, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        applyDpi600Card(holder);
        hideSkeleton(holder);
        if (position >= data.size()) {
            bindSkeleton(holder);
            return;
        }
        MangaPost post = data.get(position);
        holder.boundPost = post;
        String itemKey = post.getSourceId() + ":" + (post.slug == null || post.slug.isEmpty() ? post.title : post.slug);
        String coverKey = post.getSourceId() + "|" + (post.coverImage == null ? "" : post.coverImage);
        holder.boundAnimationKey = itemKey;
        holder.title.setText(post.title == null ? "" : post.title);
        MangaTitleStyle.apply(holder.title, context);
        MangaHomeV3GenreResolver.applyCached(post);
        boolean needsType = MangaHomeV3GenreResolver.needsType(post);
        if (needsType) MangaHomeV3GenreResolver.requestType(post, this::notifyResolvedPost);
        bindTypeFlag(holder.typeFlag, post);
        if (needsType && MangaHomeV3GenreResolver.isPending(post) && holder.typeFlag != null) {
            holder.typeFlag.setImageDrawable(null);
            ShimmerUtil.show(holder.typeFlag, 24, 18, 5f, 1f);
        }
        String chapter = post.latestChapter == null ? "" : post.latestChapter.trim();
        boolean needsChapter = chapter.isEmpty() && MangaSettingsManager.shouldLoadLatestChapterLabel(context);
        if (needsChapter && chapterRequested.add(post)) {
            MangaLatestChapterResolver.resolve(post, (resolved, changed) -> notifyResolvedPost(resolved));
        }
        if (chapter.isEmpty() && needsChapter && MangaLatestChapterResolver.isPending(post)) {
            ShimmerUtil.show(holder.chapter, 64, 18, 7f, 1f);
        } else if (chapter.isEmpty()) {
            holder.chapter.setVisibility(View.GONE);
            holder.chapter.setText("");
        } else {
            ShimmerUtil.hide(holder.chapter);
            holder.chapter.setVisibility(View.VISIBLE);
            holder.chapter.setText(chapter);
        }
        bindCover(holder, post, coverKey);
        holder.chapter.setClickable(!chapter.isEmpty() && listener != null);
        holder.chapter.setOnClickListener(chapter.isEmpty() || listener == null ? null : v -> listener.onChapterClick(post));
        holder.itemView.setOnClickListener(v -> { if (listener != null) listener.onClick(post); });
        MangaItemWaveAnimator.reset(holder.itemView);
    }

    private void applyDpi600Card(Holder holder) {
        ViewGroup.MarginLayoutParams itemParams = holder.itemView.getLayoutParams() instanceof ViewGroup.MarginLayoutParams ? (ViewGroup.MarginLayoutParams) holder.itemView.getLayoutParams() : null;
        if (itemParams != null) {
            itemParams.width = dpi600(420);
            itemParams.height = dpi600(650);
            itemParams.setMargins(dpi600(12), 0, dpi600(28), 0);
            holder.itemView.setLayoutParams(itemParams);
        }
        if (holder.overlay != null) {
            ViewGroup.LayoutParams overlayParams = holder.overlay.getLayoutParams();
            overlayParams.height = dpi600(210);
            holder.overlay.setLayoutParams(overlayParams);
        }
        if (holder.typeFlag != null && holder.typeFlag.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams flagParams = (ViewGroup.MarginLayoutParams) holder.typeFlag.getLayoutParams();
            flagParams.width = dpi600(82);
            flagParams.height = dpi600(58);
            flagParams.setMargins(dpi600(20), dpi600(20), dpi600(20), dpi600(20));
            holder.typeFlag.setLayoutParams(flagParams);
        }
        if (holder.content != null) holder.content.setPadding(dpi600(30), 0, dpi600(30), dpi600(24));
        holder.title.setTextSize(TypedValue.COMPLEX_UNIT_PX, dpi600(42));
        holder.title.setLineSpacing(dpi600(4), 1f);
        holder.chapter.setTextSize(TypedValue.COMPLEX_UNIT_PX, dpi600(38));
        if (holder.chapter.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams chapterParams = (ViewGroup.MarginLayoutParams) holder.chapter.getLayoutParams();
            chapterParams.topMargin = dpi600(16);
            holder.chapter.setLayoutParams(chapterParams);
        }
    }

    private int dpi600(int value) {
        int densityDpi = context == null || context.getResources() == null ? 600 : context.getResources().getDisplayMetrics().densityDpi;
        return Math.max(1, Math.round(value * densityDpi / DPI_600));
    }

    private void bindCover(Holder holder, MangaPost post, String coverKey) {
        if (holder == null || holder.image == null || post == null) return;
        String coverUrl = post.coverImage == null ? "" : post.coverImage.trim();
        String sourceId = post.getSourceId();
        boolean sameCover = coverKey.equals(holder.boundCoverKey);
        boolean coverLoaded = !coverUrl.isEmpty() && MangaImageLoader.isLoaded(holder.image, coverUrl, sourceId);
        if (coverUrl.isEmpty()) {
            holder.boundCoverKey = coverKey;
            MangaImageLoader.loadShimmerCover(holder.image, coverUrl, sourceId);
            setCoverLoading(holder, false);
        } else if (!sameCover || !coverLoaded || holder.image.getDrawable() == null) {
            holder.boundCoverKey = coverKey;
            holder.image.animate().cancel();
            holder.image.setAlpha(1f);
            if (!sameCover) holder.image.setImageDrawable(null);
            setCoverLoading(holder, false);
            MangaImageLoader.loadShimmerCover(holder.image, coverUrl, sourceId);
        } else {
            holder.image.animate().cancel();
            holder.image.setAlpha(1f);
            setCoverLoading(holder, false);
        }
    }

    private void setCoverLoading(Holder holder, boolean loading) {
        if (holder == null || holder.cardLoadingProgress == null) return;
        holder.cardLoadingProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void postUpdateCoverLoading(Holder holder, String coverKey, String coverUrl, String sourceId) {
        if (holder == null || holder.image == null) return;
        holder.image.post(() -> updateCoverLoading(holder, coverKey, coverUrl, sourceId));
    }

    private void updateCoverLoading(Holder holder, String coverKey, String coverUrl, String sourceId) {
        if (holder == null || holder.cardLoadingProgress == null) return;
        if (!coverKey.equals(holder.boundCoverKey)) return;
        boolean loaded = MangaImageLoader.isLoaded(holder.image, coverUrl, sourceId);
        holder.cardLoadingProgress.setVisibility(loaded ? View.GONE : View.VISIBLE);
    }

    private void bindTypeFlag(ImageView view, MangaPost post) {
        MangaLabelUtils.bindTypeFlag(view, post, context, true);
    }

    @Override public int getItemCount() { return data.size() + skeletonCount; }

    @Override public void onViewRecycled(@NonNull Holder holder) {
        MangaPost post = holder.boundPost;
        holder.boundPost = null;
        if (post != null) {
            MangaHomeV3GenreResolver.cancelQueued(post);
            MangaLatestChapterResolver.cancelQueued(post);
            chapterRequested.remove(post);
        }
        holder.image.animate().cancel();
        holder.image.setAlpha(1f);
        holder.boundCoverKey = null;
        setCoverLoading(holder, false);
        hideSkeleton(holder);
        MangaImageLoader.clear(holder.image);
        holder.itemView.setOnClickListener(null);
        holder.chapter.setOnClickListener(null);
        MangaItemWaveAnimator.reset(holder.itemView);
        super.onViewRecycled(holder);
    }

    private void bindSkeleton(Holder holder) {
        holder.boundPost = null;
        MangaImageLoader.clear(holder.image);
        ShimmerUtil.showCover(holder.image);
        ShimmerUtil.show(holder.title, 0, 18, 5f, 0.82f);
        if (MangaSettingsManager.shouldLoadTypeLabel(context) && holder.typeFlag != null) {
            holder.typeFlag.setImageDrawable(null);
            ShimmerUtil.show(holder.typeFlag, 24, 18, 5f, 1f);
        } else if (holder.typeFlag != null) {
            holder.typeFlag.setVisibility(View.GONE);
        }
        if (MangaSettingsManager.shouldLoadLatestChapterLabel(context)) ShimmerUtil.show(holder.chapter, 64, 18, 7f, 1f);
        else holder.chapter.setVisibility(View.GONE);
        if (holder.cardLoadingProgress != null) holder.cardLoadingProgress.setVisibility(View.GONE);
        holder.itemView.setOnClickListener(null);
        holder.itemView.setClickable(false);
    }

    private void hideSkeleton(Holder holder) {
        ShimmerUtil.hide(holder.title);
        ShimmerUtil.hide(holder.typeFlag);
        ShimmerUtil.hide(holder.chapter);
    }

    private void notifyResolvedPost(MangaPost resolved) {
        String key = MangaHomeV3GenreResolver.key(resolved);
        if (key.isEmpty()) return;
        for (int index = 0; index < data.size(); index++) {
            MangaPost post = data.get(index);
            if (post == resolved || key.equals(MangaHomeV3GenreResolver.key(post))) {
                MangaHomeV3GenreResolver.applyCached(post);
                notifyItemChanged(index);
            }
        }
    }

    static class Holder extends RecyclerView.ViewHolder {
        ShapeableImageView image;
        TextView title;
        ImageView typeFlag;
        View overlay;
        LinearLayout content;
        ProgressBar cardLoadingProgress;
        String boundCoverKey;
        String boundAnimationKey = "";
        TextView chapter;
        MangaPost boundPost;
        Holder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.imageView);
            title = itemView.findViewById(R.id.titleTextView);
            typeFlag = itemView.findViewById(R.id.typeFlagImageView);
            overlay = itemView.findViewById(R.id.overlayView);
            content = itemView.findViewById(R.id.contentContainer);
            cardLoadingProgress = itemView.findViewById(R.id.cardLoadingProgress);
            chapter = itemView.findViewById(R.id.chapterTextView);
        }
    }
}
