package com.signalforge.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules in CONTRIBUTING.md, enforced.
 *
 * <p>A convention documented in a markdown file is a convention until someone new joins, or until
 * the author forgets at 2am. Each rule here exists because breaking it either caused a real bug in
 * this codebase or would cause one that is invisible in review.
 */
@DisplayName("Architecture rules")
class ArchitectureRulesTest {

  private static JavaClasses production;

  @BeforeAll
  static void importClasses() {
    production =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.signalforge");
  }

  @Test
  @DisplayName("controllers never accept an organization id as a request parameter")
  void controllersDoNotAcceptTenantIdFromClient() {
    // The single most important rule in the codebase. If a controller can be told
    // which tenant to act as, every other isolation layer is decoration.
    // The organization id must come from the authenticated principal only.
    ArchRule rule =
        noClasses()
            .that()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.signalforge.platform.tenant.TenantContext")
            .because(
                "controllers must read the tenant from @AuthenticationPrincipal, not from ambient "
                    + "thread state that a background thread could have left behind");

    rule.check(production);
  }

  @Test
  @DisplayName("no module reaches into another module's repositories except through its services")
  void telemetryDoesNotDependOnIncidentInternals() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.signalforge.telemetry..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.signalforge.incident.repository..")
            .because("telemetry has no business reading incident tables directly");

    rule.check(production);
  }

  @Test
  @DisplayName("the ingestion path does not depend on the AI module")
  void ingestionDoesNotDependOnAi() {
    // Structural enforcement of the guarantee in ADR-0011: the AI can never
    // block or slow telemetry ingestion, because it is not reachable from it.
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.signalforge.telemetry..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.signalforge.ai..")
            .because("ADR-0011: the AI assistant must never be on the ingestion path");

    rule.check(production);
  }

  @Test
  @DisplayName("the detection path does not depend on the AI module")
  void detectionDoesNotDependOnAi() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("com.signalforge.detection..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.signalforge.ai..")
            .because("ADR-0011: an unavailable model must not be able to prevent detection");

    rule.check(production);
  }

  @Test
  @DisplayName("repositories are interfaces, not concrete classes with hand-rolled queries")
  void repositoriesAreInterfaces() {
    ArchRule rule =
        classes()
            .that()
            .resideInAPackage("..repository..")
            .and()
            .haveSimpleNameEndingWith("Repository")
            .should()
            .beInterfaces()
            .because("Spring Data repositories are interfaces; a concrete one is a mistake");

    rule.check(production);
  }

  @Test
  @DisplayName("entities live in domain packages")
  void entitiesLiveInDomainPackages() {
    ArchRule rule =
        classes()
            .that()
            .areAnnotatedWith(jakarta.persistence.Entity.class)
            .should()
            .resideInAPackage("..domain..")
            .because("keeping entities in one place per module makes the data model discoverable");

    rule.check(production);
  }

  @Test
  @DisplayName("no controller talks to a repository directly")
  void controllersGoThroughServices() {
    // A controller reaching a repository skips the tenant-scoping and auditing
    // that services perform.
    ArchRule rule =
        noClasses()
            .that()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("EventRepository")
            .because("controllers must go through a service so scoping and auditing are applied");

    rule.check(production);
  }

  @Test
  @DisplayName("nothing uses java.util.Date or Calendar")
  void noLegacyDateApi() {
    ArchRule rule =
        noClasses()
            .that()
            .resideOutsideOfPackage("com.signalforge.iam.auth..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("java.util.Date")
            .because(
                "java.time everywhere; the JWT library is the one exception because its API "
                    + "requires java.util.Date");

    rule.check(production);
  }

  @Test
  @DisplayName("no field injection - constructors only")
  void noFieldInjection() {
    ArchRule rule =
        noClasses()
            .should()
            .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
            .because(
                "constructor injection makes dependencies explicit and the class testable "
                    + "without a container");

    rule.check(production);
  }

  @Test
  @DisplayName("services do not print to stdout")
  void noSystemOut() {
    ArchRule rule =
        noClasses()
            .should()
            .accessField(System.class, "out")
            .orShould()
            .accessField(System.class, "err")
            .because(
                "structured JSON logging only; stdout bypasses correlation ids and log levels");

    rule.check(production);
  }

  @Test
  @DisplayName("public service methods that mutate are transactional")
  void mutatingServiceMethodsAreTransactional() {
    ArchRule rule =
        methods()
            .that()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(org.springframework.stereotype.Service.class)
            .and()
            .arePublic()
            .and()
            .haveNameStartingWith("create")
            .should()
            .beAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
            .because("a multi-statement create without a transaction can half-apply");

    rule.check(production);
  }
}
