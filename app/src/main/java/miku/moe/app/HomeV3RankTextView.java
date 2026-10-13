package miku.moe.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import androidx.appcompat.widget.AppCompatTextView;
import com.google.android.material.color.MaterialColors;

public class HomeV3RankTextView extends AppCompatTextView {
    public HomeV3RankTextView(Context context) {
        super(context);
    }

    public HomeV3RankTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public HomeV3RankTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (getLayout() == null) return;
        int stroke = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary);
        int fill = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface);
        String text = getText() == null ? "" : getText().toString();
        float x = getCompoundPaddingLeft();
        float y = getExtendedPaddingTop() + getLayout().getLineBaseline(0);
        Paint paint = new Paint(getPaint());
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(getResources().getDisplayMetrics().density * 2f);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(stroke);
        canvas.drawText(text, x, y, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fill);
        canvas.drawText(text, x, y, paint);
    }
}
