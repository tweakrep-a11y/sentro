package miku.moe.app;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class MangaWebtoonRecyclerView extends RecyclerView {
    private static final long ZOOM_DURATION = 180L;
    private static final long DOUBLE_TAP_DURATION = 220L;
    private static final long SINGLE_TAP_MAX_DURATION = 400L;
    private static final float DEFAULT_SCALE = 1f;
    private static final float MIN_SCALE = 0.5f;
    private static final float MAX_SCALE = 2.5f;
    private static final float DOUBLE_TAP_SCALE = 2f;
    private static final float FLING_DISTANCE_FACTOR = 0.4f;
    private static final long FLING_DURATION = 400L;
    private SingleTapListener singleTapListener;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private boolean zoomEnabled;
    private boolean doubleTapZoomEnabled = true;
    private boolean isZooming;
    private boolean isZoomDragging;
    private boolean isDoubleTapping;
    private boolean isQuickScaling;
    private boolean tapDuringManualScroll;
    private boolean isManuallyScrolling;
    private boolean validSingleTapCandidate;
    private float tapDownX;
    private float tapDownY;
    private long tapDownTime;
    private long lastTapDuration;
    private long suppressSingleTapUntil;
    private float pendingSingleTapX;
    private float pendingSingleTapY;
    private long pendingSingleTapDownTime;
    private int tapMoveSlop;
    private int scrollPointerId;
    private int downX;
    private int downY;
    private int lastX;
    private int lastY;
    private int originalHeight;
    private AnimatorSet flingAnimator;
    private int halfWidth;
    private int halfHeight;
    private int touchSlop;
    private float currentScale = DEFAULT_SCALE;
    private int firstVisibleItemPosition;
    private int lastVisibleItemPosition;
    private boolean externalGestureFrameEnabled;

    public MangaWebtoonRecyclerView(@NonNull Context context) {
        this(context, null);
    }

    public MangaWebtoonRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MangaWebtoonRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setClipToPadding(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        tapMoveSlop = Math.max(touchSlop, Math.round(12f * getResources().getDisplayMetrics().density));
        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        gestureDetector = new GestureDetector(context, new TapListener());
        scaleDetector.setQuickScaleEnabled(true);
    }

    public interface SingleTapListener {
        void onSingleTap(float x, float y, int width, int height);
    }

    public void setSingleTapListener(SingleTapListener listener) {
        singleTapListener = listener;
    }

    void setExternalGestureFrameEnabled(boolean enabled) {
        externalGestureFrameEnabled = enabled;
        scaleDetector.setQuickScaleEnabled(!enabled && doubleTapZoomEnabled);
    }

    boolean isReaderZoomEnabled() {
        return zoomEnabled || doubleTapZoomEnabled;
    }

    public void setZoomEnabled(boolean enabled) {
        zoomEnabled = enabled;
        if (!isReaderZoomEnabled()) resetZoom();
    }

    public void setDoubleTapZoomEnabled(boolean enabled) {
        doubleTapZoomEnabled = enabled;
        scaleDetector.setQuickScaleEnabled(!externalGestureFrameEnabled && enabled);
        if (getParent() instanceof MangaWebtoonFrame) ((MangaWebtoonFrame) getParent()).setDoubleTapZoomEnabled(enabled);
        if (!enabled) {
            isDoubleTapping = false;
            isQuickScaling = false;
        }
        if (!isReaderZoomEnabled()) resetZoom();
    }

    public void resetZoom() {
        cancelZoomAnimation();
        cancelFlingAnimation();
        currentScale = DEFAULT_SCALE;
        applyScaleLayout();
        isZooming = false;
        isZoomDragging = false;
        isDoubleTapping = false;
        isQuickScaling = false;
        setScaleRate(DEFAULT_SCALE);
        setTranslationX(0f);
        setTranslationY(0f);
        requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int measuredHeight = MeasureSpec.getSize(heightSpec);
        halfWidth = MeasureSpec.getSize(widthSpec) / 2;
        halfHeight = measuredHeight / 2;
        if (currentScale >= DEFAULT_SCALE) originalHeight = measuredHeight;
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        updateSingleTapCandidate(ev);
        if (isReaderZoomEnabled() && !externalGestureFrameEnabled) scaleDetector.onTouchEvent(ev);
        gestureDetector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) tapDuringManualScroll = isManuallyScrolling;
        if (isReaderZoomEnabled() && handleZoomTouch(e)) return true;
        return super.onTouchEvent(e);
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private void updateSingleTapCandidate(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            validSingleTapCandidate = SystemClock.uptimeMillis() >= suppressSingleTapUntil;
            tapDownX = ev.getX();
            tapDownY = ev.getY();
            tapDownTime = ev.getEventTime();
            lastTapDuration = 0L;
            return;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) {
            validSingleTapCandidate = false;
            return;
        }
        if (action == MotionEvent.ACTION_MOVE && validSingleTapCandidate) {
            float dx = ev.getX() - tapDownX;
            float dy = ev.getY() - tapDownY;
            if ((dx * dx) + (dy * dy) > tapMoveSlop * tapMoveSlop) validSingleTapCandidate = false;
            return;
        }
        if (action == MotionEvent.ACTION_UP) {
            lastTapDuration = ev.getEventTime() - tapDownTime;
            pendingSingleTapX = ev.getX();
            pendingSingleTapY = ev.getY();
            pendingSingleTapDownTime = tapDownTime;
            if (validSingleTapCandidate && lastTapDuration <= SINGLE_TAP_MAX_DURATION) postDelayed(singleTapFallbackRunnable, ViewConfiguration.getDoubleTapTimeout() + 40L);
        }
    }

    @Override public void onScrolled(int dx, int dy) {
        super.onScrolled(dx, dy);
        LayoutManager manager = getLayoutManager();
        if (manager instanceof LinearLayoutManager) {
            LinearLayoutManager linear = (LinearLayoutManager) manager;
            firstVisibleItemPosition = linear.findFirstVisibleItemPosition();
            lastVisibleItemPosition = linear.findLastVisibleItemPosition();
        }
    }

    @Override public void onScrollStateChanged(int state) {
        super.onScrollStateChanged(state);
        if (state == SCROLL_STATE_IDLE) isManuallyScrolling = false;
        else if (state == SCROLL_STATE_DRAGGING) {
            isManuallyScrolling = true;
            validSingleTapCandidate = false;
        }
    }

    private float parentX(float localX) {
        return (localX - getPivotX()) * getScaleX() + getPivotX() + getTranslationX() + getLeft();
    }

    private float parentY(float localY) {
        return (localY - getPivotY()) * getScaleY() + getPivotY() + getTranslationY() + getTop();
    }

    private boolean isAtListEdge() {
        LayoutManager manager = getLayoutManager();
        if (!(manager instanceof LinearLayoutManager)) return false;
        LinearLayoutManager linear = (LinearLayoutManager) manager;
        int count = linear.getItemCount();
        if (count <= 0) return false;
        int first = linear.findFirstVisibleItemPosition();
        int last = linear.findLastVisibleItemPosition();
        return first == 0 || last == count - 1;
    }

    private boolean handleZoomTouch(MotionEvent ev) {
        int action = ev.getActionMasked();
        int actionIndex = ev.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN) {
            cancelFlingAnimation();
            scrollPointerId = ev.getPointerId(0);
            downX = Math.round(parentX(ev.getX()));
            downY = Math.round(parentY(ev.getY()));
            lastX = downX;
            lastY = downY;
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            scrollPointerId = ev.getPointerId(actionIndex);
            downX = Math.round(parentX(ev.getX(actionIndex)));
            downY = Math.round(parentY(ev.getY(actionIndex)));
            lastX = downX;
            lastY = downY;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            if (ev.getPointerId(actionIndex) == scrollPointerId) {
                int newIndex = actionIndex == 0 ? 1 : 0;
                if (newIndex < ev.getPointerCount()) {
                    scrollPointerId = ev.getPointerId(newIndex);
                    downX = Math.round(parentX(ev.getX(newIndex)));
                    downY = Math.round(parentY(ev.getY(newIndex)));
                    lastX = downX;
                    lastY = downY;
                }
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (isDoubleTapping && isQuickScaling) return true;
            if (ev.getPointerCount() > 1) return true;
            int index = ev.findPointerIndex(scrollPointerId);
            if (index < 0) return false;
            int x = Math.round(parentX(ev.getX(index)));
            int y = Math.round(parentY(ev.getY(index)));
            boolean edge = isAtListEdge();
            if (!isZoomDragging && currentScale > DEFAULT_SCALE) {
                int dx = x - downX;
                int dy = edge ? y - downY : 0;
                boolean startScroll = false;
                if (Math.abs(dx) > touchSlop) {
                    dx += dx < 0 ? touchSlop : -touchSlop;
                    startScroll = true;
                }
                if (Math.abs(dy) > touchSlop) {
                    dy += dy < 0 ? touchSlop : -touchSlop;
                    startScroll = true;
                }
                if (startScroll) {
                    isZoomDragging = true;
                    zoomScrollBy(dx, dy);
                }
            } else if (isZoomDragging) {
                zoomScrollBy(x - lastX, edge ? y - lastY : 0);
            }
            lastX = x;
            lastY = y;
        } else if (action == MotionEvent.ACTION_UP) {
            if (doubleTapZoomEnabled && isDoubleTapping && !isQuickScaling) doubleTapZoom(ev);
            isZoomDragging = false;
            isDoubleTapping = false;
            isQuickScaling = false;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        } else if (action == MotionEvent.ACTION_CANCEL) {
            isZoomDragging = false;
            isDoubleTapping = false;
            isQuickScaling = false;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }
        return false;
    }

    private float getPositionX(float positionX) {
        if (currentScale <= DEFAULT_SCALE) return 0f;
        float max = halfWidth * (currentScale - DEFAULT_SCALE);
        return Math.max(-max, Math.min(max, positionX));
    }

    private float getPositionY(float positionY) {
        if (currentScale < DEFAULT_SCALE) return originalHeight / 2f - halfHeight;
        float max = halfHeight * (currentScale - DEFAULT_SCALE);
        return Math.max(-max, Math.min(max, positionY));
    }

    private void applyScaleLayout() {
        ViewGroup.LayoutParams params = getLayoutParams();
        if (params == null) return;
        if (currentScale < DEFAULT_SCALE && originalHeight > 0) {
            params.height = Math.round(originalHeight / currentScale);
            halfHeight = params.height / 2;
            requestLayout();
        } else if (params.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            if (originalHeight > 0) halfHeight = originalHeight / 2;
            requestLayout();
        }
    }

    private void applyTranslationForScale() {
        if (currentScale == DEFAULT_SCALE) {
            setTranslationX(0f);
            setTranslationY(0f);
        } else {
            setTranslationX(getPositionX(getTranslationX()));
            setTranslationY(getPositionY(getTranslationY()));
        }
    }

    private void setScaleRate(float rate) {
        setScaleX(rate);
        setScaleY(rate);
    }

    private void zoomScrollBy(int dx, int dy) {
        if (dx != 0) setTranslationX(getPositionX(getTranslationX() + dx));
        if (dy != 0) setTranslationY(getPositionY(getTranslationY() + dy));
    }

    boolean onReaderScaleBegin() {
        if (!zoomEnabled && !isDoubleTapping) return false;
        cancelZoomAnimation();
        cancelFlingAnimation();
        if (isDoubleTapping) isQuickScaling = true;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }

    boolean onReaderScale(float scaleFactor) {
        return onReaderScale(scaleFactor, halfWidth, halfHeight);
    }

    boolean onReaderScale(float scaleFactor, float focusX, float focusY) {
        if (!isQuickScaling && !zoomEnabled) return false;
        if (Math.abs(scaleFactor - DEFAULT_SCALE) < 0.003f) return true;
        float oldScale = currentScale;
        float newScale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, currentScale * scaleFactor));
        if (oldScale == 0f || Float.isNaN(oldScale) || Float.isNaN(newScale)) return false;
        float factor = newScale / oldScale;
        float nextX = focusX - halfWidth - (focusX - halfWidth - getTranslationX()) * factor;
        float nextY = focusY - halfHeight - (focusY - halfHeight - getTranslationY()) * factor;
        currentScale = newScale;
        setScaleRate(currentScale);
        applyScaleLayout();
        if (currentScale > DEFAULT_SCALE) {
            setTranslationX(getPositionX(nextX));
            setTranslationY(getPositionY(nextY));
        } else {
            applyTranslationForScale();
        }
        invalidate();
        return true;
    }

    void onReaderScaleEnd() {
        applyTranslationForScale();
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    void onManualReaderScroll() {
        isManuallyScrolling = true;
    }

    boolean zoomFling(int velocityX, int velocityY) {
        validSingleTapCandidate = false;
        isManuallyScrolling = true;
        if (!isReaderZoomEnabled() || currentScale <= DEFAULT_SCALE) return false;
        cancelFlingAnimation();
        boolean edge = isAtListEdge();
        List<Animator> animators = new ArrayList<>();
        if (velocityX != 0) {
            float startX = getTranslationX();
            float endX = getPositionX(startX + FLING_DISTANCE_FACTOR * velocityX / 2f);
            ValueAnimator xAnimator = ValueAnimator.ofFloat(startX, endX);
            xAnimator.addUpdateListener(animation -> setTranslationX(getPositionX((float) animation.getAnimatedValue())));
            animators.add(xAnimator);
        }
        if (velocityY != 0 && edge) {
            float startY = getTranslationY();
            float endY = getPositionY(startY + FLING_DISTANCE_FACTOR * velocityY / 2f);
            ValueAnimator yAnimator = ValueAnimator.ofFloat(startY, endY);
            yAnimator.addUpdateListener(animation -> setTranslationY(getPositionY((float) animation.getAnimatedValue())));
            animators.add(yAnimator);
        }
        if (animators.isEmpty()) return false;
        AnimatorSet set = new AnimatorSet();
        set.playTogether(animators);
        set.setDuration(FLING_DURATION);
        set.setInterpolator(new DecelerateInterpolator());
        flingAnimator = set;
        set.start();
        return true;
    }

    private void cancelFlingAnimation() {
        if (flingAnimator != null) {
            flingAnimator.cancel();
            flingAnimator = null;
        }
    }

    private void animateZoom(float fromScale, float toScale, float fromX, float toX, float fromY, float toY, long duration) {
        cancelZoomAnimation();
        isZooming = true;
        AnimatorSet set = new AnimatorSet();
        ValueAnimator scaleAnimator = ValueAnimator.ofFloat(fromScale, toScale);
        ValueAnimator xAnimator = ValueAnimator.ofFloat(fromX, toX);
        ValueAnimator yAnimator = ValueAnimator.ofFloat(fromY, toY);
        scaleAnimator.addUpdateListener(animation -> {
            currentScale = (float) animation.getAnimatedValue();
            setScaleRate(currentScale);
        });
        xAnimator.addUpdateListener(animation -> setTranslationX((float) animation.getAnimatedValue()));
        yAnimator.addUpdateListener(animation -> setTranslationY((float) animation.getAnimatedValue()));
        set.playTogether(scaleAnimator, xAnimator, yAnimator);
        set.setDuration(duration);
        set.setInterpolator(new DecelerateInterpolator());
        set.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                currentScale = toScale;
                setScaleRate(toScale);
                applyScaleLayout();
                if (toScale == DEFAULT_SCALE) {
                    setTranslationX(0f);
                    setTranslationY(0f);
                } else {
                    setTranslationX(getPositionX(toX));
                    setTranslationY(getPositionY(toY));
                }
                isZooming = false;
            }

            @Override public void onAnimationCancel(android.animation.Animator animation) {
                isZooming = false;
            }
        });
        activeAnimator = set;
        set.start();
    }

    private AnimatorSet activeAnimator;

    private void cancelZoomAnimation() {
        if (activeAnimator != null) {
            activeAnimator.cancel();
            activeAnimator = null;
        }
    }

    private void doubleTapZoom(MotionEvent ev) {
        if (isZooming || !doubleTapZoomEnabled) return;
        if (Math.abs(currentScale - DEFAULT_SCALE) > 0.01f) {
            animateZoom(currentScale, DEFAULT_SCALE, getTranslationX(), 0f, getTranslationY(), 0f, DOUBLE_TAP_DURATION);
        } else {
            float target = Math.min(MAX_SCALE, DOUBLE_TAP_SCALE);
            float factor = target / DEFAULT_SCALE;
            float toX = ev.getX() - halfWidth - (ev.getX() - halfWidth) * factor;
            float toY = ev.getY() - halfHeight - (ev.getY() - halfHeight) * factor;
            currentScale = target;
            toX = getPositionX(toX);
            toY = getPositionY(toY);
            currentScale = DEFAULT_SCALE;
            animateZoom(DEFAULT_SCALE, target, 0f, toX, 0f, toY, DOUBLE_TAP_DURATION);
        }
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
            return MangaWebtoonRecyclerView.this.onReaderScaleBegin();
        }

        @Override public boolean onScale(ScaleGestureDetector detector) {
            return MangaWebtoonRecyclerView.this.onReaderScale(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
        }

        @Override public void onScaleEnd(ScaleGestureDetector detector) {
            MangaWebtoonRecyclerView.this.onReaderScaleEnd();
        }
    }

    private final Runnable singleTapFallbackRunnable = new Runnable() {
        @Override public void run() {
            if (validSingleTapCandidate && pendingSingleTapDownTime == tapDownTime) dispatchReaderSingleTap(pendingSingleTapX, pendingSingleTapY);
        }
    };

    private void dispatchReaderSingleTap(float x, float y) {
        if (SystemClock.uptimeMillis() < suppressSingleTapUntil) {
            validSingleTapCandidate = false;
            return;
        }
        boolean scrollIdle = getScrollState() == SCROLL_STATE_IDLE;
        if (validSingleTapCandidate && lastTapDuration <= SINGLE_TAP_MAX_DURATION && (!tapDuringManualScroll || scrollIdle) && !isZoomDragging && (!isManuallyScrolling || scrollIdle)) {
            if (singleTapListener != null) singleTapListener.onSingleTap(x, y, getWidth(), getHeight());
            else performClick();
        }
        validSingleTapCandidate = false;
        removeCallbacks(singleTapFallbackRunnable);
    }

    private class TapListener extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onDown(MotionEvent e) {
            return true;
        }

        @Override public boolean onSingleTapConfirmed(MotionEvent e) {
            dispatchReaderSingleTap(e.getX(), e.getY());
            return true;
        }

        @Override public boolean onDoubleTap(MotionEvent e) {
            validSingleTapCandidate = false;
            removeCallbacks(singleTapFallbackRunnable);
            suppressSingleTapUntil = SystemClock.uptimeMillis() + 700L;
            if (!doubleTapZoomEnabled) return false;
            isDoubleTapping = true;
            return true;
        }

        @Override public boolean onDoubleTapEvent(MotionEvent e) {
            validSingleTapCandidate = false;
            removeCallbacks(singleTapFallbackRunnable);
            suppressSingleTapUntil = SystemClock.uptimeMillis() + 700L;
            return false;
        }

        @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            validSingleTapCandidate = false;
            removeCallbacks(singleTapFallbackRunnable);
            return false;
        }

        @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            if (externalGestureFrameEnabled) {
                validSingleTapCandidate = false;
                isManuallyScrolling = true;
                return false;
            }
            return zoomFling(Math.round(velocityX), Math.round(velocityY));
        }

        @Override public void onLongPress(MotionEvent e) {
            validSingleTapCandidate = false;
            removeCallbacks(singleTapFallbackRunnable);
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
    }
}
