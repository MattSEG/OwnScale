package org.ownscale.app;

import java.util.*;

/** Elapsed-time exponential response; close repeats cannot reset the displayed estimate. */
final class Stability {
  static List<TrendMath.Point> filter(List<TrendMath.Point> input, double hours) {
    if (!Double.isFinite(hours) || hours < 0)
      throw new IllegalArgumentException("Invalid response time");
    List<TrendMath.Point> out = new ArrayList<>();
    if (input.isEmpty()) return out;
    double value = input.get(0).value;
    long previous = input.get(0).time;
    for (TrendMath.Point point : input) {
      if (!Double.isFinite(point.value) || point.time < previous)
        throw new IllegalArgumentException("Invalid or unsorted readings");
      double alpha = hours == 0 ? 1 : -Math.expm1(-(point.time - previous) / (hours * 3600000.0));
      value += alpha * (point.value - value);
      out.add(new TrendMath.Point(point.time, value, point.legacy));
      previous = point.time;
    }
    return out;
  }
}
