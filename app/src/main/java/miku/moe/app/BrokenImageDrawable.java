package miku.moe.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.TypedValue;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.color.MaterialColors;

/** Cadangan statis saat gambar gagal dimuat: latar polos dengan teks "Gambar Rusak". Tidak beranimasi. */
public final class BrokenImageDrawable extends Drawable {
    public static final String MESSAGE = "Gambar Rusak";

    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final float scaledDensity;
    private StaticLayout layout;
    private int layoutWidth = -1;

    public BrokenImageDrawable(@NonNull Context context) {
        density = context.getResources().getDisplayMetrics().density;
        scaledDensity = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, context.getResources().getDisplayMetrics());
        background.setColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFFE4E6EB));
        textPaint.setColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF666666));
        textPaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
    }

    @Override public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        canvas.drawRect(b, background);
        float size = Math.max(9f * scaledDensity, Math.min(15f * scaledDensity, b.width() * 0.13f));
        int available = Math.max(1, b.width() - Math.round(12f * density));
        if (layout == null || layoutWidth != available || textPaint.getTextSize() != size) {
            textPaint.setTextSize(size);
            layout = StaticLayout.Builder.obtain(MESSAGE, 0, MESSAGE.length(), textPaint, available)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setMaxLines(3)
                    .build();
            layoutWidth = available;
        }
        int save = canvas.save();
        canvas.translate(b.left + (b.width() - available) / 2f, b.top + (b.height() - layout.getHeight()) / 2f);
        layout.draw(canvas);
        canvas.restoreToCount(save);
    }

    @Override public void setAlpha(int alpha) { background.setAlpha(alpha); textPaint.setAlpha(alpha); invalidateSelf(); }

    @Override public void setColorFilter(@Nullable ColorFilter colorFilter) { background.setColorFilter(colorFilter); textPaint.setColorFilter(colorFilter); invalidateSelf(); }

    @SuppressWarnings("deprecation")
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
