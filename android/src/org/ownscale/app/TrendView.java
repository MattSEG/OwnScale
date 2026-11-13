package org.ownscale.app;

import android.graphics.*;
import android.view.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

final class TrendView extends View {
  final MainActivity a;
  final List<TrendMath.Point> data, smooth, original;
  final String unit;
  final int color;
  final Paint paint = new Paint(3);
  int selected = -1;
  float left, right, top, bottom;
  double min, max;
  long first, last;

  TrendView(
      MainActivity a,
      List<TrendMath.Point> points,
      List<TrendMath.Point> smoothing,
      List<TrendMath.Point> comparison,
      String unit,
      int color) {
    super(a);
    this.a = a;
    data = points;
    smooth = smoothing;
    original = comparison;
    this.unit = unit;
    this.color = color;
    setContentDescription("Trend chart. Tap a reading for its date and value.");
    setMinimumHeight(a.dp(240));
  }

  float x(long time) {
    return left + (right - left) * (time - first) / (float) Math.max(1, last - first);
  }

  float y(double v) {
    return bottom - (float) ((v - min) / (max - min)) * (bottom - top);
  }

  @Override
  protected void onDraw(Canvas c) {
    super.onDraw(c);
    left = a.dp(43);
    right = getWidth() - a.dp(12);
    top = a.dp(44);
    bottom = getHeight() - a.dp(32);
    paint.setTypeface(Typeface.create("sans-serif", 0));
    paint.setTextSize(a.dp(10));
    if (data.isEmpty()) {
      paint.setColor(a.muted);
      paint.setTextAlign(Paint.Align.CENTER);
      c.drawText("No readings in this range", getWidth() / 2f, getHeight() / 2f, paint);
      return;
    }
    first = data.get(0).time;
    last = data.get(data.size() - 1).time;
    if (first == last) {
      first -= 43200000;
      last += 43200000;
    }
    min = Double.POSITIVE_INFINITY;
    max = -min;
    for (List<TrendMath.Point> list : Arrays.asList(data, smooth, original))
      for (TrendMath.Point p : list) {
        min = Math.min(min, p.value);
        max = Math.max(max, p.value);
      }
    double pad = Math.max((max - min) * .18, .5);
    min -= pad;
    max += pad;
    paint.setTextAlign(Paint.Align.RIGHT);
    for (int i = 0; i <= 3; i++) {
      double value = min + (max - min) * i / 3;
      float yy = y(value);
      paint.setColor(a.border);
      paint.setStrokeWidth(a.dp(1));
      c.drawLine(left, yy, right, yy, paint);
      paint.setColor(a.muted);
      c.drawText(String.format(Locale.US, "%.1f", value), left - a.dp(8), yy + a.dp(3), paint);
    }
    // A restrained tonal wash adds depth without changing the data path.
    if (!smooth.isEmpty()) {
      List<TrendMath.Point> legacy = new ArrayList<>(), own = new ArrayList<>();
      for (TrendMath.Point point : smooth) (point.legacy ? legacy : own).add(point);
      for (List<TrendMath.Point> series : Arrays.asList(legacy, own)) {
        if (series.size() < 2) continue;
        Path area = new Path();
        area.moveTo(x(series.get(0).time), bottom);
        for (TrendMath.Point point : series) area.lineTo(x(point.time), y(point.value));
        area.lineTo(x(series.get(series.size() - 1).time), bottom);
        area.close();
        paint.setShader(
            new LinearGradient(
                0,
                top,
                0,
                bottom,
                new int[] {(a.accent & 0xffffff) | 0x38000000, a.accent & 0xffffff},
                null,
                Shader.TileMode.CLAMP));
        c.drawPath(area, paint);
        paint.setShader(null);
      }
    }
    drawLine(c, data, color, 1.4f, false);
    for (TrendMath.Point p : data) {
      paint.setColor(color);
      paint.setStyle(p.legacy ? Paint.Style.STROKE : Paint.Style.FILL);
      paint.setStrokeWidth(a.dp(1.3f));
      c.drawCircle(x(p.time), y(p.value), a.dp(2.7f), paint);
      paint.setStyle(Paint.Style.FILL);
    }
    if (!original.isEmpty()) drawLine(c, original, a.muted, 1, true);
    if (!smooth.isEmpty()) drawLine(c, smooth, a.accent, 3, false);
    DateTimeFormatter df = DateTimeFormatter.ofPattern("MMM d").withZone(ZoneId.systemDefault());
    paint.setTextSize(a.dp(10));
    paint.setColor(a.muted);
    paint.setTextAlign(Paint.Align.LEFT);
    c.drawText(df.format(Instant.ofEpochMilli(data.get(0).time)), left, bottom + a.dp(23), paint);
    paint.setTextAlign(Paint.Align.RIGHT);
    c.drawText(
        df.format(Instant.ofEpochMilli(data.get(data.size() - 1).time)),
        right,
        bottom + a.dp(23),
        paint);
    if (selected >= 0 && selected < data.size()) {
      TrendMath.Point p = data.get(selected);
      paint.setColor(color);
      paint.setStrokeWidth(a.dp(1));
      c.drawLine(x(p.time), top, x(p.time), bottom, paint);
      c.drawCircle(x(p.time), y(p.value), a.dp(5), paint);
      paint.setColor(a.ink);
      paint.setTextSize(a.dp(12));
      paint.setTextAlign(Paint.Align.LEFT);
      c.drawText(
          df.format(Instant.ofEpochMilli(p.time))
              + "  ·  "
              + String.format(Locale.US, "%.2f %s", p.value, unit),
          left,
          a.dp(23),
          paint);
    }
  }

  void drawLine(Canvas c, List<TrendMath.Point> points, int color, float width, boolean dashed) {
    if (points.isEmpty()) return;
    if (points == data || points == smooth) {
      List<TrendMath.Point> legacy = new ArrayList<>(), own = new ArrayList<>();
      for (TrendMath.Point p : points) (p.legacy ? legacy : own).add(p);
      drawLine(c, legacy, color, width, points == data);
      drawLine(c, own, color, width, false);
      return;
    }
    Path path = new Path();
    boolean firstPoint = true;
    for (TrendMath.Point p : points) {
      if (firstPoint) {
        path.moveTo(x(p.time), y(p.value));
        firstPoint = false;
      } else path.lineTo(x(p.time), y(p.value));
    }
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(a.dp(width));
    paint.setStrokeJoin(Paint.Join.ROUND);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setColor(color);
    paint.setPathEffect(dashed ? new DashPathEffect(new float[] {a.dp(4), a.dp(4)}, 0) : null);
    c.drawPath(path, paint);
    paint.setPathEffect(null);
    paint.setStyle(Paint.Style.FILL);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (event.getAction() == MotionEvent.ACTION_UP && !data.isEmpty()) {
      float best = Float.MAX_VALUE;
      for (int i = 0; i < data.size(); i++) {
        float distance = Math.abs(x(data.get(i).time) - event.getX());
        if (distance < best) {
          best = distance;
          selected = i;
        }
      }
      invalidate();
      performClick();
    }
    return true;
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }
}
