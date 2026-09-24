package uk.gov.hmcts.reform.fpl.event;

import static uk.gov.hmcts.ccd.sdk.api.Permission.CRU;
import static uk.gov.hmcts.reform.fpl.enums.UserRole.HMCTS_ADMIN;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalEventId;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalStart;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalStartResponse;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalSubmit;
import uk.gov.hmcts.ccd.sdk.api.external.ExternalSubmitResponse;
import uk.gov.hmcts.reform.fpl.enums.State;
import uk.gov.hmcts.reform.fpl.enums.UserRole;
import uk.gov.hmcts.reform.fpl.model.CaseData;

/** An external event, driven by a bespoke frontend rather than EXUI. */
@Component
public class RecordPayment implements CCDConfig<CaseData, State, UserRole> {

  public static final ExternalEventId<PaymentDue, Payment> RECORD_PAYMENT =
      ExternalEventId.of("recordPayment", PaymentDue.class, Payment.class);

  /** What the payment page is sent. */
  public record PaymentDue(BigDecimal amount) {
  }

  /** What the payment page submits. */
  public record Payment(String reference, BigDecimal amount) {
  }

  @Override
  public void configureDecentralised(DecentralisedConfigBuilder<CaseData, State, UserRole> builder) {
    builder.externalEvent(RECORD_PAYMENT, this::submit)
        .forAllStates()
        .name("Record payment")
        .grant(CRU, HMCTS_ADMIN)
        .onStart(this::start);
  }

  private ExternalStartResponse<PaymentDue> start(ExternalStart start) {
    return ExternalStartResponse.started(new PaymentDue(BigDecimal.ZERO));
  }

  private ExternalSubmitResponse<State> submit(ExternalSubmit<Payment> submit) {
    return ExternalSubmitResponse.accepted("Payment recorded", "Recorded a payment");
  }
}
