package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import uk.gov.hmcts.ccd.sdk.JsonUtils.AddMissing;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Tab;
import uk.gov.hmcts.ccd.sdk.api.Tab.TabBuilder;
import uk.gov.hmcts.ccd.sdk.api.TabField;

class CaseTypeTabGenerator {

  // The field type set from code always takes precedence,
  // so eg. if a field changes type it gets updated.
  private static final ImmutableSet<String> OVERWRITES_FIELDS = ImmutableSet.of();

  public static <T, R extends HasRole, S> void generate(File root, String caseType,
      ConfigBuilderImpl<T, S, R> builder) {

    List<Map<String, Object>> result = Lists.newArrayList();
    result.add(buildField(caseType, "CaseHistory", "caseHistory", "History", 1, 1, ""));

    List<Map<String, Object>> caseFields = Lists.newArrayList();

    int tabDisplayOrder = 2;

    for (TabBuilder<T, R> tb : builder.tabs) {
      Tab<T, R> tab = tb.build();
      List<String> roles = tab.getRorRolesAsString();

      // if no roles have been specified leave UserRole empty
      if (roles.isEmpty()) {
        roles.add("");
      }

      for (String role : roles) {
        addTab(caseType, result, caseFields, tabDisplayOrder++, tab, role);
      }
    }

    Path tabDir = Paths.get(root.getPath(), "CaseTypeTab");
    tabDir.toFile().mkdirs();
    for (Map<String, Object> tab : result) {
      Path output = tabDir.resolve(tab.get("TabDisplayOrder") + "_" + tab.get("TabID") + ".json");
      JsonUtils.mergeInto(output, Lists.newArrayList(tab), new AddMissing(),
          "TabID", "CaseFieldID");
    }
    Path path = Paths.get(root.getPath(), "CaseField.json");
    JsonUtils.mergeInto(path, caseFields, new JsonUtils.OverwriteSpecific(OVERWRITES_FIELDS), "ID");
  }

  private static <T, R extends HasRole> void addTab(String caseType, List<Map<String, Object>> result,
                                                    List<Map<String, Object>> caseFields,
                                                    int tabDisplayOrder, Tab<T, R> tab, String role) {
    int tabFieldDisplayOrder = 1;
    for (TabField tabField : tab.getFields()) {
      Map<String, Object> field = buildField(caseType, tab.getTabID() + role, tabField.getId(),
          tab.getLabel(), tabDisplayOrder, tabFieldDisplayOrder++, role);
      if (tab.getShowCondition() != null) {
        field.put("TabShowCondition", tab.getShowCondition());
        // Only set tab show condition on first field.
        tab.setShowCondition(null);
      }
      if (tabField.getShowCondition() != null) {
        field.put("FieldShowCondition", tabField.getShowCondition());
      }
      if (tabField.getDisplayContextParameter() != null) {
        field.put("DisplayContextParameter", tabField.getDisplayContextParameter());
      }
      if (tabField.getLabel() != null) {
        Map<String, Object> fieldInfo = buildLabelField(caseType, tabField.getId());
        fieldInfo.put("FieldType", "Label");
        fieldInfo.put("Label", tabField.getLabel());
        caseFields.add(fieldInfo);
      }
      result.add(field);
    }
  }

  private static Map<String, Object> buildField(String caseType, String tabId, String fieldId,
      String tabLabel, int displayOrder, int tabFieldDisplayOrder, String role) {
    Map<String, Object> field = Maps.newHashMap();
    field.put("LiveFrom", "01/01/2017");
    field.put("CaseTypeID", caseType);
    field.put("Channel", "CaseWorker");
    field.put("TabID", tabId);
    field.put("TabLabel", tabLabel);
    field.put("UserRole", tabFieldDisplayOrder == 1 ? role : "");
    field.put("TabDisplayOrder", displayOrder);
    field.put("TabFieldDisplayOrder", tabFieldDisplayOrder);
    field.put("CaseFieldID", fieldId);
    return field;
  }

  public static Map<String, Object> buildLabelField(String caseType, String id) {
    Map<String, Object> result = new Hashtable<>();
    result.put("LiveFrom", "01/01/2017");
    result.put("CaseTypeID", caseType);
    result.put("ID", id);
    result.put("SecurityClassification", "Public");
    return result;
  }
}

