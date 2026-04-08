package com.finvanta.controller;

import com.finvanta.repository.BusinessCalendarRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.util.TenantContext;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;

/**
 * CBS Business Calendar & Day Control Controller.
 * Per Finacle/Temenos Day Control standards:
 * - Day Open/Close lifecycle management
 * - Calendar browse with day status
 * ADMIN-only access (enforced in SecurityConfig).
 */
@Controller
@RequestMapping("/calendar")
public class CalendarController {

    private final BusinessCalendarRepository calendarRepository;
    private final BusinessDateService businessDateService;

    public CalendarController(BusinessCalendarRepository calendarRepository,
                               BusinessDateService businessDateService) {
        this.calendarRepository = calendarRepository;
        this.businessDateService = businessDateService;
    }

    @GetMapping("/list")
    public ModelAndView listCalendar() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("calendar/list");
        mav.addObject("calendarDates",
            calendarRepository.findByTenantIdOrderByBusinessDateDesc(tenantId));
        mav.addObject("openDay", businessDateService.getOpenDayOrNull());
        return mav;
    }

    /** CBS Day Open — opens a business date for transactions */
    @PostMapping("/day-open")
    public String openDay(@RequestParam String businessDate, RedirectAttributes redirectAttributes) {
        try {
            LocalDate date = LocalDate.parse(businessDate);
            businessDateService.openDay(date);
            redirectAttributes.addFlashAttribute("success", "Business day opened: " + businessDate);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/calendar/list";
    }

    /** CBS Day Close — closes a business date after EOD completion */
    @PostMapping("/day-close")
    public String closeDay(@RequestParam String businessDate, RedirectAttributes redirectAttributes) {
        try {
            LocalDate date = LocalDate.parse(businessDate);
            businessDateService.closeDay(date);
            redirectAttributes.addFlashAttribute("success", "Business day closed: " + businessDate);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/calendar/list";
    }

    /**
     * CBS Calendar Generation — generates calendar for a month with auto-weekend detection.
     * Per Finacle DAYCTRL / RBI NI Act: every date in the month gets an entry.
     * Saturdays and Sundays are auto-marked as holidays.
     * Idempotent — safe to call multiple times (skips existing entries).
     */
    @PostMapping("/generate")
    public String generateCalendar(@RequestParam int year,
                                    @RequestParam int month,
                                    RedirectAttributes redirectAttributes) {
        try {
            int created = businessDateService.generateCalendarForMonth(year, month);
            redirectAttributes.addFlashAttribute("success",
                "Calendar generated for " + year + "-" + String.format("%02d", month)
                    + ": " + created + " new days created");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/calendar/list";
    }

    /** Add a gazetted holiday per RBI NI Act */
    @PostMapping("/add-holiday")
    public String addHoliday(@RequestParam String date,
                              @RequestParam String description,
                              RedirectAttributes redirectAttributes) {
        try {
            businessDateService.addHoliday(LocalDate.parse(date), description);
            redirectAttributes.addFlashAttribute("success",
                "Holiday added: " + date + " — " + description);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/calendar/list";
    }

    /** Remove holiday flag (make it a working day) */
    @PostMapping("/remove-holiday")
    public String removeHoliday(@RequestParam String date,
                                 RedirectAttributes redirectAttributes) {
        try {
            businessDateService.removeHoliday(LocalDate.parse(date));
            redirectAttributes.addFlashAttribute("success", "Holiday removed: " + date);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/calendar/list";
    }
}