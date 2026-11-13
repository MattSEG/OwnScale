import org.ownscale.app.Protocol;

public class ProtocolTest {
  static void check(boolean b) {
    if (!b) throw new AssertionError();
  }

  static byte[] checksum(byte[] p) {
    for (int i = 0; i < p.length - 1; i++) p[p.length - 1] ^= p[i];
    return p;
  }

  public static void main(String[] args) {
    byte[] w = checksum(new byte[] {(byte) 207, 0, (byte) 128, 0x51, 0x24, 0, 0, 0, 0, 1, 0});
    check(Math.abs(Protocol.stableWeight(w) - 92.97) < 0.00001);
    w[2] = 0;
    check(Protocol.stableWeight(w) == null);
    w[2] = (byte) 128;
    w[10] ^= 1;
    check(Protocol.stableWeight(w) == null);
    check(Protocol.stableWeight(new byte[] {(byte) 207}) == null);
    byte[] z =
        checksum(
            new byte[] {
              (byte) 190, 0, (byte) 0xf7, 0x0c, (byte) 0x9d, 0x0c, 0x0e, 1, 3, 9, 0x11, 9, 0
            });
    check(Protocol.impedance(z)[0] == 331.9);
    check(Protocol.impedance(z)[2] == 27.0);
    z[1] = 2;
    check(Protocol.impedance(z) == null);
    check(Protocol.hex(Protocol.profile(173, 52, 1, 1)).equals("fe010101ad340199"));
    try {
      Protocol.profile(173, 0, 1, 1);
      throw new AssertionError();
    } catch (IllegalArgumentException expected) {
    }
    System.out.println(
        "PASS: stable/live weight, checksum corruption, truncated packet, impedance units, profile"
            + " checksum and input validation");
  }
}
