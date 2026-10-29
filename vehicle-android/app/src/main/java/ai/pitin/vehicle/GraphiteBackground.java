package ai.pitin.vehicle;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Static material from the supplied hybrid composition; no independent animation clock. */
final class GraphiteBackground extends Drawable {
    private final Paint ground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grain = new Paint();
    GraphiteBackground(Context context) {
        Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.graphite_grain);
        if (bitmap != null) grain.setShader(new BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        grain.setAlpha(9);
    }
    @Override protected void onBoundsChange(Rect bounds) {
        ground.setShader(new RadialGradient(bounds.width() * .52f, bounds.height() * .4f,
                Math.max(1, Math.max(bounds.width(), bounds.height()) * .85f),
                new int[]{0xff18181a, 0xff131314, 0xff0e0e0f}, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
    }
    @Override public void draw(Canvas canvas) {
        canvas.drawRect(getBounds(), ground);
        if (grain.getShader() != null) canvas.drawRect(getBounds(), grain);
    }
    @Override public void setAlpha(int alpha) { ground.setAlpha(alpha); grain.setAlpha(Math.round(alpha * .035f)); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { ground.setColorFilter(filter); grain.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
