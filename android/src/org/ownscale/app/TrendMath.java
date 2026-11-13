package org.ownscale.app;

import java.time.*;
import java.util.*;

final class TrendMath {
  static final class Point {
    final long time;
    final double value;
    final boolean legacy;

    Point(long time, double value) {
      this(time, value, false);
    }

    Point(long time, double value, boolean legacy) {
      this.time = time;
      this.value = value;
      this.legacy = legacy;
    }
  }

  // Each calendar day carries equal weight, even if it has multiple weigh-ins.
  static List<Point> smooth(List<Point> points, ZoneId zone) {
    List<Point> result = new ArrayList<>();
    TreeMap<LocalDate, double[]> daily = new TreeMap<>();
    for (Point p : points) {
      LocalDate d = Instant.ofEpochMilli(p.time).atZone(zone).toLocalDate();
      double[] a = daily.get(d);
      if (a == null) {
        a = new double[2];
        daily.put(d, a);
      }
      a[0] += p.value;
      a[1]++;
    }
    for (Point p : points) {
      LocalDate day = Instant.ofEpochMilli(p.time).atZone(zone).toLocalDate();
      double sum = 0;
      int n = 0;
      for (double[] a : daily.subMap(day.minusDays(6), true, day, true).values()) {
        sum += a[0] / a[1];
        n++;
      }
      result.add(new Point(p.time, sum / n));
    }
    return result;
  }

  static Double average(List<Point> points, LocalDate end, int days, ZoneId zone) {
    Map<LocalDate, double[]> daily = new HashMap<>();
    for (Point p : points) {
      LocalDate d = Instant.ofEpochMilli(p.time).atZone(zone).toLocalDate();
      if (d.isAfter(end) || d.isBefore(end.minusDays(days - 1))) continue;
      double[] a = daily.get(d);
      if (a == null) {
        a = new double[2];
        daily.put(d, a);
      }
      a[0] += p.value;
      a[1]++;
    }
    if (daily.isEmpty()) return null;
    double total = 0;
    for (double[] a : daily.values()) total += a[0] / a[1];
    return total / daily.size();
  }
}
