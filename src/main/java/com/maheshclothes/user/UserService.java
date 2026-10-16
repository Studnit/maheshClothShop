package com.maheshclothes.user;

import com.maheshclothes.common.AuditService;
import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.NotFoundException;
import com.maheshclothes.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9.]{3,60}$");
    public static final String OWNER_ROLE = "OWNER";

    private final UserRepository users;
    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public boolean hasAnyUser() {
        return users.count() > 0;
    }

    public List<AppUser> listUsers() {
        return users.findAllByOrderByUsernameAsc();
    }

    public List<Role> listRoles() {
        return roles.findAllByOrderByNameAsc();
    }

    public List<Permission> listPermissions() {
        return permissions.findAllByOrderByGroupNameAscCodeAsc();
    }

    public AppUser get(UUID id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("User"));
    }

    public void validatePassword(String pw) {
        if (pw == null || pw.length() < 8 || !pw.matches(".*[A-Za-z].*") || !pw.matches(".*[0-9].*")) {
            throw new BusinessException("user.error.password.weak");
        }
    }

    private String normaliseUsername(String raw) {
        String u = raw == null ? "" : raw.trim().toLowerCase();
        if (!USERNAME.matcher(u).matches()) {
            throw new BusinessException("user.error.username.invalid");
        }
        return u;
    }

    /** First-run only: creates the initial owner. The advisory lock stops two simultaneous setups. */
    @Transactional
    public AppUser createFirstOwner(String username, String fullName, String password) {
        jdbc.query("select pg_advisory_xact_lock(7001)", rs -> {
        });
        if (users.count() > 0) {
            throw new BusinessException("setup.error.done");
        }
        Role owner = roles.findByNameIgnoreCase(OWNER_ROLE).orElseThrow(() -> new NotFoundException("Role"));
        AppUser u = build(username, fullName, null, null, password, owner);
        audit.logAs(u.getUsername(), "SETUP_OWNER_CREATED", "user", u.getId(), null);
        return u;
    }

    @Transactional
    public AppUser create(String username, String fullName, String email, String phone, UUID roleId, String password) {
        Role role = roles.findById(roleId).orElseThrow(() -> new NotFoundException("Role"));
        AppUser u = build(username, fullName, email, phone, password, role);
        audit.log("USER_CREATED", "user", u.getId(), u.getUsername() + " as " + role.getName());
        return u;
    }

    private AppUser build(String username, String fullName, String email, String phone, String password, Role role) {
        String uname = normaliseUsername(username);
        if (users.existsByUsernameIgnoreCase(uname)) {
            throw new BusinessException("user.error.username.taken");
        }
        validatePassword(password);
        AppUser u = new AppUser();
        u.setUsername(uname);
        u.setFullName(fullName.trim());
        u.setEmail(blankToNull(email));
        u.setPhone(blankToNull(phone));
        u.setPasswordHash(encoder.encode(password));
        u.setRole(role);
        u.setEnabled(true);
        return users.save(u);
    }

    @Transactional
    public AppUser update(UUID id, String fullName, String email, String phone, UUID roleId, boolean enabled) {
        AppUser u = get(id);
        Role newRole = roles.findById(roleId).orElseThrow(() -> new NotFoundException("Role"));
        boolean demotingOwner = OWNER_ROLE.equals(u.getRole().getName()) && !OWNER_ROLE.equals(newRole.getName());
        boolean disablingOwner = OWNER_ROLE.equals(u.getRole().getName()) && u.isEnabled() && !enabled;
        if ((demotingOwner || disablingOwner) && otherActiveOwners(u) == 0) {
            throw new BusinessException("user.error.lastowner");
        }
        if (id.equals(CurrentUser.id()) && !enabled) {
            throw new BusinessException("user.error.disable.self");
        }
        u.setFullName(fullName.trim());
        u.setEmail(blankToNull(email));
        u.setPhone(blankToNull(phone));
        u.setRole(newRole);
        u.setEnabled(enabled);
        audit.log("USER_UPDATED", "user", id, u.getUsername() + " role=" + newRole.getName() + " enabled=" + enabled);
        return users.save(u);
    }

    private long otherActiveOwners(AppUser except) {
        return users.findAll().stream()
                .filter(x -> x.isEnabled() && OWNER_ROLE.equals(x.getRole().getName()) && !x.getId().equals(except.getId()))
                .count();
    }

    @Transactional
    public void resetPassword(UUID id, String newPassword) {
        validatePassword(newPassword);
        AppUser u = get(id);
        u.setPasswordHash(encoder.encode(newPassword));
        u.setFailedAttempts(0);
        u.setLockedUntil(null);
        users.save(u);
        audit.log("PASSWORD_RESET", "user", id, u.getUsername());
    }

    @Transactional
    public void changeOwnPassword(UUID id, String current, String newPassword) {
        AppUser u = get(id);
        if (!encoder.matches(current == null ? "" : current, u.getPasswordHash())) {
            throw new BusinessException("user.error.password.current");
        }
        validatePassword(newPassword);
        u.setPasswordHash(encoder.encode(newPassword));
        users.save(u);
        audit.log("PASSWORD_CHANGED", "user", id, u.getUsername());
    }

    @Transactional
    public void unlock(UUID id) {
        AppUser u = get(id);
        u.setFailedAttempts(0);
        u.setLockedUntil(null);
        users.save(u);
        audit.log("USER_UNLOCKED", "user", id, u.getUsername());
    }

    /** The OWNER role always keeps every permission; other roles can be tuned by the owner. */
    @Transactional
    public void updateRolePermissions(UUID roleId, Set<String> codes) {
        Role role = roles.findById(roleId).orElseThrow(() -> new NotFoundException("Role"));
        if (OWNER_ROLE.equals(role.getName())) {
            throw new BusinessException("user.error.owner.role.locked");
        }
        role.getPermissions().clear();
        permissions.findAllById(codes).forEach(role.getPermissions()::add);
        roles.save(role);
        audit.log("ROLE_PERMISSIONS_UPDATED", "role", roleId, role.getName() + ": " + String.join(",", codes));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
