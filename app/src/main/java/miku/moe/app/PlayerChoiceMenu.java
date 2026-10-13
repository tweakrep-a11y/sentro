package miku.moe.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

/** Menu pilihan player dengan tanda centang (bukan radio button). */
final class PlayerChoiceMenu {
    interface OnPick { void onPick(int index); }

    private PlayerChoiceMenu() {}

    static void show(Activity activity, View anchor, String[] labels, int checked, OnPick onPick, Runnable onDismiss) {
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        float d = dm.density;
        int margin = Math.round(12 * d);
        int maxH = Math.round(dm.heightPixels * 0.9f) - margin * 2;
        int vPad = Math.round(6 * d);
        // Tinggi item menyesuaikan layar (min 36dp) supaya semua pilihan muat tanpa scroll, terutama di landscape.
        int rowH = Math.max(Math.round(36 * d), Math.min(Math.round(48 * d), (maxH - vPad * 2) / Math.max(1, labels.length)));

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, vPad, 0, vPad);

        final PopupWindow[] holder = new PopupWindow[1];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(rowH);
            row.setPadding(Math.round(18 * d), 0, Math.round(14 * d), 0);
            row.setBackgroundResource(R.drawable.player_v2_menu_item);

            TextView label = new TextView(activity);
            label.setText(labels[i]);
            label.setTextColor(0xFFFFFFFF);
            label.setTextSize(15f);
            label.setSingleLine(true);
            label.setTypeface(i == checked ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            ImageView check = new ImageView(activity);
            check.setImageResource(R.drawable.ic_player_check);
            check.setVisibility(i == checked ? View.VISIBLE : View.INVISIBLE);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(Math.round(22 * d), Math.round(22 * d));
            cp.setMarginStart(Math.round(20 * d));
            row.addView(check, cp);

            row.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                if (onPick != null) onPick.onPick(index);
            });
            list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(Math.round(24 * d));
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(list);
        scroll.setBackgroundResource(R.drawable.player_v2_menu_bg);
        scroll.setClipToOutline(true);
        scroll.setClipChildren(true);

        int minW = Math.round(150 * d);
        scroll.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels - margin * 2, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
        int w = Math.max(minW, scroll.getMeasuredWidth());
        int h = Math.min(maxH, scroll.getMeasuredHeight());

        PopupWindow popup = new PopupWindow(scroll, w, h, true);
        holder[0] = popup;
        popup.setBackgroundDrawable(new ColorDrawable(0));
        popup.setOutsideTouchable(true);
        popup.setElevation(8 * d);
        popup.setOnDismissListener(() -> { if (onDismiss != null) onDismiss.run(); });

        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int x = loc[0] + anchor.getWidth() - w;                 // rata kanan dengan tombol
        x = Math.max(margin, Math.min(x, dm.widthPixels - w - margin));
        boolean showBelow = loc[1] + anchor.getHeight() / 2 < dm.heightPixels / 2;
        int y = showBelow ? loc[1] + anchor.getHeight() + Math.round(6 * d) : loc[1] - h - Math.round(6 * d);
        y = Math.max(margin, Math.min(y, dm.heightPixels - h - margin));
        popup.showAtLocation(anchor, Gravity.TOP | Gravity.START, x, y);
    }
}
