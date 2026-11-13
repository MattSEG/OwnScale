package org.ownscale.app;

/**
 * Experimental model only: not validated against this device or this person. Coefficients
 * documented in Blue2Scale BLE_SCALE_PROTOCOL.md, section 8. The personal anchor is an assumption,
 * never a measured reference value.
 */
public final class BodyFatModel {
  public static final String VERSION = "blue2scale_tbw_personal_offset_v1_experimental";

  public static double wholeBody(double[] segments) {
    if (segments == null || segments.length != 5)
      throw new IllegalArgumentException("Five impedance segments required");
    for (double v : segments)
      if (!Double.isFinite(v) || v <= 0) throw new IllegalArgumentException("Invalid impedance");
    return (segments[0] + segments[1] + 2 * segments[2] + segments[3] + segments[4]) / 2;
  }

  public static double unadjusted(double kg, double heightCm, int sex, double[] segments) {
    if (!Double.isFinite(kg)
        || kg <= 0
        || !Double.isFinite(heightCm)
        || heightCm < 100
        || heightCm > 250
        || (sex != 0 && sex != 1)) throw new IllegalArgumentException("Invalid model inputs");
    double index = heightCm * heightCm / wholeBody(segments);
    double water = sex == 1 ? .464 * index + .190 * kg + 3.03 : .390 * index + .107 * kg + 8.30;
    return 100 * (kg - water / .731) / kg;
  }

  public static double anchored(double currentModel, double baselineModel, double assumedBaseline) {
    if (!Double.isFinite(currentModel)
        || !Double.isFinite(baselineModel)
        || !Double.isFinite(assumedBaseline)
        || assumedBaseline <= 0
        || assumedBaseline >= 100) throw new IllegalArgumentException("Invalid baseline");
    double result = currentModel + assumedBaseline - baselineModel;
    if (result <= 0 || result >= 100)
      throw new IllegalArgumentException("Model result outside 0–100%; raw measurement retained");
    return result;
  }
}
