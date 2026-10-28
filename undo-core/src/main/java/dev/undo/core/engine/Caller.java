package dev.undo.core.engine;

/**
 * Who proposes an action. {@code inline} = this caller may claim and execute ALLOW decisions on its
 * own thread (API nodes with inline enabled, and the hosted runner). {@code runFence} is the
 * runner's lease fence (0 for other callers) so a zombie runner cannot create actions.
 */
public record Caller(Type type, String node, String actor, boolean inline, long runFence) {
  public enum Type {
    API,
    MCP,
    RUNNER,
    SYSTEM
  }

  public static Caller api(String node, String agentName, boolean inline) {
    return new Caller(Type.API, node, "agent:" + agentName, inline, 0);
  }

  public static Caller mcp(String node, String agentName, boolean inline) {
    return new Caller(Type.MCP, node, "agent:" + agentName, inline, 0);
  }

  public static Caller runner(String node, String agentName, long fence) {
    return new Caller(Type.RUNNER, node, "agent:" + agentName, true, fence);
  }

  public static Caller system(String node, String job) {
    return new Caller(Type.SYSTEM, node, "system:" + job, false, 0);
  }

  public boolean isRunner() {
    return type == Type.RUNNER;
  }
}
