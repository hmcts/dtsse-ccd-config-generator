package uk.gov.hmcts.ccd.sdk.generator;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.Event;
import uk.gov.hmcts.ccd.sdk.api.Field;
import uk.gov.hmcts.ccd.sdk.api.FieldCollection;
import uk.gov.hmcts.ccd.sdk.api.HasRole;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CaseEventToFieldsGeneratorTest {

  private CaseEventToFieldsGenerator<Object, Object, HasRole> underTest;

  @Mock
  private ResolvedCCDConfig<Object, Object, HasRole> resolvedCCDConfig;
  @Mock
  private Event<Object, HasRole, Object> event;

  @BeforeEach
  void setUp() {
    underTest = new CaseEventToFieldsGenerator<>();
  }

  @Test
  void shouldNotSortFieldsWhenWritingToJson() throws IOException {
    // Given
    File rootDir = Files.createTempDirectory("test").toFile();
    rootDir.deleteOnExit();

    when(resolvedCCDConfig.getEvents()).thenReturn(ImmutableMap.of("event1", event));
    FieldCollection fieldCollection = mock(FieldCollection.class);
    when(event.getFields()).thenReturn(fieldCollection);

    Field.FieldBuilder<?, ?, ?, ?> fieldBuilder1 = createMockFieldBuilder("field1");
    Field.FieldBuilder<?, ?, ?, ?> fieldBuilder2 = createMockFieldBuilder("field2");
    when(fieldCollection.getFields()).thenReturn(List.of(fieldBuilder1, fieldBuilder2));

    ArgumentCaptor<Boolean> sortEnabledCaptor = ArgumentCaptor.forClass(Boolean.class);

    // When
    try (MockedStatic<JsonUtils> mockedJsonUtils = mockStatic(JsonUtils.class)) {
      underTest.write(rootDir, resolvedCCDConfig);

      mockedJsonUtils.verify(() -> JsonUtils.mergeInto(any(), any(),
        any(), sortEnabledCaptor.capture(), eq("CaseFieldID")));
    }

    // Then
    assertThat(sortEnabledCaptor.getValue()).isFalse();
  }

  @SuppressWarnings({"unchecked"})
  private static Field.FieldBuilder<?, ?, ?, ?> createMockFieldBuilder(String fieldId) {
    Field.FieldBuilder<Object, Object, Object, Object> fieldBuilder = mock(Field.FieldBuilder.class);
    Field<Object, Object, Object, Object> field = mock(Field.class);
    when(fieldBuilder.build()).thenReturn(field);
    when(field.getId()).thenReturn(fieldId);

    return fieldBuilder;
  }

}
