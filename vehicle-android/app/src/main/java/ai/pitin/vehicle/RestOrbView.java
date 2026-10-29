package ai.pitin.vehicle;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/** Original Grok curve material plus one native point, both governed by the existing session. */
final class RestOrbView extends View {
    // Frame0, text-free source crop (380,150,756,360). See motion-handoff asset audit.
    private static final float[] SOURCE_CURVE = {
        54,292,70,292,95,292,120,292,145,292,170,289,195,284,220,276,245,266,
        270,253,295,238,320,221,345,202,370,182,395,163,420,143,445,125,470,109,
        495,95,520,83,545,73,570,65,595,60,620,56,645,55,670,55,695,55,720,55,727,55
    };
    private final RestSession session;
    private final Bitmap material;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final RectF art = new RectF();
    private final float[] point = new float[2];
    private Shader horizontalFade;
    private Shader verticalFade;
    private boolean naturalRestMode;

    RestOrbView(Context context, RestSession session) {
        super(context); this.session = session;
        material = BitmapFactory.decodeResource(context.getResources(), R.drawable.graphite_motion_plate);
        edge.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    void setNaturalRestMode(boolean naturalRestMode) {
        this.naturalRestMode = naturalRestMode;
    }
    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        float scale = Math.max(0, Math.min(width / 756f, height / 360f) * .96f);
        art.set((width - 756 * scale) / 2, (height - 360 * scale) / 2,
                (width + 756 * scale) / 2, (height + 360 * scale) / 2);
        path.reset();
        for (int i = 0; i < SOURCE_CURVE.length; i += 2) {
            float x = art.left + SOURCE_CURVE[i] * scale;
            float y = art.top + SOURCE_CURVE[i + 1] * scale;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        measure.setPath(path, false);
        if (scale > 0) {
            int[] colors = {Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT};
            float[] stops = {0, .04f, .96f, 1};
            horizontalFade = new LinearGradient(art.left, 0, art.right, 0, colors, stops, Shader.TileMode.CLAMP);
            verticalFade = new LinearGradient(0, art.top, 0, art.bottom, colors, stops, Shader.TileMode.CLAMP);
        }
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = SystemClock.elapsedRealtime();
        double expansion = naturalRestMode && session.state() == RestSession.State.ACTIVE
                ? (session.reducedMotion() ? 0.5 : 0.0)
                : session.expansion(now);
        if (art.isEmpty()) return;
        if (material != null) {
            int saved = canvas.saveLayer(art, null);
            paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE);
            canvas.drawBitmap(material, null, art, paint);
            edge.setShader(horizontalFade); canvas.drawRect(art, edge);
            edge.setShader(verticalFade); canvas.drawRect(art, edge);
            canvas.restoreToCount(saved);
        } else {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.25f));
            paint.setColor(GraphitePalette.PATH); canvas.drawPath(path, paint);
        }
        if (measure.getPosTan((float) expansion * measure.getLength(), point, null)) {
            paint.setStyle(Paint.Style.FILL); paint.setColor(GraphitePalette.TEXT);
            canvas.drawCircle(point[0], point[1], dp(4.5f), paint);
        }
        if (session.state() == RestSession.State.ACTIVE && !session.reducedMotion() && !naturalRestMode
                && session.segment(now) == RestSession.Segment.GUIDED) postInvalidateOnAnimation();
    }
    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
