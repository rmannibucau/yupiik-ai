package io.yupiik.ia.agent.core.configuration;

public enum PermissionMode {
    ASK,
    AUTO_EDIT,
    YOLO;

    public static PermissionMode fromConfigurationString(final String value) {
        if (value == null || value.isBlank()) {
            return ASK;
        }
        return switch (value.strip().toLowerCase().replace('-', '_')) {
            case "auto_edit", "autoedit" -> AUTO_EDIT;
            case "yolo" -> YOLO;
            default -> ASK;
        };
    }
}
