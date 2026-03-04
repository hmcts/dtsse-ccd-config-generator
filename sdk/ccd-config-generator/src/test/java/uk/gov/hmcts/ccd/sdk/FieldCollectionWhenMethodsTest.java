package uk.gov.hmcts.ccd.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.Test;
import uk.gov.hmcts.ccd.sdk.api.Field;
import uk.gov.hmcts.ccd.sdk.api.FieldCollection;
import uk.gov.hmcts.ccd.sdk.api.TypedShowCondition;
import uk.gov.hmcts.ccd.sdk.type.ListValue;

public class FieldCollectionWhenMethodsTest {

  @Test
  public void shouldApplyTypedConditionToFieldMethods() {
    FieldCollection.FieldCollectionBuilder<TestData, String, Object> builder = createBuilder();
    TypedShowCondition condition = TypedShowCondition.when(TestData::getState).is("Submitted");

    builder.optionalWhen(TestData::getName, condition);
    builder.mandatoryWhen(TestData::getState, condition, true);
    builder.readonlyWhen(TestData::getName, condition, true);

    FieldCollection fields = builder.build();
    assertThat(fields.getFields()).hasSize(3);

    assertThat(fields.getFields().get(0).build().getShowCondition()).isEqualTo("state=\"Submitted\"");
    assertThat(fields.getFields().get(1).build().getShowCondition()).isEqualTo("state=\"Submitted\"");
    assertThat(fields.getFields().get(2).build().getShowCondition()).isEqualTo("state=\"Submitted\"");
    assertThat(fields.getFields().get(1).build().isRetainHiddenValue()).isTrue();
    assertThat(fields.getFields().get(2).build().isRetainHiddenValue()).isTrue();
  }

  @Test
  public void shouldApplyTypedConditionToPageAndLabelMethods() {
    FieldCollection.FieldCollectionBuilder<TestData, String, Object> builder = createBuilder();
    TypedShowCondition condition = TypedShowCondition.when(TestData::getState).is("Issued");

    builder.page("2")
        .showConditionWhen(condition)
        .labelWhen("statusLabel", "Status", condition, true);

    FieldCollection fields = builder.build();
    assertThat(fields.getPageShowConditions()).containsEntry("2", "state=\"Issued\"");

    Field<?, ?, ?, ?> labelField = fields.getExplicitFields().stream()
        .map(Field.FieldBuilder::build)
        .filter(field -> "statusLabel".equals(field.getId()))
        .findFirst()
        .orElseThrow();

    assertThat(labelField.getShowCondition()).isEqualTo("state=\"Issued\"");
    assertThat(labelField.isShowSummary()).isTrue();
  }

  @Test
  public void shouldApplyTypedConditionToListAndComplexMethods() {
    FieldCollection.FieldCollectionBuilder<TestData, String, Object> builder = createBuilder();
    TypedShowCondition condition = TypedShowCondition.when(TestData::getState).is("Review");

    builder.listWhen(TestData::getItems, condition).done();
    builder.complexWhen(TestData::getDetails, condition, "Details", "Details hint").done();

    FieldCollection fields = builder.build();
    assertThat(fields.getFields().stream().map(Field.FieldBuilder::build).map(Field::getShowCondition))
        .contains("state=\"Review\"");
  }

  private FieldCollection.FieldCollectionBuilder<TestData, String, Object> createBuilder() {
    return FieldCollection.FieldCollectionBuilder.builder(null, new Object(), TestData.class, new PropertyUtils());
  }

  private static class TestData {
    private String name;
    private String state;
    private List<ListValue<Item>> items;
    private Details details;

    public String getName() {
      return name;
    }

    public String getState() {
      return state;
    }

    public List<ListValue<Item>> getItems() {
      return items;
    }

    public Details getDetails() {
      return details;
    }
  }

  private static class Item {
    private String code;

    public String getCode() {
      return code;
    }
  }

  private static class Details {
    private String description;

    public String getDescription() {
      return description;
    }
  }
}
