package com.finvanta.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.ui.Model;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Populates model attributes required by the layout (topbar) on every page.
 * businessDate and userRole are rendered in sidebar.jsp's topbar section.
 */
@ControllerAdvice
public class CommonModelAdvice {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy");

    @ModelAttribute
    public void addCommonAttributes(Model model) {
        model.addAttribute("businessDate", LocalDate.now().format(DATE_FMT));
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            String role = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst()
                .map(r -> r.replace("ROLE_", ""))
                .orElse("USER");
            model.addAttribute("userRole", role);
        }
    }
}
