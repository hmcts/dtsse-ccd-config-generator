package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import uk.gov.hmcts.ccd.sdk.JsonUtils.AddMissing;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Tab;
import uk.gov.hmcts.ccd.sdk.api.Tab.TabBuilder;
import uk.gov.hmcts.ccd.sdk.api.TabField;

class CaseTypeTabGenerator {

  public static <T, R extends HasRole, S> void generate(File root, String caseType,
      ConfigBuilderImpl<T, S, R> builder) {

    List<Map<String, Object>> result = Lists.newArrayList();
    result.add(buildField(caseType, "CaseHistory", "caseHistory",
        "History", 1, 1));

    int tabDisplayOrder = 2;
    for (TabBuilder tb : builder.tabs) {
      Tab tab = tb.build();
      int tabFieldDisplayOrder = 1;
      for (TabField tabField : tab.getFields()) {
        Map<String, Object> field = buildField(caseType, tab.getTabID(), tabField.getId(),
            tab.getLabel(), tabDisplayOrder, tabFieldDisplayOrder++);
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
        result.add(field);
      }
      ++tabDisplayOrder;
    }

    Path tabDir = Paths.get(root.getPath(), "CaseTypeTab");
    tabDir.toFile().mkdirs();
    for (Map<String, Object> tab : result) {
      Path output = tabDir.resolve(tab.get("TabDisplayOrder") + "_" + tab.get("TabID") + ".json");
      JsonUtils.mergeInto(output, Lists.newArrayList(tab), new AddMissing(),
          "TabID", "CaseFieldID");
    }
  }

  private static Map<String, Object> buildField(String caseType, String tabId, String fieldId,
      String tabLabel, int displayOrder, int tabFieldDisplayOrder) {
    Map<String, Object> field = Maps.newHashMap();
    field.put("LiveFrom", "01/01/2017");
    field.put("CaseTypeID", caseType);
    field.put("Channel", "CaseWorker");
    field.put("TabID", tabId);
    field.put("TabLabel", tabLabel);
    field.put("TabDisplayOrder", displayOrder);
    field.put("TabFieldDisplayOrder", tabFieldDisplayOrder);
    field.put("CaseFieldID", fieldId);
    return field;
  }
}

