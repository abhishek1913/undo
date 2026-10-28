package dev.undo.server.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dev.undo.core.port.BugFlags;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Section 28.3 rules 2, 3, 4 (server part) and 6. */
class ServerArchitectureTest {
  static final JavaClasses SERVER =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("dev.undo.server");

  @Test
  void apiPackagesDoNotAccessTheStorePackage() {
    noClasses()
        .that()
        .resideInAPackage("..api..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("dev.undo.server.store..")
        .check(SERVER);
  }

  @Test
  void onlyTheStorePackageUsesJdbcClient() {
    noClasses()
        .that()
        .resideOutsideOfPackage("dev.undo.server.store..")
        .should()
        .dependOnClassesThat()
        .areAssignableTo(JdbcClient.class)
        .check(SERVER);
  }

  @Test
  void theServerBindsOnlyBugFlagsNone() {
    noClasses().should().implement(BugFlags.class).check(SERVER);
  }

  @Test
  void onlyTheLlmPackageImportsTheClaudeSdk() {
    noClasses()
        .that()
        .resideOutsideOfPackage("dev.undo.server.llm..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("com.anthropic..")
        .allowEmptyShould(true)
        .check(SERVER);
  }
}
