package ccd.sdk;

import ccd.sdk.generator.ConfigGenerator;
import ccd.sdk.generator.Builder;
import ccd.sdk.types.ComplexType;
import ccd.sdk.generator.DisplayContext;
import com.google.common.io.Resources;
import org.junit.Test;
import org.reflections.Reflections;
import org.skyscreamer.jsonassert.JSONAssert;
import uk.gov.hmcts.reform.fpl.model.CaseData;
import uk.gov.hmcts.reform.fpl.model.CaseState;
import uk.gov.hmcts.reform.fpl.model.Solicitor;

import java.nio.charset.Charset;
import java.util.Set;

import static ccd.sdk.generator.Builder.builder;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

public class ComplexTypeTest {
    @Test
    public void solicitor() throws Exception {

        Reflections reflections = new Reflections("uk.gov.hmcts");
        Set<Class<?>> types = reflections.getTypesAnnotatedWith(ComplexType.class);
        assertThat(types.size(), equalTo(1));
        Class c = getClass().getClassLoader().loadClass("uk.gov.hmcts.reform.fpl.model.Solicitor");
        String expected = Resources.toString(Resources.getResource("ComplexTypes/Solicitor.json"), Charset.defaultCharset());

        JSONAssert.assertEquals(expected, ConfigGenerator.toComplexType(c), false);

    }


    @Test
    public void foo() {
        builder(CaseData.class, CaseState.class)
                .event(CaseState.Open, CaseState.Submitted)
                .field(x -> x.getSolicitors(), DisplayContext.Mandatory)
                .field(x -> x.getCaseName(), DisplayContext.Mandatory)
                .field(x -> x.getSolicitors(), this::renderSolicitor);
    }

    private void renderSolicitor(Solicitor solicitor) {
        solicitor.getMobile();
        solicitor.getEmail();
    }
}
