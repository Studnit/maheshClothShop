package com.maheshclothes.security;

import com.maheshclothes.common.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationFailureLockedEvent;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/** Account lockout after repeated bad passwords, and login auditing. */
@Component
@RequiredArgsConstructor
public class LoginEvents {

    static final int MAX_ATTEMPTS = 5;
    static final int LOCK_MINUTES = 15;

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    @EventListener
    public void onBadCredentials(AuthenticationFailureBadCredentialsEvent e) {
        String username = e.getAuthentication().getName();
        jdbc.update("""
                update users
                   set failed_attempts = failed_attempts + 1,
                       locked_until = case when failed_attempts + 1 >= ? then now() + make_interval(mins => ?)
                                           else locked_until end
                 where lower(username) = lower(?)
                """, MAX_ATTEMPTS, LOCK_MINUTES, username);
        audit.logAs(username, "LOGIN_FAILED", "user", null, "Bad credentials");
    }

    @EventListener
    public void onLocked(AuthenticationFailureLockedEvent e) {
        audit.logAs(e.getAuthentication().getName(), "LOGIN_BLOCKED", "user", null, "Account locked");
    }

    @EventListener
    public void onSuccess(InteractiveAuthenticationSuccessEvent e) {
        String username = e.getAuthentication().getName();
        jdbc.update("update users set failed_attempts = 0, locked_until = null, last_login_at = now() "
                + "where lower(username) = lower(?)", username);
        audit.logAs(username, "LOGIN", "user", null, null);
    }
}
