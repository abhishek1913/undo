package dev.shopfront.core.store;

import java.time.Instant;

/** Cursors for time-ordered listings: "<instant>|<id>". */
public final class Cursors {
  private Cursors() {}

  public static int compare(String a, String b) {
    String[] x = a.split("\\|", 2);
    String[] y = b.split("\\|", 2);
    int c = Instant.parse(x[0]).compareTo(Instant.parse(y[0]));
    return c != 0 ? c : x[1].compareTo(y[1]);
  }

  public static Instant time(String cursor) {
    return Instant.parse(cursor.split("\\|", 2)[0]);
  }

  public static String id(String cursor) {
    return cursor.split("\\|", 2)[1];
  }
}
