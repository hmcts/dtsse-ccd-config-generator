package uk.gov.hmcts.ccd.sdk.conversion.spring;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * Matches when {@code ccd.conversion.docmosis.convert-endpoint} and {@code access-key} are both
 * set and not blank. Services set them from optional env vars, which bind as "" when unset.
 */
class ConversionDocmosisConfigured extends SpringBootCondition {

  @Override
  public ConditionOutcome getMatchOutcome(ConditionContext context,
      AnnotatedTypeMetadata metadata) {
    return isConfigured(context.getEnvironment())
        ? ConditionOutcome.match("ccd.conversion.docmosis convert endpoint and access key set")
        : ConditionOutcome.noMatch("ccd.conversion.docmosis convert endpoint or access key unset");
  }

  static boolean isConfigured(PropertyResolver properties) {
    return StringUtils.hasText(properties.getProperty("ccd.conversion.docmosis.convert-endpoint"))
        && StringUtils.hasText(properties.getProperty("ccd.conversion.docmosis.access-key"));
  }
}
