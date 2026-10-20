package com.ansub.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.service.dreams.DreamService;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

// Screensaver: green code rain, with the time small in the bottom right corner. Pick it under
// Settings > Screensaver ("Matrix"), or: settings put secure screensaver_components com.ansub.tvhome/.MatrixDream
public class MatrixDream extends DreamService {

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setContentView(new Rain(this));
    }

    // The rain is drawn into a half-size bitmap and scaled up, so a slow TV only fills a quarter of the
    // pixels each frame. Each frame darkens what is there a little and draws one new glyph per column,
    // which leaves the fading trails.
    static final class Rain extends View {
        private static final String GLYPHS =
                "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ0123456789Z:.=*+-<>|";
        private static final int FRAME_MS = 50;
        private final Random rnd = new Random();
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fade = new Paint();
        private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint clock = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.ROOT);
        private Bitmap frame;
        private Canvas canvas;
        private int cell;
        private float[] drops;
        private final Rect dst = new Rect();

        Rain(Context c) {
            super(c);
            setBackgroundColor(Color.BLACK);
            glyph.setTypeface(Typeface.MONOSPACE);
            fade.setColor(Color.argb(38, 0, 0, 0));
            clock.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            clock.setColor(MainActivity.PHOSPHOR);
            clock.setTextAlign(Paint.Align.RIGHT);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            if (w <= 0 || h <= 0) return;
            frame = Bitmap.createBitmap(Math.max(1, w / 2), Math.max(1, h / 2), Bitmap.Config.ARGB_8888);
            canvas = new Canvas(frame);
            canvas.drawColor(Color.BLACK);
            cell = Math.max(8, frame.getHeight() / 36);
            glyph.setTextSize(cell);
            drops = new float[frame.getWidth() / cell + 1];
            for (int i = 0; i < drops.length; i++) drops[i] = -rnd.nextInt(40);
            clock.setTextSize(h / 18f);
            dst.set(0, 0, w, h);
        }

        @Override
        protected void onDraw(Canvas out) {
            if (frame == null) return;
            canvas.drawRect(0, 0, frame.getWidth(), frame.getHeight(), fade);
            int rows = frame.getHeight() / cell;
            for (int i = 0; i < drops.length; i++) {
                if (drops[i] >= 0) {
                    // The leading glyph is near white, the trail behind it the usual green.
                    glyph.setColor(rnd.nextInt(10) == 0 ? 0xFFD8FFE4 : MainActivity.PHOSPHOR);
                    char ch = GLYPHS.charAt(rnd.nextInt(GLYPHS.length()));
                    canvas.drawText(String.valueOf(ch), i * cell, (drops[i] + 1) * cell, glyph);
                }
                drops[i] += 1;
                if (drops[i] > rows && rnd.nextInt(40) == 0) drops[i] = -rnd.nextInt(20);
            }
            out.drawBitmap(frame, null, dst, blit);
            float pad = getHeight() / 24f;
            out.drawText(time.format(new Date()), getWidth() - pad, getHeight() - pad, clock);
            postInvalidateDelayed(FRAME_MS);
        }
    }
}
