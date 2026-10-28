package dev.shopfront.core.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

/** Section 7.3: shopfront-core is pure, so the simulator can run it deterministically. */
class ShopfrontCoreArchitectureTest {
  private static final JavaClasses CORE =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("dev.shopfront.core");

  @Test
  void noInfrastructureDependencies() {
    noClasses()
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "java.net.http..",
            "java.sql..",
            "okhttp3..",
            "com.anthropic..")
        .allowEmptyShould(true)
        .check(CORE);
  }

  @Test
  void noWallClockRandomnessOrThreads() {
    List<ArchRule> rules =
        List.of(
            noClasses().should().callMethod(Instant.class, "now"),
            noClasses().should().callMethod(LocalDateTime.class, "now"),
            noClasses().should().callMethod(System.class, "currentTimeMillis"),
            noClasses().should().callMethod(System.class, "nanoTime"),
            noClasses().should().callMethod(UUID.class, "randomUUID"),
            noClasses().should().callConstructor(Random.class),
            noClasses().should().callConstructor(SecureRandom.class),
            noClasses().should().dependOnClassesThat().areAssignableTo(ThreadLocalRandom.class),
            noClasses().should().callMethod(Math.class, "random"),
            noClasses().should().callConstructor(Thread.class, Runnable.class),
            noClasses().should().dependOnClassesThat().areAssignableTo(Executors.class),
            noClasses().should().callMethod(Thread.class, "sleep", long.class));
    for (ArchRule rule : rules) {
      rule.allowEmptyShould(true).check(CORE);
    }
  }
}
