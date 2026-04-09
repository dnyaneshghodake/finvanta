package com.finvanta.controller;

import com.finvanta.audit.AuditService;
import com.finvanta.repository.AuditLogRepository;
import com.finvanta.util.TenantContext;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
@RequestMapping("/audit")
public class AuditController {

    private final AuditLogRepository auditLogRepository;
    private final AuditService auditService;

    public AuditController(AuditLogRepository auditLogRepository, AuditService auditService) {
        this.auditLogRepository = auditLogRepository;
        this.auditService = auditService;
    }

    @GetMapping("/logs")
    public ModelAndView auditLogs() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("audit/logs");
        mav.addObject("auditLogs", auditLogRepository.findRecentAuditLogs(tenantId));
        mav.addObject("chainIntegrity", auditService.verifyChainIntegrity(tenantId));
        return mav;
    }
}
