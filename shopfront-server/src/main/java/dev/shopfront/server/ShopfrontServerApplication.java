package dev.shopfront.server;

import dev.shopfront.core.port.ShopClock;
import dev.shopfront.core.port.ShopConfig;
import dev.shopfront.core.port.ShopRandom;
import dev.shopfront.core.router.ShopRouter;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ShopProperties.class)
public class ShopfrontServerApplication {
  /** Config file is shopfront.yml, so it never mixes with undo-server's application.yml. */
  public static final String CONFIG_NAME = "spring.config.name=shopfront";

  public static void main(String[] args) {
    new org.springframework.boot.builder.SpringApplicationBuilder(ShopfrontServerApplication.class)
        .properties(CONFIG_NAME)
        .run(args);
  }

  /** Postgres stores microseconds; keep the clock at the same precision. */
  @Bean
  ShopClock shopClock() {
    return () -> Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  @Bean
  ShopRandom shopRandom() {
    SecureRandom r = new SecureRandom();
    return new ShopRandom() {
      public double nextDouble() {
        return r.nextDouble();
      }

      public int nextInt(int bound) {
        return r.nextInt(bound);
      }

      public long nextLong() {
        return r.nextLong();
      }
    };
  }

  @Bean
  ShopConfig shopConfig(ShopProperties p) {
    return new ShopConfig(
        p.apiKey(),
        p.adminToken(),
        p.webhookSecret(),
        p.settlementDelay(),
        p.exportLag(),
        p.idempotencyTtl());
  }

  @Bean
  ShopRouter shopRouter(
      JdbcShopStore store, ShopClock clock, ShopRandom random, ShopConfig config) {
    return new ShopRouter(store, clock, random, config);
  }
}
