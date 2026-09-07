package io.yupiik.ia.agent.core.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;

public record LlmConfiguration(
        @Property(defaultValue = "3", documentation = "Maximum retry attempts for 429/5xx and network errors.") int retriesMaxAttempts,
        @Property(defaultValue = "500L", documentation = "Base delay in milliseconds of the exponential backoff (with jitter).") long retriesBaseDelayMs,
        @Property(defaultValue = "0", documentation = "Client side requests per minute throttle. 0 disables the throttle.") int rateLimitRequestsPerMinute,
        @Property(defaultValue = "4", documentation = "Maximum number of concurrent in-flight LLM requests.") int maxConcurrent) {

}
