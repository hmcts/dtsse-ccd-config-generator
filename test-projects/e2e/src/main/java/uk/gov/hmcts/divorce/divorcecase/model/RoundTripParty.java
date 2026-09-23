package uk.gov.hmcts.divorce.divorcecase.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.type.AddressGlobalUK;
import uk.gov.hmcts.ccd.sdk.type.AddressUK;
import uk.gov.hmcts.ccd.sdk.type.CaseLink;
import uk.gov.hmcts.ccd.sdk.type.CaseLocation;
import uk.gov.hmcts.ccd.sdk.type.Document;
import uk.gov.hmcts.ccd.sdk.type.DynamicList;
import uk.gov.hmcts.ccd.sdk.type.DynamicMultiSelectList;
import uk.gov.hmcts.ccd.sdk.type.Flags;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.ccd.sdk.type.OrderSummary;
import uk.gov.hmcts.ccd.sdk.type.OrganisationPolicy;
import uk.gov.hmcts.ccd.sdk.type.ScannedDocument;
import uk.gov.hmcts.ccd.sdk.type.YesOrNo;

import java.time.LocalDate;
import java.util.List;

/**
 * Carries every SDK complex type through the same shapes services use, so round-trip tests can
 * prove case data survives Jackson. {@link CaseData} holds it both behind a prefixed
 * {@code @JsonUnwrapped} and as a plain nested complex type.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@Builder
public class RoundTripParty {

    @CCD(label = "Name")
    private String name;

    @CCD(label = "Document")
    private Document document;

    @CCD(label = "UK address")
    private AddressUK addressUk;

    @CCD(label = "Global UK address")
    private AddressGlobalUK addressGlobalUk;

    @CCD(label = "Organisation policy")
    private OrganisationPolicy<UserRole> organisationPolicy;

    @CCD(label = "Order summary")
    private OrderSummary orderSummary;

    @CCD(label = "Dynamic list")
    private DynamicList dynamicList;

    @CCD(label = "Dynamic multi-select list")
    private DynamicMultiSelectList dynamicMultiSelectList;

    @CCD(label = "Case link")
    private CaseLink caseLink;

    @CCD(label = "Case location")
    private CaseLocation caseLocation;

    @CCD(label = "Flags")
    private Flags flags;

    @CCD(label = "Scanned document")
    private ScannedDocument scannedDocument;

    @CCD(label = "Documents")
    private List<ListValue<Document>> documents;

    @CCD(label = "Service-owned document")
    private RoundTripDocument serviceDocument;

    @CCD(label = "Notification")
    private RoundTripNotification notification;

    @CCD(label = "Confirmed")
    private YesOrNo confirmed;

    @CCD(label = "Date")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate date;

    @JsonUnwrapped(prefix = "Solicitor")
    @Builder.Default
    @CCD
    private RoundTripSolicitor solicitor = new RoundTripSolicitor();
}
