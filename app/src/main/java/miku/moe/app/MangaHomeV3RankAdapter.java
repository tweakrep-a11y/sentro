package miku.moe.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.imageview.ShapeableImageView;
import java.util.ArrayList;

public class MangaHomeV3RankAdapter extends RecyclerView.Adapter<MangaHomeV3RankAdapter.Holder> {
    public interface Listener {
        void onClick(MangaPost post);
        void onNeedChapter(MangaPost post);
    }

    private final Context context;
    private ArrayList<MangaPost> data;
    private final Listener listener;
    private final java.util.Set<MangaPost> chapterRequested = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<MangaPost, Boolean>());
    private int skeletonCount;

    public MangaHomeV3RankAdapter(Context context, ArrayList<MangaPost> data, Listener listener) {
        this.context = context;
        this.data = data;
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setData(ArrayList<MangaPost> data) {
        this.data = data;
    }

    /** Jumlah kartu shimmer yang ditampilkan setelah data nyata (atau seluruhnya bila data kosong). */
    public void setSkeletonCount(int count) {
        this.skeletonCount = Math.max(0, count);
    }

    @Override public long getItemId(int position) {
        if (position >= data.size()) return Long.MIN_VALUE + 1000L + (position - data.size());
        MangaPost post = data.get(position);
        String key = post.getSourceId() + ":" + (post.slug == null || post.slug.isEmpty() ? post.title : post.slug);
        return key.hashCode();
    }

    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.manga_home_v3_rank_item, parent, false);
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(Math.round(context.getResources().getDisplayMetrics().density * 126f), ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(Math.round(context.getResources().getDisplayMetrics().density * 2f));
        view.setLayoutParams(params);
        return new Holder(view);
    }

    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        if (position >= data.size()) {
            bindSkeleton(holder);
            return;
        }
        MangaPost post = data.get(position);
        ShimmerUtil.hide(holder.title);
        ShimmerUtil.hide(holder.typeChip);
        holder.title.setText(post.title == null ? "" : post.title);
        MangaTitleStyle.apply(holder.title, context);
        holder.timeline.setEdges(false, false);
        MangaHomeV3GenreResolver.applyCached(post);
        boolean needsType = MangaHomeV3GenreResolver.needsType(post);
        if (needsType) MangaHomeV3GenreResolver.requestType(post, this::notifyTypeChanged);
        if (!MangaHomeV3Labels.typeText(post).isEmpty()) {
            MangaHomeV3Labels.bindType(holder.typeChip, post);
        } else if (needsType && MangaHomeV3GenreResolver.isPending(post)) {
            ShimmerUtil.show(holder.typeChip, 72, 28, 10f, 1f);
        } else {
            MangaHomeV3Labels.bindType(holder.typeChip, post);
        }
        if (MangaLatestChapterResolver.normalize(post.latestChapter).isEmpty() && MangaSettingsManager.shouldLoadLatestChapterLabel(context) && listener != null && chapterRequested.add(post)) listener.onNeedChapter(post);
        String url = post.coverImage == null ? "" : post.coverImage.trim();
        MangaImageLoader.loadShimmerCover(holder.image, url, post.getSourceId());
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(post);
        });
    }

    private void bindSkeleton(Holder holder) {
        holder.itemView.setOnClickListener(null);
        holder.itemView.setClickable(false);
        holder.timeline.setEdges(false, false);
        ShimmerUtil.showCover(holder.image);
        ShimmerUtil.show(holder.title, 0, 34, 6f, 0.9f, 2, false);
        ShimmerUtil.show(holder.typeChip, 72, 28, 10f, 1f);
    }

    private void notifyTypeChanged(MangaPost resolved) {
        String key = MangaHomeV3GenreResolver.key(resolved);
        if (key.isEmpty()) return;
        for (int i = 0; i < data.size(); i++) {
            MangaPost item = data.get(i);
            if (item == null) continue;
            if (item == resolved) {
                notifyItemChanged(i);
            } else if (key.equals(MangaHomeV3GenreResolver.key(item))) {
                MangaHomeV3GenreResolver.applyCached(item);
                notifyItemChanged(i);
            }
        }
    }

    @Override public int getItemCount() { return data.size() + skeletonCount; }

    @Override public void onViewRecycled(@NonNull Holder holder) {
        holder.image.animate().cancel();
        MangaImageLoader.clear(holder.image);
        ShimmerUtil.hide(holder.title);
        ShimmerUtil.hide(holder.typeChip);
        super.onViewRecycled(holder);
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ShapeableImageView image;
        final HomeV3TimelineView timeline;
        final TextView title;
        final TextView typeChip;

        Holder(@NonNull View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.homeV3RankImage);
            timeline = itemView.findViewById(R.id.homeV3RankTimeline);
            timeline.setHorizontal(true);
            timeline.setDotOffsetDp(-1f);
            title = itemView.findViewById(R.id.homeV3RankTitle);
            typeChip = itemView.findViewById(R.id.homeV3RankTypeChip);
        }
    }
}
