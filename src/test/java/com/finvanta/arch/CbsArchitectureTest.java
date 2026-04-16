package com.finvanta.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * CBS Architecture Guards per Finacle/Temenos Tier-1 engineering invariants.
 *
 * <p>Enforces architectural rules that cannot be expressed in the Java type
 * system. Every rule here maps to a finding from the pre-Phase-3 audit
 * ("M7 ArchUnit guards") and represents a Tier-1 CBS invariant that must not
 * silently regress:
 *
 * <ol>
 *   <li><b>Single posting point:</b> only {@code TransactionEngine} may call
 *       {@code AccountingService.postJournalEntry(...)}. Every other financial
 *       posting MUST route through {@code TransactionEngine.execute(...)} so
 *       the 10-step validation chain (tenant, branch, limits, calendar, audit,
 *       idempotency, GL posting, ledger append, engine-token guard) runs.</li>
 *   <li><b>ENGINE_TOKEN guard:</b> the raw token helpers on
 *       {@code AccountingService} must only be touched by the transaction
 *       engine package. Any service that generates/clears engine tokens has
 *       bypassed the engine.</li>
 *   <li><b>Legacy isolation:</b> classes inside {@code com.finvanta.legacy}
 *       must not be referenced from production service / controller code.
 *       They exist only to preserve history during the Phase 1 removal.</li>
 *   <li><b>Repository boundary:</b> JPA repositories must live in
 *       {@code com.finvanta.repository} so a codebase-wide scan can verify
 *       tenant-filter coverage at startup.</li>
 *   <li><b>JPA leak:</b> controllers must not import {@code jakarta.persistence.*}.
 *       Persistence annotations belong on entities, not on request handlers.</li>
 * </ol>
 *
 * <p>Every rule is a JUnit 5 test, so violations break CI and cannot be
 * silently merged. The rules ignore legacy packages and the Lombok-generated
 * classes that shadow the production code during compile.
 */
class CbsArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void loadClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages("com.finvanta");
    }

    /**
     * CBS invariant: {@code AccountingService.postJournalEntry} is the single
     * write-path into the GL. Only the transaction engine may call it so every
     * posting goes through the 10-step validation chain.
     */
    @Test
    void postJournalEntry_onlyCalledByTransactionEngine() {
        ArchRule rule = noClasses()
                .that()
                .resideOutsideOfPackage("com.finvanta.accounting..")
                .and()
                .doNotHaveSimpleName("TransactionEngine")
                .should(callMethodByName(
                        "com.finvanta.accounting.AccountingService",
                        "postJournalEntry"))
                .because(
                        "CBS: only TransactionEngine may post to the GL -- every other "
                                + "posting must route through TransactionEngine.execute(). "
                                + "Direct AccountingService.postJournalEntry() bypasses the "
                                + "10-step validation chain.");
        rule.check(classes);
    }

    /**
     * CBS invariant: the ENGINE_TOKEN ceremony is internal plumbing of the
     * transaction engine. Any production class that generates or clears the
     * token outside {@code com.finvanta.accounting} / the engine has reached
     * into the private API.
     */
    @Test
    void engineTokenHelpers_notUsedOutsideEngine() {
        ArchRule rule = noClasses()
                .that()
                .resideOutsideOfPackage("com.finvanta.accounting..")
                .and()
                .doNotHaveSimpleName("TransactionEngine")
                .should(callMethodByName(
                        "com.finvanta.accounting.AccountingService", "generateEngineToken"))
                .orShould(callMethodByName(
                        "com.finvanta.accounting.AccountingService", "clearEngineToken"))
                .because(
                        "CBS: ENGINE_TOKEN helpers are internal to the TransactionEngine "
                                + "posting spine. Calling them from a service bypasses the engine.");
        rule.check(classes);
    }

    /**
     * ArchUnit {@link ArchCondition} that matches any method call to a given
     * owner class + method name, irrespective of parameter types. This is the
     * Tier-1 pattern for enforcing "nobody outside X may invoke Y.z(...)"
     * across overloaded signatures without having to enumerate every overload
     * and without depending on internal parameter-record types (which refactor
     * naturally between phases).
     */
    private static ArchCondition<com.tngtech.archunit.core.domain.JavaClass>
            callMethodByName(String ownerFqn, String methodName) {
        return new ArchCondition<>(
                "call " + ownerFqn + "." + methodName + "(..)") {
            @Override
            public void check(
                    com.tngtech.archunit.core.domain.JavaClass item,
                    ConditionEvents events) {
                for (JavaMethodCall call : item.getMethodCallsFromSelf()) {
                    if (call.getTargetOwner().getFullName().equals(ownerFqn)
                            && call.getTarget().getName().equals(methodName)) {
                        events.add(SimpleConditionEvent.violated(
                                call,
                                call.getDescription()));
                    }
                }
            }
        };
    }

    /**
     * Classes under {@code com.finvanta.legacy} are preserved for history only.
     * They must not be referenced from active production code.
     */
    @Test
    void legacyPackage_notDependedOnFromProduction() {
        ArchRule rule = noClasses()
                .that()
                .resideOutsideOfPackages("com.finvanta.legacy..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("com.finvanta.legacy..")
                .because(
                        "CBS: com.finvanta.legacy holds Phase-1 deprecated services. "
                                + "Depending on them re-introduces divergent code paths.");
        rule.check(classes);
    }

    /**
     * JPA repositories must live in {@code com.finvanta.repository}. This is a
     * pre-requisite for the tenant-filter scan that enforces multi-tenant isolation.
     */
    @Test
    void repositories_liveInRepositoryPackage() {
        ArchRule rule = classes()
                .that()
                .areAssignableTo(org.springframework.data.repository.Repository.class)
                .and()
                .areInterfaces()
                .should()
                .resideInAPackage("com.finvanta.repository..")
                .because(
                        "CBS: repository discovery + tenant-filter registration assumes "
                                + "every Spring Data repository is under com.finvanta.repository.");
        rule.check(classes);
    }

    /**
     * Controllers should not import {@code jakarta.persistence.*}. Persistence
     * concerns belong on entities and repositories, not on HTTP request handlers.
     */
    @Test
    void controllers_doNotImportJakartaPersistence() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("com.finvanta.controller..")
                .or()
                .resideInAPackage("com.finvanta.api..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("jakarta.persistence..")
                .because(
                        "CBS: controllers must delegate persistence to services/repositories. "
                                + "Importing jakarta.persistence.* in a controller is a layering leak.");
        rule.check(classes);
    }
}
