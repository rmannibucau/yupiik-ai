package io.yupiik.ia.agent.tui;

import io.yupiik.fusion.framework.build.api.configuration.Property;

public record TuiTranscriptConfiguration(
        @Property(defaultValue = "10000000L", documentation = "Maximum number of characters kept in the in-memory conversation transcript. When exceeded, the oldest content is truncated from the beginning (hidden thinking/tool blocks stay retained and can be shown back).") long maxHistoryCharacters) {
}
