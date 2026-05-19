package uk.gov.hmcts.ccd.sdk;

import java.util.List;
import java.util.Map;

public interface CallbackResponse<T> {

  T getData();

  List<String> getErrors();

  List<String> getWarnings();

  String getState();

  Map<String, Object> getDataClassification();

  String getSecurityClassification();

  String getErrorMessageOverride();
}
