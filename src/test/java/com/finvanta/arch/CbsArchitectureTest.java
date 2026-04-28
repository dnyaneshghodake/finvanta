package com.finvanta.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.Optional;
import java.util.regex.Pattern;

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
     * CBS invariant: {@code setAccountNumber(...)} on a persistence entity may
     * only be called from the service layer (and the CBS reference/bootstrap
     * helpers that seed test data). Controllers, mappers, DTOs, and batch jobs
     * must never mint an account number directly -- doing so bypasses
     * {@code CbsReferenceService.generateDepositAccountNumber(...)} and the
     * branch SOL + check-digit logic it encodes.
     *
     * <p>The legitimate callers on the {@code integration_prior_to_master_branch}
     * commit are:
     * <ul>
     *   <li>{@code com.finvanta.service.impl..} (legacy CASA/loan services)</li>
     *   <li>{@code com.finvanta.cbs.modules..service..} (refactored services)</li>
     *   <li>{@code com.finvanta.charge..} (charge kernel propagating parent account)</li>
     *   <li>{@code com.finvanta.batch..} (EOD snapshot copies from live entity)</li>
     * </ul>
     */
    @Test
    void setAccountNumber_onlyCalledFromServiceLayer() {
        ArchRule rule = noClasses()
                .that()
                .resideOutsideOfPackages(
                        "com.finvanta.service.impl..",
                        "com.finvanta.cbs.modules..",
                        "com.finvanta.charge..",
                        "com.finvanta.batch..",
                        "com.finvanta.legacy..")
                .should(callMethodByName(
                        "com.finvanta.domain.entity.DepositAccount", "setAccountNumber"))
                .orShould(callMethodByName(
                        "com.finvanta.domain.entity.LoanAccount", "setAccountNumber"))
                .because(
                        "CBS: account numbers must be minted by CbsReferenceService and set "
                                + "only inside the service layer. Writing accountNumber from a "
                                + "controller, mapper, or DTO bypasses SOL/check-digit rules.");
        rule.check(classes);
    }

    /**
     * CBS invariant: no string constant inside {@code com.finvanta.cbs..} may
     * look like a production account number. Hardcoded account numbers are a
     * common source of cross-environment data leaks and audit violations.
     *
     * <p>Rejects:
     * <ul>
     *   <li>10-20 consecutive digits (Indian CBS account numbers are 11-16 digits)</li>
     *   <li>2 leading letters followed by 8-18 digits (IBAN-like pattern)</li>
     * </ul>
     *
     * <p>Legitimate numeric constants (GL codes like {@code "1100"}, PIN code
     * lengths, phone lengths) are shorter than 10 digits and fall outside this
     * pattern. Tenant IDs / test UUIDs use hyphens and are also exempt.
     */
    @Test
    void cbsPackage_hasNoHardcodedAccountNumbers() {
        Pattern accountNumberLike = Pattern.compile("^([A-Z]{2}[0-9]{8,18}|[0-9]{10,20})$");
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("com.finvanta.cbs..")
                .should(new ArchCondition<JavaClass>(
                        "contain a string constant matching an account-number pattern") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        for (JavaField field : item.getFields()) {
                            if (!field.getModifiers().contains(
                                    com.tngtech.archunit.core.domain.JavaModifier.STATIC)) {
                                continue;
                            }
                            if (!field.getRawType().getName().equals("java.lang.String")) {
                                continue;
                            }
                            Optional<Object> value = valueOf(field);
                            if (value.isEmpty()) continue;
                            String s = value.get().toString();
                            if (accountNumberLike.matcher(s).matches()) {
                                events.add(SimpleConditionEvent.violated(
                                        field,
                                        "Field " + field.getFullName()
                                                + " = \"" + s + "\" looks like a hardcoded "
                                                + "account number. Mint via CbsReferenceService."));
                            }
                        }
                    }

                    private Optional<Object> valueOf(JavaField field) {
                        try {
                            java.lang.reflect.Field reflected = Class
                                    .forName(field.getOwner().getName())
                                    .getDeclaredField(field.getName());
                            reflected.setAccessible(true);
                            return Optional.ofNullable(reflected.get(null));
                        } catch (ReflectiveOperationException | LinkageError e) {
                            return Optional.empty();
                        }
                    }
                })
                .because(
                        "CBS: production account numbers must never be hardcoded. Any 10-20 "
                                + "digit literal in com.finvanta.cbs is either a real account "
                                + "number (data leak) or a fixture (belongs in src/test).");
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
