package com.maheshclothes.common;

import com.maheshclothes.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Append-only audit trail (the database refuses UPDATE and DELETE on audit_logs). */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final JdbcTemplate jdbc;

    public void log(String action, String entityType, Object entityId, String details) {
        logAs(CurrentUser.username(), action, entityType, entityId, details);
    }

    public void logAs(String username, String action, String entityType, Object entityId, String details) {
        jdbc.update("""
                insert into audit_logs (username, action, entity_type, entity_id, details, ip_address)
                values (?, ?, ?, ?, ?, ?)
                """, username, action, entityType, entityId == null ? null : entityId.toString(),
                truncate(details, 2000), clientIp());
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest req = attrs.getRequest();
            String fwd = req.getHeader("X-Forwarded-For");
            String ip = fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : req.getRemoteAddr();
            return truncate(ip, 45);
        }
        return null;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
