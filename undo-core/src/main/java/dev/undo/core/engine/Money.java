package dev.undo.core.engine;

/** Indian-style money text, e.g. ₹1,23,456 or ₹1,234.50 (Section 20.2). */
public final class Money {
  private Money() {}

  public static String format(long minor) {
    boolean neg = minor < 0;
    long abs = Math.abs(minor);
    long rupees = abs / 100;
    long paise = abs % 100;
    String digits = Long.toString(rupees);
    String grouped;
    if (digits.length() <= 3) {
      grouped = digits;
    } else {
      StringBuilder head = new StringBuilder(digits.substring(0, digits.length() - 3));
      for (int i = head.length() - 2; i > 0; i -= 2) {
        head.insert(i, ',');
      }
      grouped = head + "," + digits.substring(digits.length() - 3);
    }
    return (neg ? "-" : "") + "₹" + grouped + (paise == 0 ? "" : String.format(".%02d", paise));
  }
}
