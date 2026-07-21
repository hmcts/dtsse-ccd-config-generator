package uk.gov.hmcts.ccd.sdk.retention;

interface CcdRetainAndDisposeClient {

  void moveToTerminalState(RetainAndDisposeCase disposalCase, String eventId, String terminalState);

  boolean exists(RetainAndDisposeCase disposalCase);
}
