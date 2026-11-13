package org.ownscale.app;

public class LookbackTest {
  static void near(Double actual, double expected) {
    if (actual == null || Math.abs(actual - expected) > 1e-9)
      throw new AssertionError(actual + " != " + expected);
  }

  public static void main(String[] args) {
    near(Lookback.estimate(80, 80, 20, .75), 20);
    near(Lookback.estimate(90, 80, 20, .75), 100 * 23.5 / 90);
    near(Lookback.estimate(70, 80, 20, .75), 100 * 8.5 / 70);
    near(Lookback.estimate(90, 80, 20, 0), 100 * 16.0 / 90);
    near(Lookback.estimate(90, 80, 20, 1), 100 * 26.0 / 90);
    if (Lookback.estimate(50, 80, 20, 1) != null
        || Lookback.estimate(90, 80, 20, 1.1) != null
        || Lookback.estimate(Double.NaN, 80, 20, .75) != null)
      throw new AssertionError("Invalid scenario");
    System.out.println(
        "PASS: synthetic lookback anchor, gain/loss, allocations and invalid scenarios");
  }
}
