package org.telegram.messenger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Choreographer;
import android.view.View;

import org.telegram.ui.ActionBar.Theme;

/**
 * FluxGram: настраиваемый M3E loading indicator (Canvas + Path, minSdk 21).
 * ~30 fps через Choreographer, рисуем только когда view показан.
 */
public class FluxLoadingIndicator extends View {

    public enum Style { SHAPE_MORPH, RING, WAVY_RING, DOTS, ORBIT, COMET, CLASSIC }

    public enum Size {
        SMALL(16), MEDIUM(28), LARGE(48);

        public final int dp;

        Size(int dp) {
            this.dp = dp;
        }
    }

    public static final String[] STYLE_NAMES = {"Морфинг форм", "Кольцо", "Волнистое кольцо", "Точки", "Орбита", "Комета", "Классика"};

    private static final Style[] STYLES = Style.values();
    private static final int PTS = 48;
    private static final int WAVES = 7;
    private static final int TAIL = 14;
    // круг, треугольник, квадрат, пятиугольник, цветок, солнце, овал
    private static final int[] SHAPE_N = {0, 3, 4, 5, 6, 8, 2};
    private static final float[] SHAPE_A = {0f, 0.22f, 0.15f, 0.14f, 0.20f, 0.13f, 0.18f};
    private static final float[][] SHAPE_COS = new float[SHAPE_N.length][PTS];
    private static final float[] COS_T = new float[PTS];
    private static final float[] SIN_T = new float[PTS];

    static {
        for (int i = 0; i < PTS; i++) {
            double th = 2.0 * Math.PI * i / PTS;
            COS_T[i] = (float) Math.cos(th);
            SIN_T[i] = (float) Math.sin(th);
            for (int s = 0; s < SHAPE_N.length; s++) {
                SHAPE_COS[s][i] = (float) Math.cos(SHAPE_N[s] * th);
            }
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float[] px = new float[PTS];
    private final float[] py = new float[PTS];

    private Style style = Style.SHAPE_MORPH;
    private Size size;
    private boolean indeterminate = true;
    private boolean hasColor;
    private int color;
    private float speed = 1f;
    private float target;
    private float shown;

    private boolean attached;
    private boolean running;
    private int skip;
    private long lastNanos;
    private double clock;
    private float arcStart;
    private float arcSweep;

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long nanos) {
            if (!running) {
                return;
            }
            Choreographer.getInstance().postFrameCallback(this);
            if (!isShown() || getVisibility() != VISIBLE) {
                lastNanos = 0;
                return;
            }
            skip ^= 1;
            if (skip == 1) {
                return;
            }
            if (lastNanos == 0) {
                lastNanos = nanos - 33000000L;
            }
            float dt = Math.min(100f, (nanos - lastNanos) / 1000000f);
            lastNanos = nanos;
            clock += dt * speed;
            if (shown != target) {
                shown += (target - shown) * (1f - (float) Math.exp(-dt / 90f));
                if (Math.abs(target - shown) < 0.001f) {
                    shown = target;
                }
            }
            invalidate();
        }
    };

    public FluxLoadingIndicator(Context ctx) {
        this(ctx, Size.MEDIUM);
    }

    public FluxLoadingIndicator(Context ctx, Size size) {
        super(ctx);
        this.size = size == null ? Size.MEDIUM : size;
        applySettings();
    }

    /** Стиль и скорость из настроек FluxGram. */
    public void applySettings() {
        int st = FluxStyle.num(FluxStyle.K_LOADER_STYLE, 0);
        style = STYLES[Math.max(0, Math.min(STYLES.length - 1, st))];
        setSpeed(FluxStyle.num(FluxStyle.K_LOADER_SPEED, 100) / 100f);
    }

    public void setStyle(Style s) {
        if (s != null && s != style) {
            style = s;
            invalidate();
        }
    }

    public Style getStyle() {
        return style;
    }

    public void setSize(Size s) {
        if (s != null && s != size) {
            size = s;
            requestLayout();
        }
    }

    public void setProgress(float p) {
        indeterminate = false;
        target = Math.max(0f, Math.min(1f, p));
        if (!running) {
            shown = target;
        }
        invalidate();
    }

    public void setIndeterminate() {
        indeterminate = true;
        invalidate();
    }

    public void setColor(int c) {
        hasColor = true;
        color = c;
        invalidate();
    }

    public void resetColor() {
        hasColor = false;
        invalidate();
    }

    public void setSpeed(float s) {
        speed = Math.max(0.25f, Math.min(3f, s));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int s = AndroidUtilities.dp(size.dp);
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        start();
        if (FluxStyle.bool(FluxStyle.K_SPRING_UI, true)) {
            post(new Runnable() {
                @Override
                public void run() {
                    FluxStyle.springScale(FluxLoadingIndicator.this, 0.6f, 480, getWidth() / 2f, getHeight() / 2f);
                }
            });
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            start();
        } else {
            stop();
        }
    }

    private void start() {
        if (running || !attached || getVisibility() != VISIBLE) {
            return;
        }
        running = true;
        lastNanos = 0;
        skip = 0;
        Choreographer.getInstance().postFrameCallback(frame);
    }

    private void stop() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(frame);
    }

    // ---------------------------------------------------------------- draw

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float cx = w / 2f;
        float cy = h / 2f;
        float half = Math.min(w, h) / 2f;
        int col = hasColor ? color : Theme.getColor(Theme.key_progressCircle);
        switch (style) {
            case SHAPE_MORPH:
                drawMorph(c, cx, cy, half, col);
                break;
            case RING:
                drawRing(c, cx, cy, half, col, false);
                break;
            case WAVY_RING:
                drawWavy(c, cx, cy, half, col);
                break;
            case DOTS:
                drawDots(c, cx, cy, half, col);
                break;
            case ORBIT:
                drawOrbit(c, cx, cy, half, col);
                break;
            case COMET:
                drawComet(c, cx, cy, half, col);
                break;
            default:
                drawRing(c, cx, cy, half, col, true);
                break;
        }
    }

    private static int alphaOf(int col, float a) {
        int al = (int) ((col >>> 24) * Math.max(0f, Math.min(255f, a)) / 255f);
        return (al << 24) | (col & 0xFFFFFF);
    }

    private static float spring(float t) {
        if (t <= 0f) {
            return 0f;
        }
        if (t >= 1f) {
            return 1f;
        }
        return (float) (1.0 - Math.exp(-7.0 * t) * Math.cos(14.0 * t));
    }

    private float phase(double period) {
        return (float) ((clock % period) / period);
    }

    private void computeArc(boolean classic) {
        if (indeterminate) {
            if (classic) {
                arcSweep = 270f;
                arcStart = -90f + (float) ((clock * 0.40) % 360.0);
            } else {
                float ph = phase(1400);
                arcSweep = 30f + 240f * (0.5f - 0.5f * (float) Math.cos(2.0 * Math.PI * ph));
                arcStart = -90f + (float) ((clock * 0.26) % 360.0);
            }
        } else {
            arcSweep = Math.max(3f, 360f * shown);
            arcStart = -90f;
        }
    }

    private void drawRing(Canvas c, float cx, float cy, float half, int col, boolean classic) {
        float stroke = Math.max(AndroidUtilities.dpf2(1.5f), half * 0.2f);
        float r = half - stroke / 2f - 0.5f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(stroke);
        if (!classic) {
            paint.setColor(alphaOf(col, 38));
            c.drawCircle(cx, cy, r, paint);
        }
        paint.setColor(col);
        computeArc(classic);
        rect.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(rect, arcStart, arcSweep, false, paint);
    }

    private void buildWavy(float cx, float cy, float a0, float sweep, float r, float amp, float ph) {
        path.rewind();
        int n = Math.max(2, (int) (sweep / 4f));
        for (int i = 0; i <= n; i++) {
            double a = Math.toRadians(a0 + sweep * i / n);
            float rr = r + amp * (float) Math.sin(WAVES * a + ph);
            float x = cx + rr * (float) Math.cos(a);
            float y = cy + rr * (float) Math.sin(a);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
    }

    private void drawWavy(Canvas c, float cx, float cy, float half, int col) {
        float stroke = Math.max(AndroidUtilities.dpf2(1.5f), half * 0.17f);
        float amp = stroke * 0.65f;
        float r = half - stroke / 2f - amp - 0.5f;
        float ph = (float) ((clock * 0.006) % (2.0 * Math.PI));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(stroke);
        buildWavy(cx, cy, 0f, 360f, r, amp, ph);
        paint.setColor(alphaOf(col, 38));
        c.drawPath(path, paint);
        computeArc(false);
        buildWavy(cx, cy, arcStart, arcSweep, r, amp, ph);
        paint.setColor(col);
        c.drawPath(path, paint);
    }

    private void drawMorph(Canvas c, float cx, float cy, float half, int col) {
        int k = SHAPE_N.length;
        int s0;
        int s1;
        float u;
        float scale;
        if (indeterminate) {
            double st = clock / 700.0;
            int idx = (int) st;
            s0 = idx % k;
            s1 = (s0 + 1) % k;
            float f = (float) (st - idx);
            u = spring(Math.min(1f, f / 0.6f));
            scale = 1f;
        } else {
            float pos = shown * (k - 1);
            int i = Math.max(0, Math.min(k - 2, (int) pos));
            s0 = i;
            s1 = i + 1;
            u = pos - i;
            scale = 0.55f + 0.45f * shown;
        }
        double rot = (clock * 0.09) % 360.0;
        float cr = (float) Math.cos(Math.toRadians(rot));
        float sr = (float) Math.sin(Math.toRadians(rot));
        float rad = half * 0.75f * scale;
        float a0 = SHAPE_A[s0];
        float a1 = SHAPE_A[s1];
        float[] c0 = SHAPE_COS[s0];
        float[] c1 = SHAPE_COS[s1];
        for (int i = 0; i < PTS; i++) {
            float rr = rad * ((1f - u) * (1f + a0 * c0[i]) + u * (1f + a1 * c1[i]));
            float x = COS_T[i] * cr - SIN_T[i] * sr;
            float y = SIN_T[i] * cr + COS_T[i] * sr;
            px[i] = cx + rr * x;
            py[i] = cy + rr * y;
        }
        path.rewind();
        path.moveTo((px[PTS - 1] + px[0]) / 2f, (py[PTS - 1] + py[0]) / 2f);
        for (int i = 0; i < PTS; i++) {
            int nx = (i + 1) % PTS;
            path.quadTo(px[i], py[i], (px[i] + px[nx]) / 2f, (py[i] + py[nx]) / 2f);
        }
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(col);
        c.drawPath(path, paint);
    }

    private void drawDots(Canvas c, float cx, float cy, float half, int col) {
        float gap = half * 0.62f;
        float dr = half * 0.2f;
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 3; i++) {
            float y = cy;
            float r;
            float a;
            if (indeterminate) {
                float t = (float) (((clock / 1000.0) - i * 0.15) % 1.0);
                if (t < 0f) {
                    t += 1f;
                }
                float b = t < 0.5f ? (float) Math.sin(2.0 * Math.PI * t) : 0f;
                y = cy - b * half * 0.35f;
                r = dr * (1f + 0.3f * b);
                a = 140f + 115f * b;
            } else {
                float kf = Math.max(0f, Math.min(1f, shown * 3f - i));
                r = dr * (0.8f + 0.2f * kf);
                a = 70f + 185f * kf;
            }
            paint.setColor(alphaOf(col, a));
            c.drawCircle(cx + (i - 1) * gap, y, r, paint);
        }
    }

    private void drawOrbit(Canvas c, float cx, float cy, float half, int col) {
        float orbit = half * 0.72f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, half * 0.06f));
        paint.setColor(alphaOf(col, 40));
        c.drawCircle(cx, cy, orbit, paint);
        double a = Math.toRadians((clock * 0.28) % 360.0);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(col);
        c.drawCircle(cx + orbit * (float) Math.cos(a), cy + orbit * (float) Math.sin(a), half * 0.17f, paint);
        paint.setColor(alphaOf(col, 150));
        c.drawCircle(cx - orbit * (float) Math.cos(a), cy - orbit * (float) Math.sin(a), half * 0.12f, paint);
        float cs = indeterminate ? 1f + 0.15f * (float) Math.sin(clock / 220.0) : 0.5f + 0.5f * shown;
        paint.setColor(col);
        c.drawCircle(cx, cy, half * 0.14f * cs, paint);
    }

    private void drawComet(Canvas c, float cx, float cy, float half, int col) {
        float orbit = half * 0.72f;
        double head;
        if (indeterminate) {
            head = Math.toRadians(-90.0 + (clock * 0.33) % 360.0);
        } else {
            head = Math.toRadians(-90.0 + 360.0 * shown);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, half * 0.06f));
            paint.setColor(alphaOf(col, 40));
            c.drawCircle(cx, cy, orbit, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        double step = Math.toRadians(9.0);
        for (int i = 0; i < TAIL; i++) {
            float f = i / (float) TAIL;
            double a = head - i * step;
            float al = 255f * (float) Math.pow(1f - f, 1.6);
            paint.setColor(alphaOf(col, al));
            c.drawCircle(cx + orbit * (float) Math.cos(a), cy + orbit * (float) Math.sin(a), half * 0.2f * (1f - 0.75f * f), paint);
        }
    }
}
