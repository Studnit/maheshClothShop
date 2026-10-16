package com.maheshclothes.common;

import com.maheshclothes.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/** Key/value shop settings stored in app_settings, cached briefly in memory. */
@Service
@RequiredArgsConstructor
public class SettingsService {

    private static final long TTL_MILLIS = 30_000;

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    private volatile Map<String, String> cache = Collections.emptyMap();
    private volatile long loadedAt = 0;

    private Map<String, String> map() {
        if (System.currentTimeMillis() - loadedAt > TTL_MILLIS) {
            reload();
        }
        return cache;
    }

    private synchronized void reload() {
        Map<String, String> m = new HashMap<>();
        jdbc.query("select setting_key, setting_value from app_settings",
                rs -> {
                    m.put(rs.getString(1), rs.getString(2));
                });
        cache = Collections.unmodifiableMap(m);
        loadedAt = System.currentTimeMillis();
    }

    public String get(String key) {
        return map().get(key);
    }

    public String get(String key, String defaultValue) {
        String v = map().get(key);
        return v == null || v.isBlank() ? defaultValue : v;
    }

    public boolean bool(String key) {
        return "true".equalsIgnoreCase(get(key, "false"));
    }

    public int integer(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public BigDecimal decimal(String key, BigDecimal defaultValue) {
        try {
            return new BigDecimal(get(key, defaultValue.toPlainString()).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public Map<String, String> all() {
        return map();
    }

    public String defaultLanguage() {
        String l = get("language.default", "en");
        if ("mr".equalsIgnoreCase(l)) {
            return "mr";
        }
        return "both".equalsIgnoreCase(l) ? "both" : "en";
    }

    @Transactional
    public void update(Map<String, String> values) {
        TreeSet<String> changed = new TreeSet<>();
        Map<String, String> current = map();
        for (Map.Entry<String, String> e : values.entrySet()) {
            String v = e.getValue() == null ? "" : e.getValue().trim();
            if (!v.equals(current.getOrDefault(e.getKey(), ""))) {
                changed.add(e.getKey());
            }
            jdbc.update("""
                    insert into app_settings (setting_key, setting_value, updated_at, updated_by)
                    values (?, ?, now(), ?)
                    on conflict (setting_key) do update
                      set setting_value = excluded.setting_value, updated_at = now(), updated_by = excluded.updated_by
                    """, e.getKey(), v, CurrentUser.username());
        }
        loadedAt = 0;
        if (!changed.isEmpty()) {
            audit.log("SETTINGS_UPDATED", "settings", null, "Changed: " + String.join(", ", changed));
        }
    }

    public ShopInfo shop() {
        return new ShopInfo(
                get("shop.name", "Shop"), get("shop.address", ""), get("shop.village", ""),
                get("shop.district", ""), get("shop.state", ""), get("shop.pin", ""),
                get("shop.phone", ""), get("shop.email", ""), get("shop.gstin", ""),
                get("shop.logo_file_id", ""), get("invoice.footer", ""), get("invoice.return_policy", ""),
                bool("tax.enabled"), bool("tax.prices_include_tax"));
    }

    /** Shop details shown on invoices and in the layout. */
    public record ShopInfo(String name, String address, String village, String district, String state, String pin,
                           String phone, String email, String gstin, String logoFileId, String footer,
                           String returnPolicy, boolean taxEnabled, boolean pricesIncludeTax) {

        public String addressLine() {
            StringBuilder sb = new StringBuilder();
            append(sb, address);
            append(sb, village);
            append(sb, district);
            String statePin = (state == null ? "" : state) + (pin == null || pin.isBlank() ? "" : " - " + pin);
            append(sb, statePin);
            return sb.toString();
        }

        private static void append(StringBuilder sb, String part) {
            if (part != null && !part.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(part.trim());
            }
        }

        public boolean hasLogo() {
            return logoFileId != null && !logoFileId.isBlank();
        }
    }
}
