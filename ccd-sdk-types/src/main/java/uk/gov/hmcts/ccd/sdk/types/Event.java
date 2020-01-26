package uk.gov.hmcts.ccd.sdk.types;

import com.google.common.base.CaseFormat;
import com.google.common.base.Joiner;
import com.google.common.collect.Maps;
import lombok.*;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

@Builder
@Data
public class Event<T, R extends Role, S> {
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


    public static class EventBuilder<T, R extends Role, S> {

        public static <T, R extends Role, S> EventBuilder<T, R, S> builder(Class dataClass) {
            EventBuilder<T, R, S> result = new EventBuilder<T, R, S>();
            result.dataClass = dataClass;
            result.grants = Maps.newHashMap();
            result.fields = FieldCollection.FieldCollectionBuilder.builder(null, dataClass);
            result.eventNumber = eventCount++;

            return result;
        }

        public FieldCollection.FieldCollectionBuilder<T, ?> fields() {
            return fields;
        }

        public EventBuilder<T, R, S> name(String n) {
            this.name = n;
            if (description == null) {
                description = n;
            }
            return this;
        }

        public EventBuilder<T, R, S> showEventNotes() {
            this.showEventNotes = true;
            return this;
        }

        public EventBuilder<T, R, S> showSummary(boolean show) {
            this.showSummary = show;
            return this;
        }

        public EventBuilder<T, R, S> showSummary() {
            this.showSummary = true;
            return this;
        }

        // Do not inherit role permissions from states.
        public EventBuilder<T, R, S> explicitGrants() {
            this.explicitGrants = true;
            return this;
        }

        EventBuilder<T, R, S> forStates(S... states) {
            return forState(states[0]);
        }

        protected EventBuilder<T, R, S> forState(String state) {
            this.preState = state;
            this.postState = state;
            return this;
        }

        EventBuilder<T, R, S> forState(S state) {
            this.preState = state.toString();
            this.postState = state.toString();
            return this;
        }

        EventBuilder<T, R, S> postState(S state) {
            this.postState = state.toString();
            return this;
        }

        EventBuilder<T, R, S> preState(S state) {
            if (null != state) {
                this.preState = state.toString();
            }
            return this;
        }

        public EventBuilder<T, R, S> grant(String crud, R... roles) {
            for (R role : roles) {
                grants.put(role.getRole(), crud);
            }

            return this;
        }

        private String webhookConvention;
        public EventBuilder<T, R, S> allWebhooks(String convention) {
            this.webhookConvention = convention;
            aboutToStartURL = "/" + convention + "/about-to-start";
            aboutToSubmitURL = "/" + convention + "/about-to-submit";
            submittedURL = "/" + convention + "/submitted";
            return this;
        }

        public EventBuilder<T, R, S> aboutToStartWebhook(String convention) {
            this.webhookConvention = convention;
            // Use snake case event ID by convention
            aboutToStartURL = "/" + getWebhookPathByConvention() + "/about-to-start";
            return this;
        }

        public EventBuilder<T, R, S> aboutToStartWebhook() {
            // Use snake case event ID by convention
            aboutToStartURL = "/" + getWebhookPathByConvention() + "/about-to-start";
            return this;
        }

        public EventBuilder<T, R, S> aboutToSubmitWebhook() {
            aboutToSubmitURL = "/" + getWebhookPathByConvention() + "/about-to-submit";
            return this;
        }

        public EventBuilder<T, R, S> submittedWebhook() {
            submittedURL = "/" + getWebhookPathByConvention() + "/submitted";
            return this;
        }

        public EventBuilder<T, R, S> midEventWebhook() {
            midEventURL = "/" + getWebhookPathByConvention() + "/mid-event";
            return this;
        }

        public EventBuilder<T, R, S> retries(Integer... retries) {
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
