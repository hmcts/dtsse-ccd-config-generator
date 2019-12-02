package ccd.sdk;

import ccd.sdk.generator.ConfigGenerator;
import ccd.sdk.types.ComplexType;
import com.google.common.io.Resources;
import org.junit.Test;
import org.reflections.Reflections;
import org.skyscreamer.jsonassert.JSONAssert;

import java.nio.charset.Charset;
import java.util.Set;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

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
}
