package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Small caps labels over the rows that start a section: Jump back in, the trending rows, All apps. */
final class SectionDecoration extends RecyclerView.ItemDecoration {

    private final AppAdapter adapter;
    private final int columns;
    private final float density;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    SectionDecoration(AppAdapter adapter, int columns, float density, Typeface font) {
        this.adapter = adapter;
        this.columns = columns;
        this.density = density;
        text.setColor(0x99FFFFFF);
        text.setTextSize(13 * density);
        text.setLetterSpacing(0.2f);
        text.setTypeface(font);
    }

    /** The label for the row this position starts, or null. */
    private String labelFor(int pos) {
        return adapter.rowLabel(pos);
    }

    @Override
    public void getItemOffsets(@NonNull Rect out, @NonNull View view, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos < 0) return;
        int rowStart = pos - pos % columns;
        if (labelFor(rowStart) != null) out.top += (int) (30 * density);
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            String label = pos < 0 ? null : labelFor(pos);
            if (label == null) continue;
            View card = child.findViewById(R.id.card);
            float x = child.getLeft() + (card != null ? card.getLeft() : 0);
            c.drawText(label.toUpperCase(java.util.Locale.ROOT), x, child.getTop() - 4 * density, text);
        }
    }
}
