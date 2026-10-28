package dev.undo.sim.world;

import dev.undo.sim.SimulatedCrash;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Runs blocking engine code as cooperative sim tasks (Section 21.10). Each task runs on its own
 * virtual thread, but a baton guarantees that exactly one thread (the event loop or one task) runs
 * at any moment. A task gives the baton back only at a blocking point: {@link #block}, or task end.
 * Same seed, same order, same result.
 */
public final class CoopScheduler {
  /** Something that owns tasks and can die (a worker incarnation), or the immortal world. */
  public static final class Owner {
    final String name;
    final Set<Task> tasks = new LinkedHashSet<>();
    boolean dead;

    public Owner(String name) {
      this.name = name;
    }

    public boolean dead() {
      return dead;
    }

    public String name() {
      return name;
    }
  }

  /** A point where a task waits; the first of its resume events wins. */
  public static final class Park {
    final Task task;
    boolean done;
    Object value;

    Park(Task task) {
      this.task = task;
    }
  }

  public static final class Task {
    final String name;
    final Owner owner;
    final Runnable body;
    final Semaphore baton = new Semaphore(0);
    boolean dead;
    boolean finished;
    Park park;
    BiConsumer<Boolean, Object> onEnd;

    Task(String name, Owner owner, Runnable body) {
      this.name = name;
      this.owner = owner;
      this.body = body;
    }

    public String name() {
      return name;
    }

    public Owner owner() {
      return owner;
    }
  }

  private final EventLoop loop;
  private final Semaphore loopBaton = new Semaphore(0);
  private final ThreadLocal<Task> current = new ThreadLocal<>();
  private final BiConsumer<Task, Throwable> onError;
  private int live;

  public CoopScheduler(EventLoop loop, BiConsumer<Task, Throwable> onError) {
    this.loop = loop;
    this.onError = onError;
  }

  public EventLoop loop() {
    return loop;
  }

  public int liveTasks() {
    return live;
  }

  /** The task running on this thread, or null on the event loop. */
  public Task current() {
    return current.get();
  }

  /** Starts a task at the current virtual time (after the current event). */
  public Task spawn(String name, Owner owner, Runnable body) {
    Task t = new Task(name, owner, body);
    if (owner.dead) {
      return t;
    }
    owner.tasks.add(t);
    live++;
    Thread.ofVirtual().name("sim-" + name).start(() -> runBody(t));
    loop.after(0, "start " + name, () -> transfer(t));
    return t;
  }

  private void runBody(Task t) {
    t.baton.acquireUninterruptibly();
    current.set(t);
    boolean ok = false;
    Object err = null;
    try {
      if (t.dead) {
        throw new SimulatedCrash("dead before start");
      }
      t.body.run();
      ok = true;
    } catch (SimulatedCrash c) {
      err = c;
    } catch (RuntimeException e) {
      err = e;
      if (t.onEnd == null) {
        onError.accept(t, e); // an RPC child hands it to its caller instead
      }
    } catch (Throwable e) {
      err = e;
      onError.accept(t, e);
    } finally {
      t.finished = true;
      t.owner.tasks.remove(t);
      live--;
      if (t.onEnd != null) {
        t.onEnd.accept(ok, err);
      }
      loopBaton.release();
    }
  }

  /** Event loop → task; returns when the task blocks again or ends. */
  private void transfer(Task t) {
    if (t.finished) {
      return;
    }
    t.baton.release();
    loopBaton.acquireUninterruptibly();
  }

  /** Called on a task thread: park until a resume event fires. Throws SimulatedCrash if killed. */
  public Object block(Park p) {
    Task t = current.get();
    if (t == null || p.task != t) {
      throw new IllegalStateException("block() must be called by the parked task");
    }
    if (t.dead) {
      throw new SimulatedCrash(t.name);
    }
    t.park = p;
    loopBaton.release();
    t.baton.acquireUninterruptibly();
    t.park = null;
    if (t.dead) {
      throw new SimulatedCrash(t.name);
    }
    return p.value;
  }

  public Park newPark() {
    Task t = current.get();
    if (t == null) {
      throw new IllegalStateException("only tasks can park");
    }
    return new Park(t);
  }

  /** Schedules the parked task to continue at the given time with a value (first resume wins). */
  public void resumeAt(Park p, long timeMicros, String what, Object value) {
    loop.at(timeMicros, what, () -> resumeNow(p, value));
  }

  private void resumeNow(Park p, Object value) {
    if (p.done || p.task.finished) {
      return;
    }
    p.done = true;
    p.value = value;
    transfer(p.task);
  }

  /** Convenience: park the current task for a duration. */
  public void sleep(long micros, String what) {
    Park p = newPark();
    resumeAt(p, loop.nowMicros() + micros, what, null);
    block(p);
  }

  /**
   * Runs {@code work} as a child task of {@code owner} (an RPC into a worker) and blocks the caller
   * until it returns. If the owner dies first, the caller gets {@link RpcFailed}.
   */
  public <T> T call(Owner owner, String name, Supplier<T> work) {
    Park p = newPark();
    if (owner.dead) {
      throw new RpcFailed(owner.name + " is down");
    }
    Object[] box = new Object[1];
    Task child = spawn(name, owner, () -> box[0] = work.get());
    child.onEnd =
        (ok, err) -> {
          Object v = ok ? new Ok(box[0]) : err;
          resumeAt(p, loop.nowMicros(), "rpc-return " + name, v);
        };
    Object v = block(p);
    if (v instanceof Ok o) {
      @SuppressWarnings("unchecked")
      T r = (T) o.value;
      return r;
    }
    if (v instanceof RuntimeException re && !(v instanceof SimulatedCrash)) {
      throw re;
    }
    throw new RpcFailed(name + " failed: " + v);
  }

  private record Ok(Object value) {}

  /** The worker serving an RPC died (connection reset from the caller's point of view). */
  public static final class RpcFailed extends RuntimeException {
    public RpcFailed(String m) {
      super(m, null, false, false);
    }
  }

  /** Kills every task of the owner: each unwinds with SimulatedCrash at its blocking point. */
  public void kill(Owner owner) {
    owner.dead = true;
    List<Task> ts = new ArrayList<>(owner.tasks);
    for (Task t : ts) {
      t.dead = true;
      if (t.park != null) {
        Park p = t.park;
        loop.after(0, "unwind " + t.name, () -> resumeNow(p, null));
      }
    }
  }

  /** At the end of a seed: unwind everything still parked so no thread is left behind. */
  public void shutdown(List<Owner> owners) {
    for (Owner o : owners) {
      kill(o);
    }
    while (live > 0 && loop.step()) {
      // drain unwind events
    }
  }
}
