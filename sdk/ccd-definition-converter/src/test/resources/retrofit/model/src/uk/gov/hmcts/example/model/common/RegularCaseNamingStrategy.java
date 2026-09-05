package uk.gov.hmcts.example.model.common;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * A team's own naming strategy (probate declares one of these), referenced by
 * {@link CustomNamedAddress}. Its {@code translate} is arbitrary Java, so the converter cannot evaluate
 * it statically and must refuse to guess the serialised names of any class using it.
 */
public class RegularCaseNamingStrategy extends PropertyNamingStrategies.NamingBase {

  @Override
  public String translate(String propertyName) {
    return propertyName == null || propertyName.isEmpty() ? propertyName
        : propertyName.substring(0, 1).toUpperCase() + propertyName.substring(1);
  }
}
