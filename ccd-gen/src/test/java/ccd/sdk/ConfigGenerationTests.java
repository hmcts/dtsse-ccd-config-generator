package ccd.sdk;

import ccd.sdk.generator.ConfigGenerator;
import ccd.sdk.types.DisplayContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.Resources;
import de.cronn.reflection.util.PropertyUtils;
import org.apache.commons.io.FileUtils;
import org.hamcrest.core.Is;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.reflections.Reflections;
import org.skyscreamer.jsonassert.JSONAssert;
import uk.gov.hmcts.reform.fpl.model.CaseData;
import uk.gov.hmcts.reform.fpl.model.CaseState;
import uk.gov.hmcts.reform.fpl.model.HearingBooking;
import uk.gov.hmcts.reform.fpl.model.common.Element;

import java.io.File;
import java.nio.charset.Charset;
import java.util.List;
import java.util.function.Function;

import static ccd.sdk.generator.Builder.builder;
import static org.hamcrest.MatcherAssert.assertThat;

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
    public void generatesCaseEventToField() {
        assertEquals("CaseEventToFields/enterParentingFactors.json");
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
            String expected = Resources.toString(Resources.getResource("ccd-definition/" + jsonPath), Charset.defaultCharset());
            String actual = FileUtils.readFileToString(new File(temp.getRoot(), jsonPath), Charset.defaultCharset());
//            ObjectMapper mapper = new ObjectMapper();
//            Object json = mapper.readValue(actual, Object.class);
//            String indented = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
//            System.out.println(indented);

            JSONAssert.assertEquals(expected, actual, false);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void renderSolicitor(HearingBooking solicitor) {
        solicitor.getStartDate();
        solicitor.getType();
    }
}
