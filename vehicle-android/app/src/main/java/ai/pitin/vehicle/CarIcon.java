package ai.pitin.vehicle;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import java.util.HashMap;
import java.util.Map;

/** One set of 1.5px line icons on a 24-unit grid (spec 6). Replaces every symbol-character icon. */
final class CarIcon extends Drawable {
    static final String PHONE = "M9.5 3.5h5a2.5 2.5 0 0 1 2.5 2.5v12a2.5 2.5 0 0 1-2.5 2.5h-5a2.5 2.5 0 0 1-2.5-2.5V6a2.5 2.5 0 0 1 2.5-2.5zM11 17.5h2";
    static final String FEATURES = "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0M12 3.5v2.5M12 18v2.5M3.5 12H6M18 12h2.5M6 6l1.8 1.8M16.2 16.2 18 18M6 18l1.8-1.8M16.2 7.8 18 6";
    static final String MUTE = "M4 9.5v5h3.5L12 18.5v-13L7.5 9.5zM16 9.5l4 5M20 9.5l-4 5";
    static final String BACK = "M15 6l-6 6 6 6";
    static final String NEXT = "M9 6l6 6-6 6";
    static final String CHECK = "M5 12.5l4.5 4.5L19 7.5";
    static final String ALERT = "M12 7.5v5.5M12 16.5h.01";
    static final String COOL = "M3 9h11a3 3 0 1 0-3-3M3 15h15a3 3 0 1 1-3 3";
    static final String MOON = "M15 4.5a7.5 7.5 0 1 0 4.5 13.5A8 8 0 0 1 15 4.5z";
    static final String SUNRISE = "M4 17h16M7 17a5 5 0 0 1 10 0M12 7v2M6 10l1.4 1.4M18 10l-1.4 1.4";
    static final String TEMP = "M10 14.5V5a2 2 0 1 1 4 0v9.5a4 4 0 1 1-4 0z";
    static final String FAN = "M12 12c0-3 1-6 4-6 2 0 3 1.5 3 3 0 3-4 3-7 3zm0 0c3 0 6 1 6 4 0 2-1.5 3-3 3-3 0-3-4-3-7zm0 0c0 3-1 6-4 6-2 0-3-1.5-3-3 0-3 4-3 7-3zm0 0c-3 0-6-1-6-4 0-2 1.5-3 3-3 3 0 3 4 3 7z";
    static final String SEAT = "M7 4.5v9.5h9l2 5.5M7 14l-1 5.5M10 4.5H7";
    static final String CLIMATE = "M12 3v18M4.2 7.5l15.6 9M4.2 16.5l15.6-9M9 4.5l3 2 3-2M9 19.5l3-2 3 2";
    static final String LIGHT = "M9 17.5h6M10 20.5h4M12 3.5a5.5 5.5 0 0 1 3.5 9.7c-.6.5-1 1.2-1 2v.3h-5v-.3c0-.8-.4-1.5-1-2A5.5 5.5 0 0 1 12 3.5z";
    static final String SOUND_OFF = MUTE;
    static final String REREAD = "M20 11a8 8 0 1 0-2.3 5.7M20 5v6h-6";
    static final String MINUS = "M6 12h12";
    static final String PLUS = "M12 6v12M6 12h12";
    static final String DIR_KEEP = "M6 12h12";
    static final String DIR_UP = "M12 19V7M7 11l5-5 5 5";
    static final String DIR_DOWN = "M12 5v12M7 13l5 5 5-5";
    static final String DIR_BOTH = "M12 4v16M8 8l4-4 4 4M8 16l4 4 4-4";
    static final String VOICE = "M5 10v4M9 7v10M13 4v16M17 8v8M21 11v2";
    static final String LINK = "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1";
    static final String CAR = "M5 16.5V11l2-4.5h10L19 11v5.5M3.5 16.5h17M6 16.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0M15 16.5a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0M5 11h14";

    private static final Map<String, Path> CACHE = new HashMap<>();

    private final Path source;
    private final Path scaled = new Path();
    private final Matrix matrix = new Matrix();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float strokeUnits;

    CarIcon(String data, int color) { this(data, color, 1.5f); }

    CarIcon(String data, int color, float strokeUnits) {
        Path cached = CACHE.get(data);
        if (cached == null) { cached = SvgPath.parse(data); CACHE.put(data, cached); }
        source = cached;
        this.strokeUnits = strokeUnits;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
    }

    void setColor(int color) { paint.setColor(color); invalidateSelf(); }

    @Override protected void onBoundsChange(Rect bounds) {
        float s = Math.min(bounds.width(), bounds.height()) / 24f;
        matrix.setScale(s, s);
        matrix.postTranslate(bounds.left + (bounds.width() - 24 * s) / 2f, bounds.top + (bounds.height() - 24 * s) / 2f);
        source.transform(matrix, scaled);
        paint.setStrokeWidth(Math.max(1f, strokeUnits * s));
    }

    @Override public void draw(Canvas canvas) { canvas.drawPath(scaled, paint); }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
