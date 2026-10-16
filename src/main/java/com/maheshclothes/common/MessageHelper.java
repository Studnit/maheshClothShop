package com.maheshclothes.common;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/** Resolves message keys in the current request language: ${@msg.get(key, args)} in templates. */
@Component("msg")
public class MessageHelper {

    private final MessageSource messages;

    public MessageHelper(MessageSource messages) {
        this.messages = messages;
    }

    public String get(String key, Object... args) {
        if (key == null) {
            return "";
        }
        return messages.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
