package org.ownscale.app;

public final class Protocol {
  public static boolean checksum(byte[] p) {
    int x = 0;
    for (byte b : p) x ^= b & 255;
    return p.length > 1 && x == 0;
  }

  public static int u16(byte[] p, int i) {
    return (p[i] & 255) | ((p[i + 1] & 255) << 8);
  }

  public static Double stableWeight(byte[] p) {
    if (p.length != 11 || (p[0] & 255) != 207 || !checksum(p) || (p[2] & 128) == 0) return null;
    double kg = u16(p, 3) / 100.0;
    return kg > 0 && kg <= 350 ? kg : null;
  }

  public static double[] impedance(byte[] p) {
    if (p.length != 13 || (p[0] & 255) != 190 || !checksum(p) || (p[1] & 255) > 1) return null;
    double[] z = new double[5];
    for (int i = 0; i < 5; i++) z[i] = u16(p, 2 + i * 2) / 10.0;
    return z;
  }

  public static byte[] profile(int height, int age, int sex, int activity) {
    if (height < 100
        || height > 250
        || age < 18
        || age > 110
        || sex < 0
        || sex > 1
        || activity < 0
        || activity > 2)
      throw new IllegalArgumentException("Enter an adult profile: height 100–250 cm, age 18–110.");
    byte[] p = {(byte) 254, 1, (byte) sex, (byte) activity, (byte) height, (byte) age, 1, 0};
    for (int i = 1; i < 7; i++) p[7] ^= p[i];
    return p;
  }

  public static String hex(byte[] p) {
    StringBuilder s = new StringBuilder();
    for (byte b : p) s.append(String.format(java.util.Locale.US, "%02x", b & 255));
    return s.toString();
  }
}
