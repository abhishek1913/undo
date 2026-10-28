package dev.undo.sim.world;

import java.util.SplittableRandom;

/** Everything sampled for one seed (Section 21.4). Times in microseconds. */
public record WorldParams(
    int workers,
    int runs,
    int[] ticketsPerRun,
    int[] ticketOffsetPerRun,
    int[] agentConcurrency,
    long[] moneyCapPerRun,
    int[] actionCapPerRun,
    double crashProb,
    double slowRate,
    double requestDrop,
    double responseDrop,
    double error5xx,
    double rate429,
    double stallRate,
    double webhookDrop,
    double webhookDup,
    long webhookDelayMaxMicros,
    long settlementDelayMicros,
    double settlementFailure,
    boolean adversarial,
    double repeatRate,
    double parallelDupRate,
    double approveRate,
    double wrongHashRate,
    double freezeRate,
    double revertRate,
    boolean noteEdits,
    boolean creditSpends,
    double reviewRate,
    long execTickMicros,
    long sweepTickMicros,
    long resolveTickMicros,
    long waitMs,
    boolean assumeA1,
    long approvalExpiryMicros,
    double forgetApprovalRate,
    boolean inline,
    long[] outageStartMicros,
    long[] outageLengthMicros,
    long maxUnknownAgeMicros) {

  public static final int CUSTOMERS = 20;
  public static final int ORDERS = 80;
  public static final int TICKETS = 60;

  static long ms(long v) {
    return v * 1000;
  }

  static long s(long v) {
    return v * 1_000_000;
  }

  public static WorldParams sample(SplittableRandom r, boolean assumeA1) {
    // Provider outage windows (all requests fail): 30% of seeds get 1–3 windows of 1–15 min.
    SplittableRandom ro = r.split();
    int nOutages = ro.nextDouble() < 0.3 ? 1 + ro.nextInt(3) : 0;
    long[] outageStarts = new long[nOutages];
    long[] outageLengths = new long[nOutages];
    for (int i = 0; i < nOutages; i++) {
      outageStarts[i] = (long) (ro.nextDouble() * s(40 * 60));
      outageLengths[i] = s(60) + (long) (ro.nextDouble() * s(14 * 60));
    }
    int workers = 1 + r.nextInt(4);
    int runs = 1 + r.nextInt(3);
    int[] tickets = new int[runs];
    int[] offsets = new int[runs];
    int[] conc = new int[runs];
    long[] caps = new long[runs];
    int[] actionCaps = new int[runs];
    for (int i = 0; i < runs; i++) {
      tickets[i] = 5 + r.nextInt(36);
      offsets[i] = r.nextInt(TICKETS - tickets[i] + 1);
      conc[i] = 1 + r.nextInt(3);
      boolean tight = r.nextDouble() < 0.3; // small budgets exercise the reservation path
      caps[i] = tight ? 50_000 + r.nextInt(450_000) : 0;
      actionCaps[i] = tight ? 5 + r.nextInt(40) : 0;
    }
    return new WorldParams(
        workers,
        runs,
        tickets,
        offsets,
        conc,
        caps,
        actionCaps,
        r.nextDouble() * 0.05,
        0.02 + r.nextDouble() * 0.03,
        r.nextDouble() * 0.10,
        r.nextDouble() * 0.20,
        r.nextDouble() * 0.10,
        r.nextDouble() * 0.05,
        r.nextDouble() * 0.02,
        r.nextDouble() * 0.30,
        r.nextDouble() * 0.30,
        (long) (r.nextDouble() * s(60)),
        s(30) + (long) (r.nextDouble() * (s(20 * 60) - s(30))),
        r.nextDouble() * 0.10,
        r.nextBoolean(),
        r.nextDouble() * 0.20,
        r.nextDouble() * 0.10,
        r.nextDouble(),
        0.05,
        0.20,
        0.50,
        r.nextDouble() < 0.10,
        r.nextDouble() < 0.10,
        0.70,
        ms(200) + (long) (r.nextDouble() * ms(1800)),
        s(1) + (long) (r.nextDouble() * s(4)),
        ms(500) + (long) (r.nextDouble() * ms(2500)),
        r.nextDouble() < 0.5 ? 10_000 : 2_000 + r.nextInt(13_000),
        assumeA1,
        r.nextBoolean() ? s(24 * 3600) : s(5 * 60) + (long) (r.nextDouble() * s(55 * 60)),
        0.10,
        r.nextDouble() >= 0.3,
        outageStarts,
        outageLengths,
        r.nextBoolean() ? s(3600) : s(3 * 60) + (long) (r.nextDouble() * s(17 * 60)));
  }

  /** Is the provider unreachable at this time? */
  boolean outage(long nowMicros) {
    for (int i = 0; i < outageStartMicros.length; i++) {
      if (nowMicros >= outageStartMicros[i]
          && nowMicros < outageStartMicros[i] + outageLengthMicros[i]) {
        return true;
      }
    }
    return false;
  }

  /** One request's one-way delivery delay. Slow deliveries land after the 10 s client timeout. */
  long deliveryDelay(SplittableRandom r) {
    if (r.nextDouble() < slowRate) {
      long max = assumeA1 ? s(65) : s(180);
      return s(10) + (long) (r.nextDouble() * (max - s(10)));
    }
    return ms(1) + (long) (r.nextDouble() * ms(1500));
  }

  long responseDelay(SplittableRandom r) {
    return ms(1) + (long) (r.nextDouble() * ms(1500));
  }
}
