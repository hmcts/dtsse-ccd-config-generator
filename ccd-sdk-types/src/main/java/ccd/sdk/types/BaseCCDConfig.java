package ccd.sdk.types;

public abstract class BaseCCDConfig<T, S, R extends Role> implements CCDConfig<T, S, R>, ConfigBuilder<T, S, R> {
    private ConfigBuilder<T, S, R> builder;

    @Override
    public void configure(ConfigBuilder<T, S, R> builder) {
        this.builder = builder;
        configure();
    }

    protected abstract void configure();

    @Override
    public EventTypeBuilder<T, S> event(String id) {
        return builder.event(id);
    }

    @Override
    public void caseType(String caseType) {
        builder.caseType(caseType);
    }

    @Override
    public void grant(S state, String permissions, R role) {
        builder.grant(state, permissions, role);
    }

    @Override
    public void blacklist(S state, R... role) {
        builder.blacklist(state, role);
    }

    @Override
    public void explicitState(String eventId, R role, String crud) {
        builder.explicitState(eventId, role, crud);
    }

    @Override
    public void prefix(S state, String prefix) {
        builder.prefix(state, prefix);
    }
}
