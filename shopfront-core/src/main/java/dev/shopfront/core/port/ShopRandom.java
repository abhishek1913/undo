package dev.shopfront.core.port;

/** Randomness for ids and fault decisions. Server: a secure random. Simulator: seeded. */
public interface ShopRandom {
  /** A value in [0, 1). */
  double nextDouble();

  /** A value in [0, bound). */
  int nextInt(int bound);

  long nextLong();

  /** n characters of RFC 4648 base32 (A–Z, 2–7). */
  default String base32(int n) {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    StringBuilder sb = new StringBuilder(n);
    for (int i = 0; i < n; i++) {
      sb.append(alphabet.charAt(nextInt(32)));
    }
    return sb.toString();
  }
}
