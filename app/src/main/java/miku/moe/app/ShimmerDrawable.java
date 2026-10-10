package miku.moe.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.Choreographer;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import com.google.android.material.color.MaterialColors;

/**
 * Efek shimmer ala Facebook: blok abu-abu dengan sudut tumpul dan kilau miring yang bergerak
 * dari kiri ke kanan. Semua shimmer memakai jam yang sama, jadi gerakannya selalu serempak.
 * Animasi hanya berjalan selama drawable terlihat dan terpasang di view, jadi tidak bocor
 * dan berhenti sendiri begitu drawable dilepas (misalnya saat gambar sudah termuat atau rusak).
 */
public final class ShimmerDrawable extends Drawable {
    private static final long PERIOD_MS = 1400L;
    // Satu Choreographer callback dipakai bersama oleh semua shimmer yang tampil. Sebelumnya tiap
    // drawable memakai timer 20ms sendiri-sendiri; itu tidak sejajar dengan vsync 16.6ms sehingga
    // animasi terasa patah-patah dan memicu banyak invalidate terpisah.
    private static final java.util.ArrayList<ShimmerDrawable> ACTIVE = new java.util.ArrayList<>();
    private static boolean frameQueued;
    private static final Choreographer.FrameCallback FRAME = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            frameQueued = false;
            for (int i = ACTIVE.size() - 1; i >= 0; i--) {
                ShimmerDrawable d = ACTIVE.get(i);
                if (d.getCallback() == null || !d.isVisible()) {
                    d.registered = false;
                    ACTIVE.remove(i);
                } else {
                    d.invalidateSelf();
                }
            }
            if (!ACTIVE.isEmpty()) {
                frameQueued = true;
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };
    private boolean registered;
    private static final float TILT_DEGREES = 18f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF rect = new RectF();
    private final LinearGradient shader;
    private final float density;
    private final float radius;
    private float widthFraction = 1f;
    private float insetH;
    private float insetV;
    private int lines = 1;
    private float lastLineFraction = 0.6f;
    private boolean alignEnd;

    public ShimmerDrawable(@NonNull Context context, float radiusDp) {
        density = context.getResources().getDisplayMetrics().density;
        radius = Math.max(0f, radiusDp) * density;
        int base = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFFE4E6EB);
        boolean light = ColorUtils.calculateLuminance(base) > 0.5;
        int highlight = ColorUtils.blendARGB(base, Color.WHITE, light ? 0.70f : 0.14f);
        shader = new LinearGradient(0f, 0f, 1f, 0f,
                new int[]{base, highlight, base}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(shader);
    }

    /** Hanya menggambar sebagian lebar dari area view (0..1). */
    public ShimmerDrawable widthFraction(float fraction) {
        widthFraction = Math.max(0.05f, Math.min(1f, fraction));
        return this;
    }

    public ShimmerDrawable insetDp(float horizontal, float vertical) {
        insetH = horizontal * density;
        insetV = vertical * density;
        return this;
    }

    /** Menggambar beberapa batang teks; batang terakhir lebih pendek. */
    public ShimmerDrawable lines(int count, float lastFraction) {
        lines = Math.max(1, count);
        lastLineFraction = Math.max(0.2f, Math.min(1f, lastFraction));
        return this;
    }

    public ShimmerDrawable alignEnd(boolean end) {
        alignEnd = end;
        return this;
    }

    @Override public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        float w = b.width();
        float h = b.height();
        float t = (SystemClock.uptimeMillis() % PERIOD_MS) / (float) PERIOD_MS;
        float band = Math.max(w * 0.9f, density * 140f);
        float slant = h * (float) Math.tan(Math.toRadians(TILT_DEGREES));
        float travel = w + band + slant;
        float x = -band + t * travel;
        matrix.setScale(band, 1f);
        matrix.postRotate(TILT_DEGREES);
        matrix.postTranslate(b.left + x, b.top);
        shader.setLocalMatrix(matrix);

        float areaLeft = b.left + insetH;
        float areaW = Math.max(1f, w - insetH * 2f);
        float top = b.top + insetV;
        float availableH = Math.max(1f, h - insetV * 2f);
        if (lines <= 1) {
            drawBar(canvas, areaLeft, areaW * widthFraction, top, availableH, areaW, areaLeft);
        } else {
            float gap = density * 5f;
            float barH = Math.max(1f, (availableH - gap * (lines - 1)) / lines);
            for (int i = 0; i < lines; i++) {
                float f = i == lines - 1 ? lastLineFraction : 1f;
                drawBar(canvas, areaLeft, areaW * widthFraction * f, top + i * (barH + gap), barH, areaW, areaLeft);
            }
        }
        if (isVisible() && getCallback() != null) requestFrames();
    }

    private void requestFrames() {
        if (!registered) {
            registered = true;
            ACTIVE.add(this);
        }
        if (!frameQueued) {
            frameQueued = true;
            Choreographer.getInstance().postFrameCallback(FRAME);
        }
    }

    private void drawBar(Canvas canvas, float left, float width, float top, float height, float areaW, float areaLeft) {
        float l = alignEnd ? areaLeft + areaW - width : left;
        rect.set(l, top, l + width, top + height);
        float r = Math.min(radius, Math.min(rect.width(), rect.height()) / 2f);
        if (r <= 0f) canvas.drawRect(rect, paint);
        else canvas.drawRoundRect(rect, r, r, paint);
    }

    @Override public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (visible) invalidateSelf();
        else if (registered) {
            ACTIVE.remove(this);
            registered = false;
        }
        return changed;
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }

    @Override public void setColorFilter(@Nullable ColorFilter colorFilter) { paint.setColorFilter(colorFilter); invalidateSelf(); }

    @SuppressWarnings("deprecation")
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
