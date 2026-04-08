package com.finvanta.config;

import com.finvanta.util.BusinessException;
import com.finvanta.util.ValidationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ModelAndView handleBusinessException(BusinessException ex, RedirectAttributes redirectAttributes) {
        log.warn("Business error [{}]: {}", ex.getErrorCode(), ex.getMessage());
        ModelAndView mav = new ModelAndView("error/business-error");
        mav.addObject("errorCode", ex.getErrorCode());
        mav.addObject("errorMessage", ex.getMessage());
        return mav;
    }

    @ExceptionHandler(ValidationException.class)
    public ModelAndView handleValidationException(ValidationException ex) {
        log.warn("Validation error: {}", ex.getMessage());
        ModelAndView mav = new ModelAndView("error/validation-error");
        mav.addObject("errors", ex.getErrors());
        return mav;
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ModelAndView handleOptimisticLockException(OptimisticLockingFailureException ex) {
        log.error("Optimistic locking failure: {}", ex.getMessage());
        ModelAndView mav = new ModelAndView("error/business-error");
        mav.addObject("errorCode", "CONCURRENT_MODIFICATION");
        mav.addObject("errorMessage", "This record was modified by another user. Please refresh and try again.");
        return mav;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ModelAndView handleDataIntegrityException(DataIntegrityViolationException ex) {
        log.error("Data integrity violation: {}", ex.getMessage());
        ModelAndView mav = new ModelAndView("error/business-error");
        mav.addObject("errorCode", "DATA_INTEGRITY");
        mav.addObject("errorMessage", "A data constraint was violated. Please check your input.");
        return mav;
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NOT_FOUND)
    @org.springframework.web.bind.annotation.ResponseBody
    public String handleNoResourceFound(org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        // Suppress favicon.ico and other missing static resource noise
        log.debug("Static resource not found: {}", ex.getMessage());
        return "";
    }

    @ExceptionHandler(Exception.class)
    public ModelAndView handleGenericException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        ModelAndView mav = new ModelAndView("error/generic-error");
        mav.addObject("errorMessage", "An unexpected error occurred. Please contact support.");
        return mav;
    }
}
