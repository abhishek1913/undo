package dev.undo.server.store;

import dev.undo.core.port.Clock;
import dev.undo.core.port.Locks;
import dev.undo.core.port.Store;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** The only place that hands JdbcClient to anything (ArchUnit rule 3). */
@Configuration
public class StoreBeans {
  @Bean
  Store store(JdbcClient db, PlatformTransactionManager tm) {
    return new PostgresStore(db, tm);
  }

  @Bean
  DemoReset demoReset(JdbcClient db) {
    return new DemoReset(db);
  }

  @Bean
  Locks locks(JdbcClient db, Clock clock) {
    return new JdbcLocks(db, clock);
  }
}
