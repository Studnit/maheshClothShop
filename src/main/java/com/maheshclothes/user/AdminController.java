package com.maheshclothes.user;

import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.PageResult;
import com.maheshclothes.common.WebController;
import com.maheshclothes.security.AppUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Users, role permissions, audit log and the signed-in user's own account page. */
@WebController
@RequiredArgsConstructor
public class AdminController {

    private final UserService users;
    private final JdbcTemplate jdbc;

    // ---------- users ----------

    @GetMapping("/admin/users")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public String list(Model model) {
        model.addAttribute("users", users.listUsers());
        return "admin/users";
    }

    @Getter
    @Setter
    public static class UserForm {
        private UUID id;
        @NotBlank
        private String username;
        @NotBlank
        private String fullName;
        private String email;
        private String phone;
        @NotNull
        private UUID roleId;
        private boolean enabled = true;
        private String password;
    }

    @GetMapping("/admin/users/new")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public String newForm(Model model) {
        model.addAttribute("form", new UserForm());
        model.addAttribute("roles", users.listRoles());
        model.addAttribute("editing", false);
        return "admin/user-form";
    }

    @GetMapping("/admin/users/{id}/edit")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public String edit(@PathVariable UUID id, Model model) {
        AppUser u = users.get(id);
        UserForm f = new UserForm();
        f.setId(u.getId());
        f.setUsername(u.getUsername());
        f.setFullName(u.getFullName());
        f.setEmail(u.getEmail());
        f.setPhone(u.getPhone());
        f.setRoleId(u.getRole().getId());
        f.setEnabled(u.isEnabled());
        model.addAttribute("form", f);
        model.addAttribute("roles", users.listRoles());
        model.addAttribute("editing", true);
        model.addAttribute("locked", u.getLockedUntil() != null && u.getLockedUntil().isAfter(Instant.now()));
        return "admin/user-form";
    }

    @PostMapping("/admin/users")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public String save(@Valid @ModelAttribute("form") UserForm form, BindingResult result, Model model,
                       RedirectAttributes redirect) {
        boolean editing = form.getId() != null;
        model.addAttribute("roles", users.listRoles());
        model.addAttribute("editing", editing);
        if (result.hasErrors()) {
            return "admin/user-form";
        }
        try {
            if (editing) {
                users.update(form.getId(), form.getFullName(), form.getEmail(), form.getPhone(), form.getRoleId(),
                        form.isEnabled());
                if (form.getPassword() != null && !form.getPassword().isBlank()) {
                    users.resetPassword(form.getId(), form.getPassword());
                }
            } else {
                users.create(form.getUsername(), form.getFullName(), form.getEmail(), form.getPhone(), form.getRoleId(),
                        form.getPassword());
            }
        } catch (BusinessException e) {
            model.addAttribute("errorKey", e.getKey());
            model.addAttribute("errorArgs", e.getArgs());
            return "admin/user-form";
        }
        redirect.addFlashAttribute("successKey", "common.saved");
        return "redirect:/admin/users";
    }

    @PostMapping("/admin/users/{id}/unlock")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public String unlock(@PathVariable UUID id, RedirectAttributes redirect) {
        users.unlock(id);
        redirect.addFlashAttribute("successKey", "common.saved");
        return "redirect:/admin/users/" + id + "/edit";
    }

    // ---------- roles ----------

    @GetMapping("/admin/roles")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public String roles(Model model) {
        Map<String, List<Permission>> grouped = new LinkedHashMap<>();
        for (Permission p : users.listPermissions()) {
            grouped.computeIfAbsent(p.getGroupName(), k -> new ArrayList<>()).add(p);
        }
        model.addAttribute("roles", users.listRoles());
        model.addAttribute("groups", grouped);
        return "admin/roles";
    }

    @PostMapping("/admin/roles/{id}")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public String saveRole(@PathVariable UUID id, @RequestParam(name = "perm", required = false) List<String> perms,
                           RedirectAttributes redirect) {
        Set<String> codes = perms == null ? new HashSet<>() : new HashSet<>(perms);
        users.updateRolePermissions(id, codes);
        redirect.addFlashAttribute("successKey", "admin.roles.saved");
        return "redirect:/admin/roles";
    }

    // ---------- audit log ----------

    public record AuditRow(long id, Instant occurredAt, String username, String action, String entityType,
                           String entityId, String details, String ip) {
    }

    @GetMapping("/admin/audit")
    @PreAuthorize("hasAuthority('AUDIT_VIEW')")
    public String audit(@RequestParam(required = false) String user, @RequestParam(required = false) String action,
                        @RequestParam(defaultValue = "0") int page, Model model) {
        int size = 50;
        StringBuilder where = new StringBuilder(" where 1=1");
        List<Object> args = new ArrayList<>();
        if (user != null && !user.isBlank()) {
            where.append(" and lower(username) like ?");
            args.add("%" + user.trim().toLowerCase() + "%");
        }
        if (action != null && !action.isBlank()) {
            where.append(" and action = ?");
            args.add(action.trim());
        }
        Long total = jdbc.queryForObject("select count(*) from audit_logs" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(Math.max(0, page) * size);
        List<AuditRow> rows = jdbc.query("""
                select id, occurred_at, username, action, entity_type, entity_id, details, ip_address
                  from audit_logs""" + where + " order by id desc limit ? offset ?",
                (rs, i) -> new AuditRow(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)),
                pageArgs.toArray());
        List<String> actions = jdbc.queryForList("select distinct action from audit_logs order by action", String.class);
        model.addAttribute("page", new PageResult<>(rows, Math.max(0, page), size, total == null ? 0 : total));
        model.addAttribute("actions", actions);
        model.addAttribute("fUser", user);
        model.addAttribute("fAction", action);
        return "admin/audit";
    }

    // ---------- my account ----------

    @Getter
    @Setter
    public static class PasswordForm {
        private String current;
        private String password;
        private String confirm;
    }

    @GetMapping("/account")
    public String account(Model model) {
        model.addAttribute("form", new PasswordForm());
        return "account";
    }

    @PostMapping("/account/password")
    public String changePassword(@ModelAttribute("form") PasswordForm form, @AuthenticationPrincipal AppUserDetails me,
                                 Model model, RedirectAttributes redirect) {
        try {
            if (form.getPassword() == null || !form.getPassword().equals(form.getConfirm())) {
                throw new BusinessException("user.error.password.mismatch");
            }
            users.changeOwnPassword(me.getUserId(), form.getCurrent(), form.getPassword());
        } catch (BusinessException e) {
            model.addAttribute("errorKey", e.getKey());
            model.addAttribute("errorArgs", e.getArgs());
            return "account";
        }
        redirect.addFlashAttribute("successKey", "account.password.changed");
        return "redirect:/account";
    }
}
