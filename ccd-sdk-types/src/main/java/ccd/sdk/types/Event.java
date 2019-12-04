package ccd.sdk.types;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;

@Builder
@Data
public class Event {
    private String id;
    private String name;
    private String preState;
    private String postState;
    private String description;
    private String aboutToStartURL;
    private String aboutToSubmitURL;
    private String submittedURL;
    private String retries;
    @Builder.Default
    private int displayOrder = -1;

    public static class EventBuilder {
        public EventBuilder forState(String state) {
            this.preState = state;
            this.postState = state;
            return this;
        }
    }
}
