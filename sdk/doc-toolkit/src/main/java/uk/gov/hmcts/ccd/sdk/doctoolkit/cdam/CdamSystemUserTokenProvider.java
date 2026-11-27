package uk.gov.hmcts.ccd.sdk.doctoolkit.cdam;

import java.util.function.Supplier;

/** Supplies the system user's Authorization header, including the {@code Bearer } prefix. */
@FunctionalInterface
public interface CdamSystemUserTokenProvider extends Supplier<String> {
}
