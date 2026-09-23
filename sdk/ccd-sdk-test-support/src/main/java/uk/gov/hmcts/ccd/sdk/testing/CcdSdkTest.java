package uk.gov.hmcts.ccd.sdk.testing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Runs selected application CCD event and CaseView beans without starting the application. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(classes = CcdSdkTestApplication.class)
@CcdSdkPostgresTest
@Import(CcdSdkTestImportSelector.class)
@EnableJpaRepositories
@EntityScan
public @interface CcdSdkTest {

  /** Application base configs, events, case views and mapper configuration used by this test. */
  Class<?>[] components();

  /** A repository in each package needed by the selected case views. */
  @AliasFor(annotation = EnableJpaRepositories.class, attribute = "basePackageClasses")
  Class<?>[] repositories() default {};

  /** An entity in each package used by those repositories. */
  @AliasFor(annotation = EntityScan.class, attribute = "basePackageClasses")
  Class<?>[] entities() default {};
}
