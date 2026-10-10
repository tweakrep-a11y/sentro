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

public class AnimeHomeV2RankAdapter extends RecyclerView.Adapter<AnimeHomeV2RankAdapter.Holder> {
    public interface Listener {
        void onClick(AnimePost post);
    }

    private final Context context;
    private ArrayList<AnimePost> data;
    private final Listener listener;
    private int skeletonCount;

    public AnimeHomeV2RankAdapter(Context context, ArrayList<AnimePost> data, Listener listener) {
        this.context = context;
        this.data = data;
        this.listener = listener;
        setHasStableIds(true);
    }

    public void setData(ArrayList<AnimePost> data) {
        this.data = data;
    }

    public void setSkeletonCount(int count) {
        this.skeletonCount = Math.max(0, count);
    }

    @Override public long getItemId(int position) {
        if (position >= data.size()) return Long.MIN_VALUE + 1000L + (position - data.size());
        return AnimeHomeV2Labels.itemKey(data.get(position)).hashCode();
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
        AnimePost post = data.get(position);
        ShimmerUtil.hide(holder.title);
        ShimmerUtil.hide(holder.typeChip);
        holder.title.setText(post.categoryName == null ? "" : post.categoryName.trim().replaceAll("\\s+", " "));
        MangaTitleStyle.apply(holder.title, context);
        holder.timeline.setEdges(false, false);
        AnimeHomeV2Resolver.applyCached(post);
        boolean needs = AnimeHomeV2Resolver.needsResolve(post);
        if (needs) AnimeHomeV2Resolver.request(post, this::notifyResolved);
        if (!AnimeHomeV2Labels.rankText(post).isEmpty()) {
            AnimeHomeV2Labels.bindRank(holder.typeChip, post);
        } else if (needs && AnimeHomeV2Resolver.isPending(post)) {
            ShimmerUtil.show(holder.typeChip, 72, 28, 10f, 1f);
        } else {
            AnimeHomeV2Labels.bindRank(holder.typeChip, post);
        }
        AnimeImageLoader.loadShimmerCover(holder.image, post.imgUrl);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(post);
        });
    }

    private void bindSkeleton(Holder holder) {
        holder.itemView.setOnClickListener(null);
        holder.itemView.setClickable(false);
        holder.timeline.setEdges(false, false);
        AnimeImageLoader.clear(holder.image);
        ShimmerUtil.showCover(holder.image);
        ShimmerUtil.show(holder.title, 0, 34, 6f, 0.9f, 2, false);
        ShimmerUtil.show(holder.typeChip, 72, 28, 10f, 1f);
    }

    private void notifyResolved(AnimePost resolved) {
        String key = AnimeHomeV2Resolver.key(resolved);
        if (key.isEmpty()) return;
        for (int i = 0; i < data.size(); i++) {
            AnimePost item = data.get(i);
            if (item == null) continue;
            if (item == resolved) {
                notifyItemChanged(i);
            } else if (key.equals(AnimeHomeV2Resolver.key(item))) {
                AnimeHomeV2Resolver.applyCached(item);
                notifyItemChanged(i);
            }
        }
    }

    @Override public int getItemCount() {
        return data.size() + skeletonCount;
    }

    @Override public void onViewRecycled(@NonNull Holder holder) {
        holder.image.animate().cancel();
        AnimeImageLoader.clear(holder.image);
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
