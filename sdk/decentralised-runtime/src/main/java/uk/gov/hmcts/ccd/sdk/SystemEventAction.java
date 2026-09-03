package uk.gov.hmcts.ccd.sdk;

/**
 * Local database work performed as a system event.
 */
@FunctionalInterface
public interface SystemEventAction<State extends Enum<State>> {

  SystemEventResult<State> execute();
}
