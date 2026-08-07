package com.protify.portfolio.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * TEST_PLAN.md §5 · day-4-dev-A.md D4-A1. ADR-0003 bought the {@code core → platform} boundary
 * with a 5-module Maven reactor, so a wrong import was a compile error. ADR-0012 collapsed that
 * to one module and said so explicitly: <i>"the convention still stands, but review is now the
 * only thing checking it, until an ArchUnit rule exists"</i>. This class is that rule. It is not
 * a redundant second check any more — it is the only automated one left.
 *
 * <p>{@code DoNotIncludeTests} because these are rules about production code. Test classes
 * legitimately reach across every boundary: {@code MidHistoryDeleteIT} builds repositories by
 * hand, {@code ValuationServiceTest} mocks ports.
 */
@AnalyzeClasses(packages = "com.protify.portfolio", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** Was {@code portfolio-core}: the domain — money maths, projections, the write path. */
    private static final String[] CORE = {
            "com.protify.portfolio.common..",
            "com.protify.portfolio.support..",
            "com.protify.portfolio.instrument..",
            "com.protify.portfolio.portfolio..",
            "com.protify.portfolio.transaction..",
            "com.protify.portfolio.holding..",
            "com.protify.portfolio.valuation..",
    };

    /** Was {@code portfolio-platform}: everything that talks to the outside world. */
    private static final String[] PLATFORM = {
            "com.protify.portfolio.marketdata..",
            "com.protify.portfolio.fx..",
            "com.protify.portfolio.config..",
            "com.protify.portfolio.security..",
            "com.protify.portfolio.user..",
            "com.protify.portfolio.health..",
    };

    /** Persistence types that must never be returned to, or accepted from, an HTTP caller
     * (CLAUDE.md non-negotiable #5). */
    private static final String[] DOMAIN_TYPES = {
            "com.protify.portfolio.instrument..",
            "com.protify.portfolio.portfolio..",
            "com.protify.portfolio.transaction..",
            "com.protify.portfolio.holding..",
            "com.protify.portfolio.valuation..",
    };

    /**
     * The module boundary ADR-0012 stopped compiling. Domain code reaches market data and FX
     * only through {@code valuation.spi.PriceLookup}/{@code FxConversion}.
     *
     * <p><b>The {@code *Adapter} exemption is the whole design, not a loophole.</b>
     * {@code common} froze at the end of Day 1, so the ports could not be declared there; they
     * live with their consumer instead, and exactly two classes —
     * {@code MarketDataPriceLookupAdapter} and {@code FxRateConversionAdapter} — implement them
     * against Dev B's packages. Concentrating the coupling in two translation-only classes named
     * by a convention this rule enforces is what makes it reviewable; scattered through
     * {@code ValuationService} and {@code PerformanceService}, as it was before Day 4, it was not.
     */
    @ArchTest
    static final ArchRule coreMustNotDependOnPlatform = noClasses()
            .that().resideInAnyPackage(CORE)
            .and().haveSimpleNameNotEndingWith("Adapter")
            .should().dependOnClassesThat().resideInAnyPackage(PLATFORM)
            .because("core reaches market data and FX only through the valuation.spi ports "
                    + "(ADR-0012 removed the module graph that used to make this a compile error)");

    /**
     * CLAUDE.md non-negotiable #6, mechanically.
     *
     * <p>TEST_PLAN.md §5 writes this as {@code resideOutsideOfPackage("..repository..")}, which
     * predates ADR-0012's feature-first layout: there is no {@code repository} package: a
     * repository is a {@code *Repository} class inside its own feature package. Same rule,
     * expressed against the layout that actually exists.
     */
    @ArchTest
    static final ArchRule jdbcTemplateOnlyInRepositories = noClasses()
            .that().haveSimpleNameNotEndingWith("Repository")
            .should().dependOnClassesThat().belongToAnyOf(JdbcTemplate.class, NamedParameterJdbcTemplate.class)
            .because("no JdbcTemplate outside a *Repository — business logic does not write SQL");

    /**
     * CLAUDE.md non-negotiable #1. Broader than §5's {@code haveRawParameterTypes(double.class,
     * float.class)}, which matches only a method whose parameter list is <i>exactly</i>
     * {@code (double, float)} — that would pass happily on {@code BigDecimal convert(double)}.
     * This checks every parameter and the return type, boxed included.
     */
    @ArchTest
    static final ArchRule noCoreMethodUsesDoubleOrFloat = noMethods()
            .that().areDeclaredInClassesThat().resideInAnyPackage(CORE)
            .should(useBinaryFloatingPoint())
            .because("money is BigDecimal; a double anywhere near it silently loses pennies");

    /** CLAUDE.md non-negotiable #5 — a persistence type must never cross the controller
     * boundary, including wrapped in a {@code ResponseEntity} or a {@code List}. */
    @ArchTest
    static final ArchRule controllersMustNotReturnDomainTypes = noMethods()
            .that().areDeclaredInClassesThat().haveSimpleNameEndingWith("Controller")
            .and().arePublic()
            .should(returnATypeIn(DOMAIN_TYPES))
            .because("controllers return DTOs; leaking a domain record couples the API to the schema");

    private static ArchCondition<JavaMethod> useBinaryFloatingPoint() {
        Set<String> banned = Set.of("double", "float", "java.lang.Double", "java.lang.Float");
        return new ArchCondition<>("use double or float") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                List<JavaType> types = new ArrayList<>(method.getParameterTypes());
                types.add(method.getReturnType());
                for (JavaType type : types) {
                    if (banned.contains(type.toErasure().getFullName())) {
                        events.add(SimpleConditionEvent.satisfied(method,
                                method.getFullName() + " uses " + type.toErasure().getName()));
                        return;
                    }
                }
            }
        };
    }

    /** Unwraps one level of generics, so {@code ResponseEntity<Holding>} and
     * {@code List<Txn>} are caught and not only a bare {@code Holding}. */
    private static ArchCondition<JavaMethod> returnATypeIn(String[] packages) {
        DescribedPredicate<JavaClass> inPackages = JavaClass.Predicates.resideInAnyPackage(packages);
        return new ArchCondition<>("return a domain type") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                for (JavaClass candidate : returnedTypes(method.getReturnType())) {
                    if (inPackages.test(candidate)) {
                        events.add(SimpleConditionEvent.satisfied(method,
                                method.getFullName() + " returns domain type " + candidate.getName()));
                        return;
                    }
                }
            }
        };
    }

    private static List<JavaClass> returnedTypes(JavaType returnType) {
        List<JavaClass> types = new ArrayList<>();
        types.add(returnType.toErasure());
        if (returnType instanceof JavaParameterizedType parameterized) {
            parameterized.getActualTypeArguments().forEach(argument -> types.add(argument.toErasure()));
        }
        return types;
    }
}
