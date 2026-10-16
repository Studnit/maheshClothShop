package com.maheshclothes.security;

import com.maheshclothes.user.AppUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class AppUserDetails implements UserDetails {

    private final UUID userId;
    private final String username;
    private final String fullName;
    private final String roleName;
    private final String passwordHash;
    private final boolean enabled;
    private final Instant lockedUntil;
    private final Set<GrantedAuthority> authorities;

    public AppUserDetails(AppUser u) {
        this.userId = u.getId();
        this.username = u.getUsername();
        this.fullName = u.getFullName();
        this.roleName = u.getRole().getName();
        this.passwordHash = u.getPasswordHash();
        this.enabled = u.isEnabled();
        this.lockedUntil = u.getLockedUntil();
        Set<GrantedAuthority> a = new HashSet<>();
        a.add(new SimpleGrantedAuthority("ROLE_" + u.getRole().getName()));
        u.getRole().getPermissions().forEach(p -> a.add(new SimpleGrantedAuthority(p.getCode())));
        this.authorities = a;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getFullName() {
        return fullName;
    }

    public String getRoleName() {
        return roleName;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonLocked() {
        return lockedUntil == null || lockedUntil.isBefore(Instant.now());
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
