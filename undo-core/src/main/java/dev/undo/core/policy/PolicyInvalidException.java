package dev.undo.core.policy;

import java.util.List;

public final class PolicyInvalidException extends RuntimeException {
  private final List<PolicyError> errors;

  public PolicyInvalidException(List<PolicyError> errors) {
    super("Policy is invalid: " + errors);
    this.errors = List.copyOf(errors);
  }

  public List<PolicyError> errors() {
    return errors;
  }
}
