package com.finvanta.controller;

import com.finvanta.repository.BusinessCalendarRepository;
import com.finvanta.util.TenantContext;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

/**
 * CBS Business Calendar Management Controller.
 * Per Finacle/Temenos EOD framework, the business calendar controls:
 * - Which dates are working days vs holidays
 * - EOD completion status per date
 * - Calendar locking during batch processing
 * ADMIN-only access (enforced in SecurityConfig).
 */
@Controller
@RequestMapping("/calendar")
public class CalendarController {

    private final BusinessCalendarRepository calendarRepository;

    public CalendarController(BusinessCalendarRepository calendarRepository) {
        this.calendarRepository = calendarRepository;
    }

    @GetMapping("/list")
    public ModelAndView listCalendar() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("calendar/list");
        mav.addObject("calendarDates",
            calendarRepository.findByTenantIdOrderByBusinessDateDesc(tenantId));
        return mav;
    }
}