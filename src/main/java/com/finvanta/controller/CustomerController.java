package com.finvanta.controller;

import com.finvanta.domain.entity.Customer;
import com.finvanta.repository.BranchRepository;
import com.finvanta.repository.CustomerRepository;
import com.finvanta.util.TenantContext;
import com.finvanta.util.SecurityUtil;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/customer")
public class CustomerController {

    private final CustomerRepository customerRepository;
    private final BranchRepository branchRepository;

    public CustomerController(CustomerRepository customerRepository,
                               BranchRepository branchRepository) {
        this.customerRepository = customerRepository;
        this.branchRepository = branchRepository;
    }

    @GetMapping("/list")
    public ModelAndView listCustomers() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("customer/list");
        mav.addObject("customers", customerRepository.findByTenantIdAndActiveTrue(tenantId));
        return mav;
    }

    @GetMapping("/add")
    public ModelAndView showAddForm() {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("customer/add");
        mav.addObject("customer", new Customer());
        mav.addObject("branches", branchRepository.findByTenantIdAndActiveTrue(tenantId));
        return mav;
    }

    @PostMapping("/add")
    public String addCustomer(@ModelAttribute Customer customer,
                               @RequestParam Long branchId,
                               RedirectAttributes redirectAttributes) {
        String tenantId = TenantContext.getCurrentTenant();
        try {
            customer.setTenantId(tenantId);
            customer.setBranch(branchRepository.findById(branchId)
                .orElseThrow(() -> new com.finvanta.util.BusinessException(
                    "BRANCH_NOT_FOUND", "Branch not found: " + branchId)));
            customer.setCreatedBy(SecurityUtil.getCurrentUsername());
            customerRepository.save(customer);
            redirectAttributes.addFlashAttribute("success", "Customer added: " + customer.getCustomerNumber());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/customer/list";
    }

    @GetMapping("/view/{id}")
    public ModelAndView viewCustomer(@PathVariable Long id) {
        String tenantId = TenantContext.getCurrentTenant();
        ModelAndView mav = new ModelAndView("customer/view");
        Customer customer = customerRepository.findById(id)
            .filter(c -> c.getTenantId().equals(tenantId))
            .orElseThrow(() -> new com.finvanta.util.BusinessException(
                "CUSTOMER_NOT_FOUND", "Customer not found: " + id));
        mav.addObject("customer", customer);
        return mav;
    }

    @PostMapping("/verify-kyc/{id}")
    public String verifyKyc(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        String tenantId = TenantContext.getCurrentTenant();
        try {
            Customer customer = customerRepository.findById(id)
                .filter(c -> c.getTenantId().equals(tenantId))
                .orElseThrow(() -> new com.finvanta.util.BusinessException(
                    "CUSTOMER_NOT_FOUND", "Customer not found: " + id));
            customer.setKycVerified(true);
            customer.setKycVerifiedDate(java.time.LocalDate.now());
            customer.setKycVerifiedBy(SecurityUtil.getCurrentUsername());
            customer.setUpdatedBy(SecurityUtil.getCurrentUsername());
            customerRepository.save(customer);
            redirectAttributes.addFlashAttribute("success",
                "KYC verified for customer: " + customer.getCustomerNumber());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/customer/view/" + id;
    }
}
