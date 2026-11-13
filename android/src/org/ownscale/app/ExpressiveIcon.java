package org.ownscale.app;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Small code-native symbols, with no font or network dependency. */
final class ExpressiveIcon extends View {
  final int kind, color;
  final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

  ExpressiveIcon(Context context, int kind, int color) {
    super(context);
    this.kind = kind;
    this.color = color;
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  @Override
  protected void onDraw(Canvas c) {
    super.onDraw(c);
    c.save();
    c.scale(getWidth() / 24f, getHeight() / 24f);
    p.setColor(color);
    p.setStrokeWidth(1.8f);
    p.setStrokeCap(Paint.Cap.ROUND);
    p.setStrokeJoin(Paint.Join.ROUND);
    p.setStyle(Paint.Style.STROKE);
    Path path = new Path();
    if (kind == 0) {
      path.moveTo(3, 10);
      path.lineTo(12, 3);
      path.lineTo(21, 10);
      path.moveTo(5, 9);
      path.lineTo(5, 21);
      path.lineTo(10, 21);
      path.lineTo(10, 14);
      path.lineTo(14, 14);
      path.lineTo(14, 21);
      path.lineTo(19, 21);
      path.lineTo(19, 9);
      c.drawPath(path, p);
    } else if (kind == 1) {
      path.moveTo(3, 4);
      path.lineTo(3, 20);
      path.lineTo(21, 20);
      path.moveTo(6, 15);
      path.lineTo(10, 10);
      path.lineTo(14, 13);
      path.lineTo(21, 5);
      c.drawPath(path, p);
    } else if (kind == 2) {
      c.drawArc(4, 4, 21, 21, -140, 290, false, p);
      path.moveTo(3, 3);
      path.lineTo(3, 9);
      path.lineTo(9, 9);
      path.moveTo(12, 7);
      path.lineTo(12, 13);
      path.lineTo(16, 15);
      c.drawPath(path, p);
    } else if (kind == 3) {
      for (int y = 6; y <= 18; y += 6) c.drawLine(3, y, 21, y, p);
      p.setStyle(Paint.Style.FILL);
      c.drawCircle(8, 6, 3, p);
      c.drawCircle(16, 12, 3, p);
      c.drawCircle(10, 18, 3, p);
    } else {
      // A scalloped bloom framing a scale dial: decorative, never a progress score.
      p.setStyle(Paint.Style.FILL);
      for (int i = 0; i < 12; i++) {
        double angle = i * Math.PI / 6;
        c.drawCircle(
            12 + (float) Math.cos(angle) * 6.5f, 12 + (float) Math.sin(angle) * 6.5f, 4, p);
      }
      c.drawCircle(12, 12, 8, p);
      p.setColor(Color.parseColor("#EADDFF"));
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(1.1f);
      c.drawRoundRect(6, 7, 18, 17, 3, 3, p);
      c.drawArc(9, 9, 15, 15, 200, 140, false, p);
      c.drawLine(12, 12, 14, 10, p);
    }
    c.restore();
  }
}
