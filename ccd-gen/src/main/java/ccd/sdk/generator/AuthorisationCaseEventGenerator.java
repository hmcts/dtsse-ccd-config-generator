package ccd.sdk.generator;

import ccd.sdk.ConfigBuilderImpl;
import ccd.sdk.Utils;
import ccd.sdk.types.Event;
import ccd.sdk.types.Role;
import com.google.common.collect.*;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class AuthorisationCaseEventGenerator {

    public static <T, S, R extends Role> void generate(File root, List<Event> expandedEvents, ConfigBuilderImpl<T, S, R> builder) {
        List<Map<String, String>> entries = Lists.newArrayList();

        Table<String, String, String> eventRolePermissions = builder.explicit;
        for (Event event : expandedEvents) {
            // Add any state based role permissions unless event permits only explicit grants.
            if (!event.isExplicitGrants()) {
                Map<String, String> roles = builder.stateRoles.row(event.getPostState());
                for (String role : roles.keySet()) {
                    if (!builder.stateRoleblacklist.containsEntry(event.getPostState(), role)) {
                        eventRolePermissions.put(event.getId(), role, roles.get(role));
                    }
                }
            }
            // Set event level permissions, overriding state level where set.
            Map<String, String> grants = event.getGrants();
            for (String role : grants.keySet()) {
                if (!builder.stateRoleblacklist.containsEntry(event.getPostState(), role)) {
                    if (!eventRolePermissions.contains(event.getId(), role)) {
                        eventRolePermissions.put(event.getId(), role, grants.get(role));
                    }
                }
            }
        }
        for (Table.Cell<String, String, String> cell : eventRolePermissions.cellSet()) {
            if (cell.getValue().length() > 0) {
                Map<String, String> entry = Maps.newHashMap();
                entries.add(entry);
                entry.put("LiveFrom", "01/01/2017");
                entry.put("CaseTypeID", builder.caseType);
                entry.put("CaseEventID", cell.getRowKey());
                entry.put("UserRole", cell.getColumnKey());
                entry.put("CRUD", cell.getValue());
            }
        }

        File folder = new File(root.getPath(), "AuthorisationCaseEvent");
        folder.mkdir();

        Path output = Paths.get(folder.getPath(), "AuthorisationCaseEvent.json");
        Utils.writeFile(output, Utils.serialise(entries));
    }
}
