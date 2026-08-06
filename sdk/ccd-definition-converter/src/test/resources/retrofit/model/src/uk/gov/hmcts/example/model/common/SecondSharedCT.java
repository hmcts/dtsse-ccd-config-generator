package uk.gov.hmcts.example.model.common;

/**
 * The second wrapper over the shared payload {@link SharedDetails} — see {@link FirstSharedCT}. Its own
 * {@code generate = false} suppression is still applied (each wrapper is a distinct class, so there is
 * no collision there); only the shared VALUE class collides.
 */
public class SecondSharedCT {

  private SharedDetails value;
}
