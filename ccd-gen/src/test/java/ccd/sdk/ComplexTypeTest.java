package ccd.sdk;

import ccd.sdk.types.ComplexType;
import com.google.common.io.Resources;
import org.json.JSONException;
import org.junit.Test;
import org.reflections.Reflections;
import org.reflections.util.ClasspathHelper;
import org.skyscreamer.jsonassert.JSONAssert;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Set;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;

public class ComplexTypeTest {
    @Test
    public void solicitor() {

        Reflections reflections = new Reflections("uk.gov.hmcts");
        Set<Class<?>> types = reflections.getTypesAnnotatedWith(ComplexType.class);
        assertThat(types.size(), equalTo(1));
    }
}
