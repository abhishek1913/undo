package dev.undo.server.security;

import dev.undo.core.port.Clock;
import dev.undo.core.port.Ids;
import dev.undo.server.config.UndoProperties;
import dev.undo.server.store.UserStore;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/** Console users: bcrypt cost 12, bootstrap admin, demo users (Section 27.3). */
@Service
@Order(2)
public class UserService implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(UserService.class);
  private final UserStore users;
  private final Ids ids;
  private final Clock clock;
  private final UndoProperties props;
  private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(12);

  public UserService(UserStore users, Ids ids, Clock clock, UndoProperties props) {
    this.users = users;
    this.ids = ids;
    this.clock = clock;
    this.props = props;
  }

  public Optional<ConsoleUser> login(String email, String password) {
    if (email == null || password == null) {
      return Optional.empty();
    }
    return users
        .byEmail(email.trim())
        .filter(u -> bcrypt.matches(password, u.passwordHash()))
        .map(u -> new ConsoleUser(u.id(), u.email(), new HashSet<>(u.roles())));
  }

  public void create(String email, String password, List<String> roles) {
    users.insert(ids.next(), email, bcrypt.encode(password), roles, clock.now());
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!props.api()) {
      return;
    }
    String email = props.auth().bootstrapAdminEmail();
    if (email != null && !email.isBlank() && users.count() == 0) {
      create(email, props.auth().bootstrapAdminPassword(), List.of(ConsoleUser.ADMIN));
      log.info("created the first admin {}", email);
    }
    if (props.demo().enabled()) {
      if (users.byEmail("admin@undo.local").isEmpty()) {
        create("admin@undo.local", "undo-demo-admin", List.of(ConsoleUser.ADMIN));
      }
      if (users.byEmail("approver@undo.local").isEmpty()) {
        create(
            "approver@undo.local",
            "undo-demo-approver",
            List.of(ConsoleUser.APPROVER, ConsoleUser.REVERTER));
      }
      if (users.byEmail("viewer@undo.local").isEmpty()) {
        create("viewer@undo.local", "undo-demo-viewer", List.of(ConsoleUser.VIEWER));
      }
    }
  }
}
