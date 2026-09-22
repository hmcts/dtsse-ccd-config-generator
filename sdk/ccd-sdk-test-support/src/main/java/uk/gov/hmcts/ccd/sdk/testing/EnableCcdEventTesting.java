package uk.gov.hmcts.ccd.sdk.testing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;
import uk.gov.hmcts.ccd.sdk.impl.CcdEventRuntimeTestConfiguration;

/** Adds the typed event fixture to an application's existing Spring test context. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({CcdEventTestConfiguration.class, CcdEventRuntimeTestConfiguration.class})
public @interface EnableCcdEventTesting {
}
