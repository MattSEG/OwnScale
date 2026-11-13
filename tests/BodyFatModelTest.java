import org.ownscale.app.BodyFatModel;

public class BodyFatModelTest {
  static void close(double a, double b) {
    if (Math.abs(a - b) > 0.000001) throw new AssertionError(a + " != " + b);
  }

  public static void main(String[] ignored) {
    double[] z = {331.9, 322.9, 27, 230.7, 232.1};
    close(BodyFatModel.wholeBody(z), 585.8);
    double base = BodyFatModel.unadjusted(93.05, 173, 1, z);
    close(BodyFatModel.anchored(base, base, 20.0), 20.0);
    close(BodyFatModel.anchored(base + 1, base, 20.0), 21.0);
    try {
      BodyFatModel.wholeBody(new double[] {1, 1, 0, 1, 1});
      throw new AssertionError();
    } catch (IllegalArgumentException expected) {
    }
    try {
      BodyFatModel.anchored(1, 90, 20.0);
      throw new AssertionError();
    } catch (IllegalArgumentException expected) {
    }
    System.out.println(
        "PASS: impedance aggregation, exact personal anchor, percentage-point offset and"
            + " invalid-input rejection. These verify arithmetic, not accuracy.");
  }
}
