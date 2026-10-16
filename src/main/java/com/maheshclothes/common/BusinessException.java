package com.maheshclothes.common;

/**
 * A business-rule violation that should be shown to the user in their language.
 * {@code key} is a message-bundle key (i18n/messages*.properties); {@code args} fill its {0}, {1}... placeholders.
 */
public class BusinessException extends RuntimeException {

    private final String key;
    private final Object[] args;

    public BusinessException(String key, Object... args) {
        super(key);
        this.key = key;
        this.args = args == null ? new Object[0] : args;
    }

    public String getKey() {
        return key;
    }

    public Object[] getArgs() {
        return args;
    }
}
