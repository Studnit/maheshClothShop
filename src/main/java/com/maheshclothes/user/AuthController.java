package com.maheshclothes.user;

import com.maheshclothes.common.AppProperties;
import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.WebController;
import com.maheshclothes.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import jakarta.validation.constraints.NotBlank;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Login page and the one-time first-run setup of the owner account. */
@WebController
@RequiredArgsConstructor
public class AuthController {

    private final UserService users;
    private final AppProperties props;

    @GetMapping("/login")
    public String login() {
        if (!users.hasAnyUser()) {
            return "redirect:/setup";
        }
        if (CurrentUser.isAuthenticated()) {
            return "redirect:/";
        }
        return "login";
    }

    @Getter
    @Setter
    public static class SetupForm {
        @NotBlank
        private String fullName;
        @NotBlank
        private String username;
        @NotBlank
        private String password;
        @NotBlank
        private String confirmPassword;
        private String setupToken;
    }

    @GetMapping("/setup")
    public String setupForm(Model model) {
        if (users.hasAnyUser()) {
            return "redirect:/login";
        }
        model.addAttribute("form", new SetupForm());
        model.addAttribute("tokenRequired", tokenRequired());
        return "setup";
    }

    @PostMapping("/setup")
    public String setup(@Valid @ModelAttribute("form") SetupForm form, BindingResult result, Model model,
                        RedirectAttributes redirect) {
        if (users.hasAnyUser()) {
            return "redirect:/login";
        }
        model.addAttribute("tokenRequired", tokenRequired());
        if (result.hasErrors()) {
            return "setup";
        }
        try {
            if (tokenRequired() && !tokenMatches(form.getSetupToken())) {
                throw new BusinessException("setup.error.token");
            }
            if (!form.getPassword().equals(form.getConfirmPassword())) {
                throw new BusinessException("user.error.password.mismatch");
            }
            users.createFirstOwner(form.getUsername(), form.getFullName(), form.getPassword());
        } catch (BusinessException e) {
            model.addAttribute("errorKey", e.getKey());
            model.addAttribute("errorArgs", e.getArgs());
            return "setup";
        }
        redirect.addFlashAttribute("setupDone", true);
        return "redirect:/login";
    }

    private boolean tokenRequired() {
        return props.setupToken() != null && !props.setupToken().isBlank();
    }

    private boolean tokenMatches(String given) {
        byte[] a = props.setupToken().getBytes(StandardCharsets.UTF_8);
        byte[] b = (given == null ? "" : given.trim()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
