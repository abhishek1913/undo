package dev.undo.server.security;

import dev.undo.core.engine.UndoException;
import java.util.Set;
import java.util.UUID;

/** A signed-in console user. ADMIN can do everything; the other roles are independent. */
public record ConsoleUser(UUID id, String email, Set<String> roles) {
  public static final String VIEWER = "VIEWER";
  public static final String APPROVER = "APPROVER";
  public static final String REVERTER = "REVERTER";
  public static final String ADMIN = "ADMIN";

  public boolean has(String role) {
    return roles.contains(ADMIN)
        || roles.contains(role)
        || (role.equals(VIEWER) && !roles.isEmpty());
  }

  public void require(String role) {
    if (!has(role)) {
      throw new UndoException(UndoException.Code.FORBIDDEN, "This needs the " + role + " role.");
    }
  }

  public String actor() {
    return "user:" + email;
  }
}
