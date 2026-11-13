package fr.evan.linea;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Colonne de lettres à droite de l'écran, avec effet "vague" sous le doigt (façon Niagara). */
public class AlphabetBar extends View {

    public interface Listener {
        /** letter = lettre sous le doigt, ou null si le doigt est sorti de la barre vers la gauche. */
        void onBarTouch(int action, String letter, float rawX, float rawY);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<String> letters = new ArrayList<>();
    private Listener listener;
    private int active = -1;
    private float touchY = -1;
    private boolean touching = false;
    private final float density;

    public AlphabetBar(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setColor(Color.WHITE);
        paint.setShadowLayer(4 * density, 0, density, 0x88000000);
    }

    private float dp(float v) { return v * density; }

    public void setListener(Listener l) { listener = l; }

    public void setLetters(List<String> l) {
        letters = l;
        invalidate();
    }

    private float slot() {
        if (letters.isEmpty()) return 0;
        float avail = getHeight() - getPaddingTop() - getPaddingBottom();
        return Math.min(avail / letters.size(), dp(26));
    }

    private float top() {
        float total = slot() * letters.size();
        return getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom() - total) / 2f;
    }

    private int indexAt(float y) {
        float s = slot();
        if (s <= 0) return -1;
        int i = (int) ((y - top()) / s);
        return Math.max(0, Math.min(letters.size() - 1, i));
    }

    @Override
    protected void onDraw(Canvas c) {
        if (letters.isEmpty()) return;
        float s = slot();
        float t = top();
        float base = dp(11);
        float cx = getWidth() / 2f;
        for (int i = 0; i < letters.size(); i++) {
            float cy = t + s * (i + 0.5f);
            float shift = 0, scale = 1f;
            int alpha = 190;
            if (touching) {
                float d = Math.abs(cy - touchY) / (s * 3.5f);
                if (d < 1f) {
                    float k = (float) Math.cos(d * Math.PI / 2); // courbe douce
                    shift = -dp(30) * k;
                    scale = 1f + 0.9f * k;
                }
                alpha = (i == active) ? 255 : 140;
            }
            paint.setTextSize(base * scale);
            paint.setAlpha(alpha);
            c.drawText(letters.get(i), cx + shift, cy + paint.getTextSize() / 3f, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (letters.isEmpty()) return false;
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                touching = true;
                // fallthrough
            case MotionEvent.ACTION_MOVE: {
                touchY = e.getY();
                String letter = null;
                if (e.getX() >= -dp(14)) {
                    active = indexAt(touchY);
                    letter = letters.get(active);
                }
                invalidate();
                if (listener != null) listener.onBarTouch(action, letter, e.getRawX(), e.getRawY());
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                String letter = null;
                if (e.getX() >= -dp(14) && active >= 0) letter = letters.get(active);
                touching = false;
                active = -1;
                invalidate();
                if (listener != null) listener.onBarTouch(action, letter, e.getRawX(), e.getRawY());
                return true;
            }
        }
        return true;
    }
}
