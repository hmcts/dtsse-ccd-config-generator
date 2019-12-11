package ccd.sdk;

import ccd.sdk.generator.ConfigGenerator;
import ccd.sdk.types.DisplayContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.Resources;
import org.apache.commons.io.FileUtils;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.reflections.Reflections;
import org.skyscreamer.jsonassert.JSONCompare;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.skyscreamer.jsonassert.JSONCompareResult;
import uk.gov.hmcts.reform.fpl.model.CaseData;
import uk.gov.hmcts.reform.fpl.model.CaseState;
import uk.gov.hmcts.reform.fpl.model.HearingBooking;
import uk.gov.hmcts.reform.fpl.model.common.Element;

import java.io.File;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.List;
import java.util.function.Function;

import static ccd.sdk.generator.Builder.builder;

public class ConfigGenerationTests {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    ConfigGenerator generator;
    Reflections reflections;

    @Before
    public void before() {
        reflections = new Reflections("uk.gov.hmcts");
        generator = new ConfigGenerator(reflections, temp.getRoot());
        generator.generate("CARE_SUPERVISION_EPO");
    }

    @Test
    public void solicitor() throws Exception {
        assertEquals("ComplexTypes/Solicitor.json");
    }

    @Test
    public void generatesStateOpen() {
        assertEquals("CaseEvent/Open.json");
    }

    @Test
    public void generatesSingleCaseEventToField() {
        assertEquals("CaseEventToFields/enterParentingFactors.json");
    }

    @Test
    public void handlesEventForMultipleStates() {
        assertEquals("CaseEventToFields/amendAttendingHearing.json");
        assertEquals("CaseEventToFields/amendAttendingHearing-PREPARE_FOR_HEARING.json");
        assertEquals("CaseEventToFields/amendAttendingHearingGatekeeping.json");
    }

    @Test
    public void generatesAllCaseEventToField() {
        URL u = Resources.getResource("ccd-definition/CaseEventToFields");
        for (File file : new File(u.getPath()).listFiles()) {
            assertEquals("CaseEventToFields/" + file.getName());
        }
    }

    @Test
    public void generatesCaseEventToComplexTypes() {
        assertEquals("CaseEventToComplexTypes/hearingBookingDetails/hearingBookingDetails.json");;
        assertEquals("CaseEventToComplexTypes/hearingBookingDetails/hearingBookingDetailsGatekeeping.json");;
        assertEquals("CaseEventToComplexTypes/hearingBookingDetails/hearingBookingDetails-PREPARE_FOR_HEARING.json");;

        assertEquals("CaseEventToComplexTypes/createC21Order/createC21Order.json");;
        assertEquals("CaseEventToComplexTypes/createC21Order/createC21OrderGatekeeping.json");

        assertEquals("CaseEventToComplexTypes/draftCMO/draftCMO.json");

        assertEquals("CaseEventToComplexTypes/createNoticeOfProceedings/createNoticeOfProceedings.json");
        assertEquals("CaseEventToComplexTypes/createNoticeOfProceedings/createNoticeOfProceedings-PREPARE_FOR_HEARING.json");
        assertEquals("CaseEventToComplexTypes/createNoticeOfProceedings/createNoticeOfProceedingsGatekeeping.json");

        assertEquals("CaseEventToComplexTypes/uploadC2/uploadC2.json");
        assertEquals("CaseEventToComplexTypes/uploadC2/uploadC2-PREPARE_FOR_HEARING.json");
        assertEquals("CaseEventToComplexTypes/uploadC2/uploadC2Gatekeeping.json");
    }

    // This will only pass once everything else is finished.
    @Ignore
    @Test
    public void generatesAuthorisationCaseEvent() {
        assertEquals("AuthorisationCaseEvent/AuthorisationCaseEvent.json");
    }

    @Test
    public void foo() {
        Function<CaseData, List<Element<HearingBooking>>> foo = CaseData::getHearingDetails;

        builder(CaseData.class, CaseState.class)
                .event(CaseState.Open, CaseState.Submitted)
                .field(x -> x.getCaseName(), DisplayContext.Mandatory)
                .field(x -> x.getC21Order(), DisplayContext.Mandatory)
                .field(x -> x.getHearingDetails(), this::renderSolicitor);
    }

    private void assertEquals(String jsonPath) {
        try {
            System.out.println("Comparing " + jsonPath);
            String expected = Resources.toString(Resources.getResource("ccd-definition/" + jsonPath), Charset.defaultCharset());
            String actual = FileUtils.readFileToString(new File(temp.getRoot(), jsonPath), Charset.defaultCharset());
            JSONCompareResult result = JSONCompare.compareJSON(expected, actual, JSONCompareMode.LENIENT);
            if (result.failed()) {
                System.out.println(result.toString());

                ObjectMapper mapper = new ObjectMapper();
                Object json = mapper.readValue(actual, Object.class);
                String indented = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
                System.out.println(indented);

                throw new RuntimeException("Compare failed for " + jsonPath);
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void renderSolicitor(HearingBooking solicitor) {
        solicitor.getStartDate();
        solicitor.getType();
    }
}
