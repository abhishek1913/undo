package dev.undo.core.port.repo;

import dev.undo.core.model.PolicyVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyRepo {
  void insert(PolicyVersion version);

  Optional<PolicyVersion> get(UUID id);

  Optional<PolicyVersion> active(String name);

  Optional<PolicyVersion> version(String name, int version);

  List<PolicyVersion> all();

  /** 0 when the name has no versions. */
  int maxVersion(String name);

  /** Deactivates the other versions of the same name, then activates this one. */
  void activate(UUID id);
}
