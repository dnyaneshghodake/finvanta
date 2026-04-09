package com.finvanta.config;

import com.finvanta.domain.entity.BusinessCalendar;
import com.finvanta.service.BusinessDateService;

import java.time.format.DateTimeFormatter;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Populates model attributes required by the layout (topbar) on every page.
 * businessDate is sourced from the CBS Business Calendar (not system date).
 * Per Finacle/Temenos, the topbar always shows the current CBS business date.
 */
@ControllerAdvice
public class CommonModelAdvice {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy");

    private final BusinessDateService businessDateService;

    public CommonModelAdvice(BusinessDateService businessDateService) {
        this.businessDateService = businessDateService;
    }

    @ModelAttribute
    public void addCommonAttributes(Model model) {
        // CBS business date from calendar — NOT LocalDate.now()
        try {
            BusinessCalendar openDay = businessDateService.getOpenDayOrNull();
            if (openDay != null) {
                model.addAttribute("businessDate", openDay.getBusinessDate().format(DATE_FMT));
            } else {
                model.addAttribute("businessDate", "NO DAY OPEN");
            }
        } catch (Exception e) {
            model.addAttribute("businessDate", "--");
        }

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
