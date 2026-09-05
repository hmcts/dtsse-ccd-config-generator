package uk.gov.hmcts.example.model.common;

/**
 * A collection-element wrapper named for the definition type {@code firstSharedCT}, holding the shared
 * payload {@link SharedDetails}. Together with {@link SecondSharedCT} it reproduces sscs's
 * one-payload-many-definition-types shape: the value class can carry only one
 * {@code @ComplexType(name)}, so the SECOND definition type to reach it must be reported as a gap.
 */
public class FirstSharedCT {

  private SharedDetails value;
}
