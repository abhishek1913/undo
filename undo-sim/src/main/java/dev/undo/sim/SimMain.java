package dev.undo.sim;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.undo.core.json.Json;
import dev.undo.core.model.ActionStatus;
import dev.undo.core.model.AttemptOutcome;
import dev.undo.core.port.Bug;
import dev.undo.core.port.CrashPoint;
import dev.undo.sim.world.Coverage;
import dev.undo.sim.world.Trace;
import dev.undo.sim.world.Violation;
import dev.undo.sim.world.World;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simulator CLI (Section 21.6).
 *
 * <pre>
 * java -jar undo-sim.jar --seeds 100000 [--start 1] [--threads N] [--bug NAME] [--assume-a1=false]
 *                        [--seed 88421 --trace out/seed-88421.log] [--report out/sim-report.json]
 * java -jar undo-sim.jar --all-bugs [--seeds 20000]
 * </pre>
 */
public final class SimMain {
  /** Crash points that exist so far (all 13 from M8 on). */
  public static final Set<CrashPoint> EXPECTED_POINTS =
      EnumSet.of(
          CrashPoint.PROPOSE_BEFORE_COMMIT,
          CrashPoint.PROPOSE_AFTER_COMMIT,
          CrashPoint.CLAIM_AFTER_COMMIT,
          CrashPoint.PROVIDER_CALL_AFTER_SEND,
          CrashPoint.PROVIDER_CALL_AFTER_RESPONSE,
          CrashPoint.COMPLETE_AFTER_COMMIT,
          CrashPoint.RESOLVE_AFTER_LOOKUP,
          CrashPoint.APPROVAL_AFTER_COMMIT,
          CrashPoint.WEBHOOK_AFTER_INSERT,
          CrashPoint.RECON_AFTER_FETCH,
          CrashPoint.RUNNER_AFTER_MODEL_RESPONSE,
          CrashPoint.RUNNER_AFTER_TURN_PERSIST,
          CrashPoint.REVERT_AFTER_ITEM_CREATE);

  /** Bug flags the simulator can exercise so far. */
  public static final List<Bug> ACTIVE_BUGS =
      List.of(
          Bug.NEW_KEY_PER_ATTEMPT,
          Bug.NO_FENCE_CHECK,
          Bug.RETRY_UNKNOWN_WITHOUT_LOOKUP,
          Bug.SKIP_LOOKUP_GRACE,
          Bug.BUDGET_CHECK_THEN_ACT,
          Bug.APPROVAL_NOT_HASH_BOUND,
          Bug.NONE_CLASS_RETRY,
          Bug.RESOLVER_IGNORES_FREEZE,
          Bug.REVERT_TRUSTS_PREVIEW,
          Bug.JOURNAL_SKIPS_EXISTENCE_CHECK,
          Bug.RUNNER_NO_CALL_ID);

  static final int LEGAL_EDGES = 23;

  static boolean fingerprints;

  public static void main(String[] args) throws Exception {
    long seeds = 1000;
    long start = 1;
    int threads = Runtime.getRuntime().availableProcessors();
    Set<Bug> bugs = EnumSet.noneOf(Bug.class);
    boolean allBugs = false;
    boolean assumeA1 = true;
    Long single = null;
    String tracePath = null;
    String reportPath = null;
    boolean regression = true;
    for (int i = 0; i < args.length; i++) {
      String a = args[i];
      switch (a) {
        case "--seeds" -> seeds = Long.parseLong(args[++i]);
        case "--start" -> start = Long.parseLong(args[++i]);
        case "--threads" -> threads = Integer.parseInt(args[++i]);
        case "--bug" -> bugs.add(Bug.valueOf(args[++i]));
        case "--all-bugs" -> allBugs = true;
        case "--assume-a1=false" -> assumeA1 = false;
        case "--assume-a1=true" -> assumeA1 = true;
        case "--seed" -> single = Long.parseLong(args[++i]);
        case "--trace" -> tracePath = args[++i];
        case "--report" -> reportPath = args[++i];
        case "--no-regression" -> regression = false;
        case "--fingerprints" -> fingerprints = true;
        default -> throw new IllegalArgumentException("unknown option " + a);
      }
    }
    if (single != null) {
      System.exit(replay(single, bugs, assumeA1, tracePath));
    }
    if (allBugs) {
      System.exit(huntBugs(seeds, threads));
    }
    System.exit(run(seeds, start, threads, bugs, assumeA1, reportPath, regression));
  }

  static int replay(long seed, Set<Bug> bugs, boolean assumeA1, String tracePath)
      throws IOException {
    Trace trace = null;
    BufferedWriter out = null;
    if (tracePath != null) {
      Path p = Path.of(tracePath);
      if (p.getParent() != null) {
        Files.createDirectories(p.getParent());
      }
      out = Files.newBufferedWriter(p, StandardCharsets.UTF_8);
      trace = new Trace(out);
    }
    World world = new World(seed, bugs, assumeA1, trace);
    World.Result r = world.run();
    if (out != null) {
      out.close();
    }
    System.out.printf(
        "Seed %d · %,d events · %,d virtual seconds · violations: %d%n",
        seed, r.events(), r.virtualSeconds(), r.violations().size());
    java.util.Set<Long> shown = new java.util.TreeSet<>();
    for (Violation v : r.violations()) {
      System.out.println(v.invariant() + " seed " + v.seed() + ": " + v.message());
      java.util.regex.Matcher m = java.util.regex.Pattern.compile("#A-(\\d+)").matcher(v.message());
      while (m.find()) {
        shown.add(Long.parseLong(m.group(1)));
      }
    }
    for (long n : shown) {
      System.out.print(world.describe(n));
    }
    if (r.violations().stream().anyMatch(v -> v.invariant().equals("INV-6"))) {
      System.out.print(world.describePlans());
    }
    if (tracePath != null) {
      System.out.println("Trace written to " + tracePath);
    }
    return r.violations().isEmpty() ? 0 : 1;
  }

  record Summary(long seeds, long millis, List<Violation> violations, Coverage coverage) {}

  static Summary runSeeds(
      long seeds, long start, int threads, Set<Bug> bugs, boolean assumeA1, boolean stopAtFirst)
      throws Exception {
    long t0 = System.currentTimeMillis();
    Coverage total = new Coverage();
    List<Violation> violations = new ArrayList<>();
    AtomicLong next = new AtomicLong(start);
    long end = start + seeds;
    // Each seed's event loop runs on a virtual thread, so baton handoffs between the loop and the
    // sim tasks stay inside the virtual-thread scheduler (no OS-level park/unpark).
    ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    List<Future<?>> fs = new ArrayList<>();
    final long[] firstBad = {Long.MAX_VALUE};
    for (int t = 0; t < threads; t++) {
      fs.add(
          pool.submit(
              () -> {
                while (true) {
                  long s = next.getAndIncrement();
                  if (s >= end) {
                    return;
                  }
                  synchronized (firstBad) {
                    if (stopAtFirst && s > firstBad[0]) {
                      return;
                    }
                  }
                  World.Result r = new World(s, bugs, assumeA1, null).run();
                  if (fingerprints) {
                    System.out.println(
                        "FP "
                            + s
                            + " "
                            + r.events()
                            + " "
                            + r.virtualSeconds()
                            + " "
                            + r.coverage().transitions.values().stream()
                                .mapToLong(Long::longValue)
                                .sum());
                  }
                  total.merge(r.coverage());
                  if (!r.violations().isEmpty()) {
                    synchronized (violations) {
                      violations.addAll(r.violations());
                    }
                    synchronized (firstBad) {
                      firstBad[0] = Math.min(firstBad[0], s);
                    }
                  }
                }
              }));
    }
    for (Future<?> f : fs) {
      f.get();
    }
    pool.shutdown();
    violations.sort((a, b) -> Long.compare(a.seed(), b.seed()));
    return new Summary(
        Math.min(seeds, next.get() - start), System.currentTimeMillis() - t0, violations, total);
  }

  static int run(
      long seeds,
      long start,
      int threads,
      Set<Bug> bugs,
      boolean assumeA1,
      String reportPath,
      boolean regression)
      throws Exception {
    Summary s = runSeeds(seeds, start, threads, bugs, assumeA1, false);
    double perSec = s.seeds() * 1000.0 / Math.max(1, s.millis());
    System.out.printf(
        Locale.ROOT,
        "Undo simulator · %,d seeds · %d threads · %s · %,.0f seeds/s%s%s%n",
        s.seeds(),
        threads,
        duration(s.millis()),
        perSec,
        bugs.isEmpty() ? "" : " · bugs " + bugs,
        assumeA1 ? "" : " · assume-a1=false");
    System.out.println("Violations: " + s.violations().size());
    printViolations(s.violations(), 20);
    printBreakdown(s.violations());
    Coverage c = s.coverage();
    long pointsHit =
        EXPECTED_POINTS.stream().filter(p -> c.reached.getOrDefault(p, 0L) > 0).count();
    long minHit =
        EXPECTED_POINTS.stream().mapToLong(p -> c.reached.getOrDefault(p, 0L)).min().orElse(0);
    long minCrash =
        EXPECTED_POINTS.stream().mapToLong(p -> c.crashed.getOrDefault(p, 0L)).min().orElse(0);
    long edges =
        c.transitions.keySet().stream()
            .filter(
                k -> {
                  String[] ft = k.split(">");
                  return ActionStatus.valueOf(ft[0]).canMoveTo(ActionStatus.valueOf(ft[1]));
                })
            .count();
    System.out.printf(
        Locale.ROOT,
        "Coverage: crash points %d/%d hit (min %,d, min crashed %,d) · action transitions %d/%d legal edges · outcomes %d/%d · revert kinds %d/7 · finding types %d/9%n",
        pointsHit,
        EXPECTED_POINTS.size(),
        minHit,
        minCrash,
        edges,
        LEGAL_EDGES,
        c.outcomes.size(),
        AttemptOutcome.values().length,
        c.revertKinds.size(),
        c.findingTypes.size());
    List<String> missing = new ArrayList<>();
    for (ActionStatus f : ActionStatus.values()) {
      for (ActionStatus t : ActionStatus.legalTargets(f)) {
        if (!c.transitions.containsKey(f + ">" + t)) {
          missing.add(f + ">" + t);
        }
      }
    }
    if (!missing.isEmpty()) {
      System.out.println("  edges not seen: " + missing);
    }
    int regressionTotal = 0;
    int regressionPass = 0;
    if (regression && bugs.isEmpty()) {
      List<String[]> reg = regressionSeeds();
      regressionTotal = reg.size();
      for (String[] line : reg) {
        Set<Bug> b =
            line[0].equals("NONE") ? EnumSet.noneOf(Bug.class) : EnumSet.of(Bug.valueOf(line[0]));
        long seed = Long.parseLong(line[1]);
        boolean cleanPasses =
            new World(seed, EnumSet.noneOf(Bug.class), true, null).run().violations().isEmpty();
        boolean bugCaught =
            b.isEmpty() || !new World(seed, b, true, null).run().violations().isEmpty();
        if (cleanPasses && bugCaught) {
          regressionPass++;
        } else {
          System.out.println(
              "Regression seed failed: "
                  + line[0]
                  + " "
                  + seed
                  + " (clean passes="
                  + cleanPasses
                  + ", bug caught="
                  + bugCaught
                  + ")");
        }
      }
      System.out.printf("Regression seeds: %d/%d pass%n", regressionPass, regressionTotal);
    }
    if (reportPath != null) {
      writeReport(reportPath, s, threads, perSec);
    }
    if (!s.violations().isEmpty() || regressionPass < regressionTotal) {
      return 1;
    }
    if (s.seeds() >= 10_000 && pointsHit < EXPECTED_POINTS.size()) {
      System.out.println("FAILED: a crash point was never hit.");
      return 2;
    }
    return 0;
  }

  static int huntBugs(long maxSeeds, int threads) throws Exception {
    int caught = 0;
    for (Bug b : ACTIVE_BUGS) {
      Summary s = runSeeds(maxSeeds, 1, threads, EnumSet.of(b), true, true);
      if (s.violations().isEmpty()) {
        System.out.printf("%-30s NOT CAUGHT in %,d seeds%n", b, s.seeds());
      } else {
        Violation v = s.violations().get(0);
        caught++;
        java.util.Set<String> invs = new java.util.TreeSet<>();
        for (Violation x : s.violations()) {
          if (x.seed() == v.seed()) {
            invs.add(x.invariant());
          }
        }
        Violation shown = v;
        for (Violation x : s.violations()) {
          if (x.seed() == v.seed() && !x.invariant().equals("ENGINE")) {
            shown = x;
            break;
          }
        }
        System.out.printf(
            "%-30s caught at seed %-6d %-22s %s%n",
            b, v.seed(), String.join(",", invs), abbreviate(shown.message(), 100));
      }
    }
    System.out.printf("Bug flags caught: %d/%d%n", caught, ACTIVE_BUGS.size());
    return caught == ACTIVE_BUGS.size() ? 0 : 1;
  }

  static List<String[]> regressionSeeds() throws IOException {
    List<String[]> out = new ArrayList<>();
    try (InputStream in = SimMain.class.getResourceAsStream("/regression-seeds.txt")) {
      if (in == null) {
        return out;
      }
      for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
        line = line.trim();
        if (!line.isEmpty() && !line.startsWith("#")) {
          out.add(line.split("\\s+"));
        }
      }
    }
    return out;
  }

  static void printViolations(List<Violation> vs, int max) {
    for (int i = 0; i < Math.min(max, vs.size()); i++) {
      Violation v = vs.get(i);
      System.out.println(
          "  " + v.invariant() + " seed " + v.seed() + ": " + abbreviate(v.message(), 300));
    }
    if (vs.size() > max) {
      System.out.println("  … " + (vs.size() - max) + " more");
    }
  }

  private static final java.util.regex.Pattern TOOL =
      java.util.regex.Pattern.compile("shop_[a-z_]+");
  private static Map<String, String> toolClasses;

  /**
   * The idempotency class of the tool a violation message names, or "n/a" (A1 experiment, 21.8).
   */
  static String classOf(Violation v) {
    if (toolClasses == null) {
      toolClasses = new java.util.HashMap<>();
      for (dev.undo.core.connector.ToolSpec t :
          new dev.undo.connector.shopfront.ShopfrontConnector(
                  null,
                  new dev.undo.connector.shopfront.ShopfrontConnector.Config(
                      "k", "s", java.time.Duration.ofMinutes(15), java.time.Duration.ofSeconds(3)),
                  java.time.Instant::now)
              .tools()) {
        toolClasses.put(t.name(), t.idempotencyClass().name());
      }
    }
    java.util.regex.Matcher m = TOOL.matcher(v.message());
    while (m.find()) {
      String c = toolClasses.get(m.group());
      if (c != null) {
        return c;
      }
    }
    return "n/a";
  }

  /** Violations by invariant and idempotency class: {invariant -> {class -> count}}. */
  static Map<String, Map<String, Long>> breakdown(List<Violation> vs) {
    Map<String, Map<String, Long>> out = new java.util.TreeMap<>();
    for (Violation v : vs) {
      out.computeIfAbsent(v.invariant(), k -> new java.util.TreeMap<>())
          .merge(classOf(v), 1L, Long::sum);
    }
    return out;
  }

  static void printBreakdown(List<Violation> vs) {
    if (vs.isEmpty()) {
      return;
    }
    System.out.println("By invariant and idempotency class (violations, seeds):");
    breakdown(vs)
        .forEach(
            (inv, byClass) ->
                byClass.forEach(
                    (cls, n) -> {
                      long seeds =
                          vs.stream()
                              .filter(v -> v.invariant().equals(inv) && classOf(v).equals(cls))
                              .map(Violation::seed)
                              .distinct()
                              .count();
                      System.out.printf(
                          Locale.ROOT,
                          "  %-8s %-22s %,8d violations in %,d seeds%n",
                          inv,
                          cls,
                          n,
                          seeds);
                    }));
  }

  static void writeReport(String path, Summary s, int threads, double perSec) throws IOException {
    ObjectNode r = Json.obj();
    r.put("seeds", s.seeds());
    r.put("threads", threads);
    r.put("duration_ms", s.millis());
    r.put("seeds_per_sec", Math.round(perSec * 10) / 10.0);
    ObjectNode bd = r.putObject("violations_by_invariant_and_class");
    breakdown(s.violations())
        .forEach(
            (inv, byClass) -> {
              ObjectNode o = bd.putObject(inv);
              byClass.forEach(o::put);
            });
    ArrayNode vs = r.putArray("violations");
    for (Violation v : s.violations()) {
      ObjectNode o = vs.addObject();
      o.put("seed", v.seed());
      o.put("invariant", v.invariant());
      o.put("message", v.message());
      o.put("trace", "make sim-seed SEED=" + v.seed());
    }
    ObjectNode cov = r.putObject("coverage");
    ObjectNode cp = cov.putObject("crash_points_reached");
    s.coverage().reached.forEach((k, v) -> cp.put(k.name(), v));
    ObjectNode cc = cov.putObject("crash_points_crashed");
    s.coverage().crashed.forEach((k, v) -> cc.put(k.name(), v));
    ObjectNode tr = cov.putObject("transitions");
    s.coverage().transitions.forEach(tr::put);
    ObjectNode oc = cov.putObject("outcomes");
    s.coverage().outcomes.forEach((k, v) -> oc.put(k.name(), v));
    ObjectNode rk = cov.putObject("revert_kinds");
    s.coverage().revertKinds.forEach(rk::put);
    ObjectNode ft = cov.putObject("finding_types");
    s.coverage().findingTypes.forEach(ft::put);
    Path p = Path.of(path);
    if (p.getParent() != null) {
      Files.createDirectories(p.getParent());
    }
    Files.writeString(p, Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(r));
  }

  static String duration(long ms) {
    long s = ms / 1000;
    return s >= 60
        ? (s / 60) + "m" + String.format("%02d", s % 60) + "s"
        : String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
  }

  static String abbreviate(String s, int n) {
    return s.length() <= n ? s : s.substring(0, n) + "…";
  }
}
