package uk.gov.hmcts.ccd.sdk;

import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class CallbackValidationException extends RuntimeException {
    private final List<String> errors;
    private final List<String> warnings;

}
