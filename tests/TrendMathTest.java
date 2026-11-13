package org.ownscale.app;

import java.time.*;
import java.util.*;

public class TrendMathTest {
  static TrendMath.Point p(String day, double v) {
    return new TrendMath.Point(Instant.parse(day + "T12:00:00Z").toEpochMilli(), v);
  }

  static void eq(double a, double b) {
    if (Math.abs(a - b) > 1e-8) throw new AssertionError(a + " != " + b);
  }

  public static void main(String[] args) {
    List<TrendMath.Point> points =
        Arrays.asList(
            p("2026-01-01", 80), p("2026-01-01", 100), p("2026-01-02", 100), p("2026-01-08", 120));
    List<TrendMath.Point> result = TrendMath.smooth(points, ZoneOffset.UTC);
    eq(result.get(2).value, 95);
    eq(result.get(3).value, 110);
    eq(TrendMath.average(points, LocalDate.parse("2026-01-08"), 7, ZoneOffset.UTC), 110);
    if (TrendMath.average(points, LocalDate.parse("2026-02-08"), 7, ZoneOffset.UTC) != null)
      throw new AssertionError();
    if (!TrendMath.smooth(Collections.emptyList(), ZoneOffset.UTC).isEmpty())
      throw new AssertionError();
    System.out.println(
        "PASS: calendar-day weighting, 7-day boundaries, empty ranges; repeated same-day weigh-ins"
            + " do not bias smoothing.");
  }
}
