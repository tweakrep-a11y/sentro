package miku.moe.app;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import coil.Coil;
import coil.request.CachePolicy;
import coil.request.Disposable;
import coil.request.ErrorResult;
import coil.request.ImageRequest;
import coil.request.SuccessResult;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class AnimeImageLoader {
    private static final Map<ImageView, Disposable> ACTIVE_REQUESTS = Collections.synchronizedMap(new WeakHashMap<>());

    private AnimeImageLoader() {}

    public static void loadShimmerCover(ImageView target, String url) {
        if (target == null) return;
        target.setBackground(null);
        String safeUrl = url == null ? "" : url.trim();
        if (safeUrl.isEmpty()) {
            cancel(target);
            target.setTag(null);
            showBroken(target);
            return;
        }
        if (safeUrl.startsWith("//")) safeUrl = "https:" + safeUrl;
        String requestKey = "anime|" + safeUrl;
        Object currentTag = target.getTag();
        Drawable current = target.getDrawable();
        if (requestKey.equals(currentTag)) {
            if (current instanceof BrokenImageDrawable) return;
            if (current instanceof ShimmerDrawable && isRequestInFlight(target)) return;
            if (current != null && !(current instanceof ShimmerDrawable)) {
                target.animate().cancel();
                target.setAlpha(1f);
                return;
            }
        }
        Context context = target.getContext();
        cancel(target);
        target.animate().cancel();
        target.setAlpha(1f);
        target.setTag(requestKey);
        ImageRequest request = new ImageRequest.Builder(context)
                .placeholder(new ShimmerDrawable(context, 0f))
                .data(safeUrl)
                .memoryCacheKey(requestKey)
                .diskCacheKey(requestKey)
                .crossfade(500)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.ENABLED)
                .allowHardware(true)
                .target(target)
                .listener(new ImageRequest.Listener() {
                    @Override public void onSuccess(ImageRequest request, SuccessResult result) {
                    }

                    @Override public void onError(ImageRequest request, ErrorResult result) {
                        if (!requestKey.equals(target.getTag())) return;
                        showBroken(target);
                    }
                })
                .build();
        Disposable disposable = Coil.imageLoader(context.getApplicationContext()).enqueue(request);
        ACTIVE_REQUESTS.put(target, disposable);
    }

    public static void clear(ImageView target) {
        if (target == null) return;
        cancel(target);
        target.animate().cancel();
        target.setAlpha(1f);
        target.setTag(null);
        target.setImageDrawable(null);
    }

    private static void cancel(ImageView target) {
        try {
            Disposable disposable = ACTIVE_REQUESTS.remove(target);
            if (disposable != null) disposable.dispose();
        } catch (Exception ignored) {
        }
    }

    private static boolean isRequestInFlight(ImageView target) {
        Disposable active = ACTIVE_REQUESTS.get(target);
        return active != null && !active.isDisposed();
    }

    private static void showBroken(ImageView target) {
        target.animate().cancel();
        target.setAlpha(1f);
        target.setImageDrawable(new BrokenImageDrawable(target.getContext()));
    }
}
