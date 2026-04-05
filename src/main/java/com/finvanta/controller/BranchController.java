package com.finvanta.controller;

import com.finvanta.domain.entity.Branch;
import com.finvanta.repository.BranchRepository;
import com.finvanta.util.TenantContext;
import com.finvanta.util.SecurityUtil;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/branch")
public class BranchController {

    private final BranchRepository branchRepository;

    public BranchController(BranchRepository branchRepository) {
        this.branchRepository = branchRepository;
    }

    @GetMapping("/list")
    public ModelAndView listBranches() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("branch/list");
        mav.addObject("branches", branchRepository.findByTenantIdAndActiveTrue(tenantId));
        return mav;
    }

    @GetMapping("/add")
    public ModelAndView showAddForm() {
        ModelAndView mav = new ModelAndView("branch/add");
        mav.addObject("branch", new Branch());
        return mav;
    }

    @PostMapping("/add")
    public String addBranch(@ModelAttribute Branch branch, RedirectAttributes redirectAttributes) {
        String tenantId = TenantContext.getCurrentTenant();
        try {
            branch.setTenantId(tenantId);
            branch.setCreatedBy(SecurityUtil.getCurrentUsername());
            branchRepository.save(branch);
            redirectAttributes.addFlashAttribute("success", "Branch added: " + branch.getBranchCode());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/branch/list";
    }
}
