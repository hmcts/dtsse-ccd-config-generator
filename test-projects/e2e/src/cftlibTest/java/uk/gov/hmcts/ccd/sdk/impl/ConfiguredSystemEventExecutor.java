package uk.gov.hmcts.ccd.sdk.impl;

import org.springframework.context.ApplicationContext;
import uk.gov.hmcts.ccd.sdk.SystemEventExecutor;

/**
 * Builds a {@link SystemEventExecutor} configured with a fixed system user {@code id}, next to the
 * application's own executor, which the cftlib suite configures to look its ID up from IDAM.
 *
 * <p>One Spring context holds one set of {@code system-user} properties, and starting a second
 * application context in the cftlib suite would rerun the cftlib configurers. Which properties
 * create which executor is covered by {@code SystemEventExecutorConfigurationTest}.
 */
public final class ConfiguredSystemEventExecutor {

    private ConfiguredSystemEventExecutor() {
    }

    public static SystemEventExecutor create(
        ApplicationContext context,
        String id,
        String username,
        String firstName,
        String lastName
    ) {
        var executor = new SystemEventExecutorImpl(
            id,
            username,
            firstName,
            lastName,
            "",
            context.getBean(CaseEventTransactionCoordinator.class),
            context.getBean(CaseDataRepository.class),
            context.getBean(IdamService.class)
        );
        // The same post-processors as the application's executor, including its @Transactional proxy.
        return (SystemEventExecutor) context.getAutowireCapableBeanFactory()
            .initializeBean(executor, "configuredIdSystemEventExecutor");
    }
}
