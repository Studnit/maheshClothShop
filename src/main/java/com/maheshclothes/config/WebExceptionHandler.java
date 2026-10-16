package com.maheshclothes.config;

import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.WebController;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URI;

/**
 * Any business rule failure thrown from a page action is shown as a message on the page the user came from.
 * (Pages that need to keep typed values catch BusinessException themselves and re-render.)
 */
@Slf4j
@ControllerAdvice(annotations = WebController.class)
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class WebExceptionHandler {

    private final MessageSource messages;

    public WebExceptionHandler(MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler(BusinessException.class)
    public String business(BusinessException e, HttpServletRequest req, RedirectAttributes redirect) {
        redirect.addFlashAttribute("error", messages.getMessage(e.getKey(), e.getArgs(), e.getKey(),
                LocaleContextHolder.getLocale()));
        return back(req);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public String conflict(HttpServletRequest req, RedirectAttributes redirect) {
        redirect.addFlashAttribute("error", messages.getMessage("error.conflict", null, "Conflict",
                LocaleContextHolder.getLocale()));
        return back(req);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public String integrity(DataIntegrityViolationException e, HttpServletRequest req, RedirectAttributes redirect) {
        log.warn("Data integrity violation: {}", e.getMostSpecificCause().getMessage());
        redirect.addFlashAttribute("error", messages.getMessage("error.integrity", null, "Duplicate or invalid data",
                LocaleContextHolder.getLocale()));
        return back(req);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String tooBig(HttpServletRequest req, RedirectAttributes redirect) {
        redirect.addFlashAttribute("error", messages.getMessage("error.upload.size", null, "File too large",
                LocaleContextHolder.getLocale()));
        return back(req);
    }

    /** Redirect to the referring page's path only, so a forged Referer can never send users to another site. */
    static String back(HttpServletRequest req) {
        String ref = req.getHeader("Referer");
        if (ref != null && !ref.isBlank()) {
            try {
                URI u = URI.create(ref);
                String path = u.getRawPath();
                if (path != null && path.startsWith("/") && !path.startsWith("//")) {
                    return "redirect:" + path + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
                }
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return "redirect:/";
    }
}
