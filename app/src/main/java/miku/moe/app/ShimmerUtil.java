package miku.moe.app;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/** Helper untuk memasang / melepas shimmer pada view teks, chip, dan cover. */
public final class ShimmerUtil {
    private ShimmerUtil() {}

    private static final class Saved {
        final Drawable background;
        final int minWidth;
        final int minHeight;

        Saved(View view) {
            background = view.getBackground();
            minWidth = view instanceof TextView ? ((TextView) view).getMinWidth() : view.getMinimumWidth();
            minHeight = view instanceof TextView ? ((TextView) view).getMinHeight() : view.getMinimumHeight();
        }
    }

    private static int dp(View view, float value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    /** Menampilkan batang shimmer menggantikan isi view (teks dikosongkan). */
    public static void show(View view, int minWidthDp, int minHeightDp, float radiusDp, float widthFraction, int lines, boolean alignEnd) {
        if (view == null) return;
        Object tag = view.getTag(R.id.shimmer_saved_state);
        if (!(tag instanceof Saved)) view.setTag(R.id.shimmer_saved_state, new Saved(view));
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setText("");
            text.setMinWidth(dp(view, minWidthDp));
            text.setMinHeight(dp(view, minHeightDp));
        } else {
            view.setMinimumWidth(dp(view, minWidthDp));
            view.setMinimumHeight(dp(view, minHeightDp));
        }
        view.setBackground(new ShimmerDrawable(view.getContext(), radiusDp).widthFraction(widthFraction).lines(lines, 0.6f).alignEnd(alignEnd));
        view.setVisibility(View.VISIBLE);
    }

    public static void show(View view, int minWidthDp, int minHeightDp, float radiusDp, float widthFraction) {
        show(view, minWidthDp, minHeightDp, radiusDp, widthFraction, 1, false);
    }

    /** Melepas shimmer dan mengembalikan background / ukuran minimum asli view. */
    public static void hide(View view) {
        if (view == null) return;
        Object tag = view.getTag(R.id.shimmer_saved_state);
        if (!(tag instanceof Saved)) return;
        Saved saved = (Saved) tag;
        view.setTag(R.id.shimmer_saved_state, null);
        view.setBackground(saved.background);
        if (view instanceof TextView) {
            ((TextView) view).setMinWidth(saved.minWidth);
            ((TextView) view).setMinHeight(saved.minHeight);
        } else {
            view.setMinimumWidth(saved.minWidth);
            view.setMinimumHeight(saved.minHeight);
        }
    }

    public static boolean isShowing(View view) {
        return view != null && view.getTag(R.id.shimmer_saved_state) instanceof Saved;
    }

    /** Cover skeleton: shimmer sebagai drawable gambar (ikut terpotong sudut tumpul ShapeableImageView). */
    public static void showCover(ImageView image) {
        if (image == null) return;
        MangaImageLoader.clear(image);
        image.setBackground(null);
        image.setImageDrawable(new ShimmerDrawable(image.getContext(), 0f));
    }
}
