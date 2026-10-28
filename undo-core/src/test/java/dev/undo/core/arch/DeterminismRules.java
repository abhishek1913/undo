package dev.undo.core.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/** Shared by undo-core and shopfront-core architecture tests (Section 7.3). */
public final class DeterminismRules {
  private DeterminismRules() {}

  public static ArchRule noInfrastructure() {
    return noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "java.net.http..",
            "java.sql..",
            "okhttp3..",
            "com.anthropic..")
        .because("core modules must stay free of frameworks, JDBC, HTTP and the Claude SDK");
  }

  public static List<ArchRule> noNondeterminism() {
    return List.of(
        noClasses().should().callMethod(Instant.class, "now").because("use the Clock port"),
        noClasses().should().callMethod(LocalDateTime.class, "now"),
        noClasses().should().callMethod(LocalDate.class, "now"),
        noClasses().should().callMethod(ZonedDateTime.class, "now"),
        noClasses().should().callMethod(OffsetDateTime.class, "now"),
        noClasses().should().callMethod(System.class, "currentTimeMillis"),
        noClasses().should().callMethod(System.class, "nanoTime"),
        noClasses().should().callMethod(UUID.class, "randomUUID").because("use the Ids port"),
        noClasses().should().callConstructor(Random.class),
        noClasses().should().callConstructor(Random.class, long.class),
        noClasses().should().callConstructor(SecureRandom.class),
        noClasses().should().dependOnClassesThat().areAssignableTo(ThreadLocalRandom.class),
        noClasses().should().callMethod(Math.class, "random"),
        noClasses().should().callConstructor(Thread.class),
        noClasses().should().callConstructor(Thread.class, Runnable.class),
        noClasses().should().callMethod(Thread.class, "startVirtualThread", Runnable.class),
        noClasses().should().callMethod(Thread.class, "ofVirtual"),
        noClasses().should().callMethod(Thread.class, "ofPlatform"),
        noClasses().should().dependOnClassesThat().areAssignableTo(Executors.class),
        noClasses().should().callMethod(Thread.class, "sleep", long.class).because("use Waiter"),
        noClasses()
            .should()
            .callMethod(Thread.class, "sleep", java.time.Duration.class)
            .because("use Waiter"));
  }
}
