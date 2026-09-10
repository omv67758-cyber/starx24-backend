package com.arenax.tournament;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * Lightweight red particle layer for the dark ArenaX login screen.
 * It deliberately uses no external assets so it stays smooth and offline.
 */
public class SnowfallView extends View {
    private static final int FLAKE_COUNT = 34;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random(42L);
    private final Flake[] flakes = new Flake[FLAKE_COUNT];
    private long lastFrameNanos;

    public SnowfallView(Context context) {
        super(context);
        initialize();
    }

    public SnowfallView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public SnowfallView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize();
    }

    private void initialize() {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        paint.setColor(Color.rgb(255, 34, 58));
        for (int index = 0; index < FLAKE_COUNT; index++) {
            flakes[index] = new Flake(random);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        long now = System.nanoTime();
        float deltaSeconds = lastFrameNanos == 0L
                ? 0.016f
                : Math.min((now - lastFrameNanos) / 1_000_000_000f, 0.05f);
        lastFrameNanos = now;

        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            postInvalidateOnAnimation();
            return;
        }

        for (Flake flake : flakes) {
            flake.update(deltaSeconds, width, height);
            paint.setAlpha(flake.alpha);
            paint.setShadowLayer(flake.radius * 3.5f, 0f, 0f, Color.argb(flake.alpha, 255, 17, 40));
            canvas.drawCircle(flake.x, flake.y, flake.radius, paint);
        }
        paint.clearShadowLayer();
        postInvalidateOnAnimation();
    }

    private static final class Flake {
        private final float drift;
        private final float speed;
        private final float radius;
        private final int alpha;
        private float x;
        private float y;
        private float phase;

        private Flake(Random random) {
            drift = (random.nextFloat() - 0.5f) * 34f;
            speed = 22f + random.nextFloat() * 42f;
            radius = 1.3f + random.nextFloat() * 2.2f;
            alpha = 75 + random.nextInt(105);
            x = random.nextFloat() * 1200f;
            y = random.nextFloat() * 1800f;
            phase = random.nextFloat() * 6.28f;
        }

        private void update(float deltaSeconds, int width, int height) {
            y += speed * deltaSeconds;
            phase += deltaSeconds * 1.2f;
            x += (float) Math.sin(phase) * drift * deltaSeconds * 0.08f;
            if (y > height + 12f) {
                y = -12f;
                x = (x + width * 0.37f) % width;
            }
            if (x < -12f) {
                x = width + 12f;
            } else if (x > width + 12f) {
                x = -12f;
            }
        }
    }
}