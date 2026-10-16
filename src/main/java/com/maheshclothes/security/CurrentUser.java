package com.maheshclothes.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/** Access to the signed-in user from anywhere (services, auditing). */
public final class CurrentUser {

    private CurrentUser() {
    }

    private static Authentication auth() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated() || a instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return a;
    }

    public static String username() {
        Authentication a = auth();
        return a == null ? "system" : a.getName();
    }

    public static UUID id() {
        Authentication a = auth();
        if (a != null && a.getPrincipal() instanceof AppUserDetails d) {
            return d.getUserId();
        }
        return null;
    }

    public static boolean has(String authority) {
        Authentication a = auth();
        return a != null && a.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals(authority));
    }

    public static boolean isAuthenticated() {
        return auth() != null;
    }
}
