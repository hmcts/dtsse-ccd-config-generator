package uk.gov.hmcts.ccd.sdk.diff;

import com.google.common.reflect.ClassPath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.Set;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

/**
 * The set of CCD complex-type IDs the SDK ships as built-in classes — derived by reflection over
 * {@code uk.gov.hmcts.ccd.sdk.type}, the SDK's own source of truth, rather than a hand-maintained
 * list that could drift from it.
 *
 * <p>A class in that package annotated {@code @ComplexType(generate = false)} is a platform type the
 * definition store already knows from the SDK jar at runtime: {@code ComplexTypeGenerator} skips it
 * (it emits no {@code ComplexTypes} rows), and {@code CaseFieldGenerator} resolves referencing
 * fields straight to the built-in class. The wire ID such a type is referenced by is its
 * {@code @ComplexType(name)} when set, else its simple class name — exactly how both generators
 * identify it. This is the same set the converter's {@code SdkPredefinedTypes} enumerates on the
 * link side; reflecting it here keeps the comparator's {@code PREDEFINED_COMPLEX_TYPE_REDECLARATION}
 * rule in lockstep with the SDK without duplicating the ID list.
 */
final class PredefinedComplexTypes {

    private static final String TYPE_PACKAGE = "uk.gov.hmcts.ccd.sdk.type";

    private static final Set<String> IDS = scan();

    private PredefinedComplexTypes() {
    }

    /**
     * Whether the ID names an SDK-predefined platform type (one the generator emits no
     * {@code ComplexTypes} rows for).
     *
     * @param id the ComplexTypes sheet ID
     * @return true when the SDK ships this type as a built-in class
     */
    static boolean isPredefined(String id) {
        return id != null && IDS.contains(id);
    }

    /**
     * The reflected set of predefined complex-type IDs, for diagnostics and tests.
     *
     * @return the wire IDs of every {@code @ComplexType(generate = false)} class in the SDK type
     *     package
     */
    static Set<String> ids() {
        return IDS;
    }

    private static Set<String> scan() {
        Set<String> ids = new LinkedHashSet<>();
        try {
            ClassPath classPath = ClassPath.from(PredefinedComplexTypes.class.getClassLoader());
            for (ClassPath.ClassInfo info : classPath.getTopLevelClasses(TYPE_PACKAGE)) {
                Class<?> type = info.load();
                ComplexType annotation = type.getAnnotation(ComplexType.class);
                if (annotation == null || annotation.generate()) {
                    continue;
                }
                String id = annotation.name().isEmpty() ? type.getSimpleName() : annotation.name();
                ids.add(id);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                "Failed to scan " + TYPE_PACKAGE + " for predefined complex types", e);
        }
        return ids;
    }
}
