package com.finvanta.controller;

import com.finvanta.workflow.ApprovalWorkflowService;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/workflow")
public class WorkflowController {

    private final ApprovalWorkflowService workflowService;

    public WorkflowController(ApprovalWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @GetMapping("/pending")
    public ModelAndView pendingApprovals() {
        ModelAndView mav = new ModelAndView("workflow/pending");
        mav.addObject("pendingItems", workflowService.getPendingApprovals());
        return mav;
    }

    @PostMapping("/approve/{id}")
    public String approve(@PathVariable Long id, @RequestParam String remarks, RedirectAttributes redirectAttributes) {
        try {
            workflowService.approve(id, remarks);
            redirectAttributes.addFlashAttribute("success", "Approved successfully");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/workflow/pending";
    }

    @PostMapping("/reject/{id}")
    public String reject(@PathVariable Long id, @RequestParam String remarks, RedirectAttributes redirectAttributes) {
        try {
            workflowService.reject(id, remarks);
            redirectAttributes.addFlashAttribute("success", "Rejected successfully");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/workflow/pending";
    }
}
