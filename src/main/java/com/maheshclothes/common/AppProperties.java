package com.maheshclothes.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deployment-level configuration (environment variables). Shop-level configuration lives in the
 * app_settings table and is edited from the Settings screen. Secrets are only ever read from the environment.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(String timezone, String setupToken, Whatsapp whatsapp) {

    public AppProperties {
        if (timezone == null || timezone.isBlank()) {
            timezone = "Asia/Kolkata";
        }
        if (whatsapp == null) {
            whatsapp = new Whatsapp(null, null, null, null);
        }
    }

    public record Whatsapp(String accessToken, String appSecret, String verifyToken, String apiVersion) {
        public Whatsapp {
            if (apiVersion == null || apiVersion.isBlank()) {
                apiVersion = "v23.0";
            }
        }

        public boolean hasAccessToken() {
            return accessToken != null && !accessToken.isBlank();
        }
    }
}
