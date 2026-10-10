package miku.moe.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import com.google.android.material.color.MaterialColors;

public class HomeV3TimelineView extends View {
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private boolean horizontal;
    private boolean first;
    private boolean last;
    private float dotOffset;

    public HomeV3TimelineView(Context context) {
        this(context, null, 0);
    }

    public HomeV3TimelineView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HomeV3TimelineView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = getResources().getDisplayMetrics().density;
        dotOffset = density * 30f;
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(density * 2f);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        haloPaint.setStyle(Paint.Style.FILL);
        dotPaint.setStyle(Paint.Style.FILL);
    }

    public void setHorizontal(boolean horizontal) {
        this.horizontal = horizontal;
        invalidate();
    }

    public void setDotOffsetDp(float dp) {
        this.dotOffset = density * dp;
        invalidate();
    }

    public void setEdges(boolean first, boolean last) {
        this.first = first;
        this.last = last;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        int primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary);
        int outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant);
        linePaint.setColor(outline);
        dotPaint.setColor(primary);
        haloPaint.setColor((primary & 0x00FFFFFF) | 0x40000000);
        float width = getWidth();
        float height = getHeight();
        float cx;
        float cy;
        if (horizontal) {
            cx = dotOffset < 0f ? width / 2f : Math.min(dotOffset, width);
            cy = height / 2f;
            float startX = first ? cx : 0f;
            float endX = last ? cx : width;
            canvas.drawLine(startX, cy, endX, cy, linePaint);
        } else {
            cx = width / 2f;
            cy = Math.min(dotOffset, height);
            float startY = first ? cy : 0f;
            float endY = last ? cy : height;
            canvas.drawLine(cx, startY, cx, endY, linePaint);
        }
        canvas.drawCircle(cx, cy, density * 9f, haloPaint);
        canvas.drawCircle(cx, cy, density * 5f, dotPaint);
    }
}
