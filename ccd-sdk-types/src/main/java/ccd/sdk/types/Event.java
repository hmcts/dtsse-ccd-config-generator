package ccd.sdk.types;

import com.google.common.base.CaseFormat;
import com.google.common.base.Joiner;
import com.google.common.collect.Maps;
import lombok.*;

import java.util.Map;

@Builder
@Data
public class Event<T> {
    @With
    private String id;
    // The same event can have a different ID if on different states.
    private String eventId;

    private String name;
    private String preState;
    private String postState;
    private String description;
    private String aboutToStartURL;
    private String aboutToSubmitURL;
    private String submittedURL;
    private String midEventURL;
    private String retries;
    private String[] states;
    private boolean explicitGrants;
    private boolean showSummary;
    private boolean showEventNotes;
    private int eventNumber;

    public void setEventID(String eventId) {
        this.eventId = eventId;
    }
    public String getEventID() {
        return this.eventId != null ? this.eventId : this.id;
    }

    public void name(String s) {
        name = s;
        if (null == description) {
            description = s;
        }
    }

    @ToString.Exclude
    private FieldCollection.FieldCollectionBuilder<T, ?> fields;

    @Builder.Default
    // TODO: don't always add.
    private String endButtonLabel = "Save and continue";
    @Builder.Default
    private int displayOrder = -1;

    private Map<String, String> grants;
    public Map<String, String> getGrants() {
        return grants;
    }

    private Class dataClass;
    private static int eventCount;


    public static class EventBuilder<T> {

        public static <T> EventBuilder<T> builder(Class dataClass) {
            EventBuilder<T> result = new EventBuilder<T>();
            result.dataClass = dataClass;
            result.grants = Maps.newHashMap();
            result.fields = FieldCollection.FieldCollectionBuilder.builder(null, dataClass);
            result.eventNumber = eventCount++;

            return result;
        }

        public FieldCollection.FieldCollectionBuilder<T, ?> fields() {
            return fields;
        }

        public EventBuilder<T> name(String n) {
            this.name = n;
            if (description == null) {
                description = n;
            }
            return this;
        }

        public EventBuilder<T> showEventNotes() {
            this.showEventNotes = true;
            return this;
        }

        public EventBuilder<T> showSummary(boolean show) {
            this.showSummary = show;
            return this;
        }

        public EventBuilder<T> showSummary() {
            this.showSummary = true;
            return this;
        }

        // Do not inherit role permissions from states.
        public EventBuilder<T> explicitGrants() {
            this.explicitGrants = true;
            return this;
        }

        EventBuilder<T> forStates(String... states) {
            this.states = states;
            return forState(states[0]);
        }

        EventBuilder<T> forState(String state) {
            this.preState = state;
            this.postState = state;
            return this;
        }

        EventBuilder<T> postState(String state) {
            this.postState = state;
            return this;
        }

        EventBuilder<T> preState(String state) {
            this.preState = state;
            return this;
        }

        public EventBuilder<T> grant(String crud, String... roles) {
            for (String role : roles) {
                grants.put(role, crud);
            }

            return this;
        }

        private String webhookConvention;
        public EventBuilder<T> allWebhooks(String convention) {
            this.webhookConvention = convention;
            aboutToStartURL = "/" + convention + "/about-to-start";
            aboutToSubmitURL = "/" + convention + "/about-to-submit";
            submittedURL = "/" + convention + "/submitted";
            return this;
        }

        public EventBuilder<T> aboutToStartWebhook(String convention) {
            this.webhookConvention = convention;
            // Use snake case event ID by convention
            aboutToStartURL = "/" + getWebhookPathByConvention() + "/about-to-start";
            return this;
        }

        public EventBuilder<T> aboutToStartWebhook() {
            // Use snake case event ID by convention
            aboutToStartURL = "/" + getWebhookPathByConvention() + "/about-to-start";
            return this;
        }

        public EventBuilder<T> aboutToSubmitWebhook() {
            aboutToSubmitURL = "/" + getWebhookPathByConvention() + "/about-to-submit";
            return this;
        }

        public EventBuilder<T> submittedWebhook() {
            submittedURL = "/" + getWebhookPathByConvention() + "/submitted";
            return this;
        }

        public EventBuilder<T> midEventWebhook() {
            midEventURL = "/" + getWebhookPathByConvention() + "/mid-event";
            return this;
        }

        public EventBuilder<T> retries(Integer... retries) {
            this.retries = Joiner.on(",").join(retries);
            return this;
        }

        private String getWebhookPathByConvention() {
            if (webhookConvention != null) {
                return webhookConvention;
            }
            return CaseFormat.LOWER_CAMEL.to(CaseFormat.LOWER_HYPHEN, eventId);
        }
    }
}
