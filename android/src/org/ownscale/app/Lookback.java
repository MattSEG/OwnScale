package org.ownscale.app;

/** A transparent scenario, not a measurement of historical body composition. */
final class Lookback {
  static Double estimate(
      double weight, double referenceWeight, double anchorPercent, double fatFraction) {
    if (!Double.isFinite(weight)
        || !Double.isFinite(referenceWeight)
        || !Double.isFinite(anchorPercent)
        || !Double.isFinite(fatFraction)
        || weight <= 0
        || referenceWeight <= 0
        || anchorPercent <= 0
        || anchorPercent >= 100
        || fatFraction < 0
        || fatFraction > 1) return null;
    double fat = referenceWeight * anchorPercent / 100 + fatFraction * (weight - referenceWeight);
    double result = 100 * fat / weight;
    return result > 0 && result < 100 ? result : null;
  }
}
