package uk.gov.hmcts.divorce.stubs;

import java.util.List;
import java.util.Map;

import static java.util.Map.entry;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RefDataStubController {

    private static final String SERVICE = "DIVORCE";
    private static final String LOCATION_ID = "336559";
    private static final String LOCATION_NAME = "Glasgow Tribunals Centre";
    private static final String REGION_ID = "1";
    private static final String USER_EMAIL = "TEST_CASE_WORKER_USER@mailinator.com";
    private static final String USER_ID = "74779774-2fc4-32c9-a842-f8d0aa6e770a";

    @GetMapping(value = "/refdata/internal/staff/usersByServiceName", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> usersByServiceName(@RequestParam("ccd_service_names") String ccdServiceNames) {
        Map<String, Object> staffProfile = Map.ofEntries(
            entry("id", USER_ID),
            entry("first_name", "Test"),
            entry("last_name", "Caseworker"),
            entry("region_id", REGION_ID),
            entry("user_type", "Caseworker"),
            entry("idam_roles", "caseworker-divorce"),
            entry("suspended", "N"),
            entry("case_allocator", "N"),
            entry("task_supervisor", "N"),
            entry("staff_admin", "N"),
            entry("created_time", "2024-01-01T00:00:00Z"),
            entry("last_updated_time", "2024-01-01T00:00:00Z"),
            entry("email_id", USER_EMAIL),
            entry("region", "Scotland"),
            entry("base_location", List.of(Map.of(
                "location_id", LOCATION_ID,
                "location", LOCATION_NAME,
                "is_primary", true,
                "services", List.of(SERVICE)
            ))),
            entry("user_type_id", "1"),
            entry("role", List.of()),
            entry("skills", List.of()),
            entry("work_area", List.of())
        );

        Map<String, Object> staffUser = Map.of(
            "ccd_service_name", SERVICE,
            "staff_profile", staffProfile
        );

        return List.of(staffUser);
    }

    @GetMapping(value = "/refdata/location/court-venues/services", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> courtVenuesByService(@RequestParam("service_code") String serviceCode) {
        return Map.of("court_venues", buildCourtVenueList());
    }

    @GetMapping(value = "/refdata/location/court-venues", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> courtVenuesById(@RequestParam("epimms_id") String epimmsId) {
        return buildCourtVenueList();
    }

    private List<Map<String, Object>> buildCourtVenueList() {
        return List.of(Map.of(
            "epimms_id", LOCATION_ID,
            "site_name", LOCATION_NAME,
            "is_case_management_location", "Y",
            "region_id", REGION_ID
        ));
    }
}
