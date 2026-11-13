package org.ownscale.app;

import java.util.*;

public class StabilityTest {
  static void close(double a, double b) {
    if (Math.abs(a - b) > 1e-9) throw new AssertionError(a + " != " + b);
  }

  public static void main(String[] args) {
    List<TrendMath.Point> repeat =
        Arrays.asList(new TrendMath.Point(0, 20.0), new TrendMath.Point(20 * 60000, 19.0));
    double value = Stability.filter(repeat, 72).get(1).value;
    if (value < 19.99 || value > 20.0) throw new AssertionError("Close repeat too influential");
    close(Stability.filter(repeat, 0).get(1).value, 19.0);
    List<TrendMath.Point> sustained =
        Arrays.asList(new TrendMath.Point(0, 20.0), new TrendMath.Point(72 * 3600000L, 19.0));
    close(Stability.filter(sustained, 72).get(1).value, 20.0 - (1 - Math.exp(-1)));
    close(
        Stability.filter(Arrays.asList(new TrendMath.Point(0, 20.0), new TrendMath.Point(0, 2)), 72)
            .get(1)
            .value,
        20.0);
    try {
      Stability.filter(repeat, -1);
      throw new AssertionError();
    } catch (IllegalArgumentException expected) {
    }
    System.out.println(
        "PASS: 20-minute repeat stays near 20.0; sustained 3-day change responds gradually; off"
            + " mode, same-time repeats and invalid response tested.");
  }
}
