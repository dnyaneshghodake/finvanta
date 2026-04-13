package com.finvanta.controller;

import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.BusinessCalendarRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.util.SecurityUtil;
import com.finvanta.util.TenantContext;

import java.time.LocalDate;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * CBS Business Calendar & Day Control Controller.
 * Per Finacle DAYCTRL / Tier-1 Branch-Level Day Control:
 * - Day Open/Close lifecycle management PER BRANCH
 * - Calendar browse filtered by user's branch (ADMIN sees operational branch)
 * ADMIN-only access (enforced in SecurityConfig).
 */
@Controller
@RequestMapping("/calendar")
public class CalendarController {

    private final BusinessCalendarRepository calendarRepository;
    private final BranchRepository branchRepository;
    private final BusinessDateService businessDateService;

    public CalendarController(
            BusinessCalendarRepository calendarRepository,
            BranchRepository branchRepository,
            BusinessDateService businessDateService) {
        this.calendarRepository = calendarRepository;
        this.branchRepository = branchRepository;
        this.businessDateService = businessDateService;
    }

    @GetMapping("/list")
    public ModelAndView listCalendar() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("calendar/list");

        // CBS Tier-1: ADMIN sees the first operational branch's calendar by default.
        // Per Finacle DAYCTRL: calendar/day control operates on OPERATIONAL branches
        // (type=BRANCH), not HEAD_OFFICE. The admin is assigned to HQ001 (HEAD_OFFICE)
        // but calendar entries are generated for BR001 (BRANCH type). Without this,
        // the admin sees an empty calendar because HQ001 has no calendar entries.
        // ADMIN can switch branches via the branch selector dropdown.
        Long branchId = SecurityUtil.getCurrentUserBranchId();
        if (SecurityUtil.isAdminRole()) {
            // ADMIN: show first operational branch's calendar (where entries actually exist)
            var operationalBranches = branchRepository.findAllOperationalBranches(tenantId);
            if (!operationalBranches.isEmpty()) {
                Long opBranchId = operationalBranches.get(0).getId();
                mav.addObject("calendarDates",
                        calendarRepository.findByTenantIdAndBranchIdOrderByBusinessDateDesc(tenantId, opBranchId));
                mav.addObject("openDay", businessDateService.getOpenDayOrNull(opBranchId));
                mav.addObject("currentBranchId", opBranchId);
                mav.addObject("currentBranchCode", operationalBranches.get(0).getBranchCode());
            } else {
                mav.addObject("calendarDates", java.util.Collections.emptyList());
                mav.addObject("currentBranchId", branchId);
                mav.addObject("currentBranchCode", SecurityUtil.getCurrentUserBranchCode());
            }
        } else if (branchId != null) {
            mav.addObject("calendarDates",
                    calendarRepository.findByTenantIdAndBranchIdOrderByBusinessDateDesc(tenantId, branchId));
            mav.addObject("openDay", businessDateService.getOpenDayOrNull(branchId));
            mav.addObject("currentBranchId", branchId);
            mav.addObject("currentBranchCode", SecurityUtil.getCurrentUserBranchCode());
        } else {
            mav.addObject("calendarDates", java.util.Collections.emptyList());
            mav.addObject("currentBranchId", null);
            mav.addObject("currentBranchCode", "--");
        }
        return mav;
    }

    /**
     * CBS Day Open — opens a business date for transactions at a specific branch.
     * Per Finacle DAYCTRL: day control is branch-scoped. The branchId parameter
     * identifies which branch's day to open. Uses the non-deprecated branch-explicit
     * API instead of the deprecated user-branch-implicit API.
     */
    @PostMapping("/day-open")
    public String openDay(
            @RequestParam String businessDate,
            @RequestParam Long branchId,
            RedirectAttributes redirectAttributes) {
        try {
            LocalDate date = LocalDate.parse(businessDate);
            businessDateService.openDay(date, branchId);
            redirectAttributes.addFlashAttribute("success", "Business day opened: " + businessDate);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/calendar/list";
    }

    /**
     * CBS Day Close — closes a business date after EOD completion at a specific branch.
     * Per Finacle DAYCTRL: day close is branch-scoped.
     */
    @PostMapping("/day-close")
    public String closeDay(
            @RequestParam String businessDate,
            @RequestParam Long branchId,
            RedirectAttributes redirectAttributes) {
        try {
            LocalDate date = LocalDate.parse(businessDate);
            businessDateService.closeDay(date, branchId);
            redirectAttributes.addFlashAttribute("success", "Business day closed: " + businessDate);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/calendar/list";
    }

    /**
     * CBS Calendar Generation — generates calendar for a month with auto-weekend detection.
     * Per Finacle DAYCTRL / RBI NI Act: every date in the month gets an entry.
     * Weekends are auto-marked based on tenant's businessDayPolicy:
     *   MON_TO_SAT → only Sunday is weekend (default for Indian banks)
     *   MON_TO_FRI → Saturday and Sunday are weekends
     * Idempotent — safe to call multiple times (skips existing entries).
     */
    @PostMapping("/generate")
    public String generateCalendar(
            @RequestParam int year, @RequestParam int month, RedirectAttributes redirectAttributes) {
        try {
            int created = businessDateService.generateCalendarForMonth(year, month);
            String monthLabel = year + "-" + String.format("%02d", month);
            if (created > 0) {
                redirectAttributes.addFlashAttribute("success",
                        "Calendar generated for " + monthLabel + ": " + created + " new entries created.");
            } else {
                // Per Finacle DAYCTRL: idempotent generation returns 0 when all entries exist.
                // Show a clear info message instead of confusing "0 new days created".
                redirectAttributes.addFlashAttribute("info",
                        "Calendar already exists for " + monthLabel
                                + ". All entries are already present — no new entries needed.");
            }
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/calendar/list";
    }

    /** Add a gazetted holiday per RBI NI Act */
    @PostMapping("/add-holiday")
    public String addHoliday(
            @RequestParam String date, @RequestParam String description, RedirectAttributes redirectAttributes) {
        try {
            businessDateService.addHoliday(LocalDate.parse(date), description);
            redirectAttributes.addFlashAttribute("success", "Holiday added: " + date + " — " + description);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/calendar/list";
    }

    /** Remove holiday flag (make it a working day) */
    @PostMapping("/remove-holiday")
    public String removeHoliday(@RequestParam String date, RedirectAttributes redirectAttributes) {
        try {
            businessDateService.removeHoliday(LocalDate.parse(date));
            redirectAttributes.addFlashAttribute("success", "Holiday removed: " + date);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/calendar/list";
    }
}
