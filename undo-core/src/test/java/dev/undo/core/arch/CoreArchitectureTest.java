package dev.undo.core.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import dev.undo.core.port.BugFlags;
import org.junit.jupiter.api.Test;

/** Section 7.3 and 28.3 rules 1 and 4: the core is deterministic and has no infrastructure. */
class CoreArchitectureTest {
  private static final JavaClasses CORE =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("dev.undo.core");

  @Test
  void coreHasNoInfrastructureDependencies() {
    DeterminismRules.noInfrastructure().check(CORE);
  }

  @Test
  void coreNeverReadsTheWallClockOrRandomnessOrStartsThreads() {
    for (ArchRule rule : DeterminismRules.noNondeterminism()) {
      rule.check(CORE);
    }
  }

  @Test
  void decisionCodeNeverReadsTheAdvisoryNote() {
    noClasses()
        .that()
        .haveSimpleName("ApprovalService")
        .or()
        .haveSimpleName("PolicyService")
        .or()
        .haveSimpleName("PolicyEvaluator")
        .or()
        .haveSimpleName("ProposalService")
        .should()
        .accessField(dev.undo.core.model.Approval.class, "advisoryNote")
        .because("the advisory note is never an input to any decision (FR-APR-6, rule 5)")
        .check(CORE);
  }

  @Test
  void bugFlagsAreReferencedOnlyFromAllowedPackages() {
    noClasses()
        .that()
        .resideOutsideOfPackages(
            "dev.undo.core.engine..",
            "dev.undo.core.ledger..",
            "dev.undo.core.revert..",
            "dev.undo.core.recon..",
            "dev.undo.core.agent..",
            "dev.undo.core.port..")
        .should()
        .dependOnClassesThat()
        .areAssignableTo(BugFlags.class)
        .allowEmptyShould(true)
        .check(CORE);
  }
}
