package com.finvanta.controller;

import com.finvanta.accounting.AccountingService;
import com.finvanta.repository.JournalEntryRepository;
import com.finvanta.util.TenantContext;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

import java.time.LocalDate;

@Controller
@RequestMapping("/accounting")
public class AccountingController {

    private final AccountingService accountingService;
    private final JournalEntryRepository journalEntryRepository;

    public AccountingController(AccountingService accountingService,
                                 JournalEntryRepository journalEntryRepository) {
        this.accountingService = accountingService;
        this.journalEntryRepository = journalEntryRepository;
    }

    @GetMapping("/trial-balance")
    public ModelAndView trialBalance() {
        ModelAndView mav = new ModelAndView("accounting/trial-balance");
        mav.addObject("trialBalance", accountingService.getTrialBalance());
        return mav;
    }

    @GetMapping("/journal-entries")
    public ModelAndView journalEntries(@RequestParam(required = false) String fromDate,
                                        @RequestParam(required = false) String toDate) {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("accounting/journal-entries");

        LocalDate from = fromDate != null ? LocalDate.parse(fromDate) : LocalDate.now().minusMonths(1);
        LocalDate to = toDate != null ? LocalDate.parse(toDate) : LocalDate.now();

        mav.addObject("entries",
            journalEntryRepository.findByTenantIdAndValueDateBetween(tenantId, from, to));
        mav.addObject("fromDate", from);
        mav.addObject("toDate", to);

        return mav;
    }
}
