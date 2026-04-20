package com.finvanta.config;

import com.finvanta.util.BusinessException;
import com.finvanta.util.ValidationException;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

/**
 * CBS Global Exception Handler per Finacle/Temenos Tier-1 standards.
 *
 * Per RBI IT Governance Direction 2023 §8.3:
 * - No stack traces exposed to end users
 * - Structured error codes for support escalation
 * - Correlation ID (requestId) displayed on every error page
 * - All errors logged with MDC context for SIEM correlation
 *
 * All handlers route to the unified error/error.jsp view which renders
 * the error code, message, and correlation ID consistently.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Build error ModelAndView with correlation ID from MDC/request attribute. */
    private ModelAndView buildErrorView(String errorCode, String errorTitle, String errorMessage,
                                        HttpServletRequest request) {
        ModelAndView mav = new ModelAndView("error/error");
        mav.addObject("errorCode", errorCode);
        mav.addObject("errorTitle", errorTitle);
        mav.addObject("errorMessage", errorMessage);
        // CBS Tier-1: Correlation ID for support escalation per Phase 13 audit.
        // Reads from request attribute (set by TenantFilter) or MDC fallback.
        String requestId = request != null
                ? (String) request.getAttribute("fvRequestId")
                : null;
        if (requestId == null) {
            requestId = MDC.get("requestId");
        }
        mav.addObject("correlationId", requestId);
        return mav;
    }

    @ExceptionHandler(BusinessException.class)
    public ModelAndView handleBusinessException(BusinessException ex, HttpServletRequest request) {
        log.warn("Business error [{}]: {}", ex.getErrorCode(), ex.getMessage());
        return buildErrorView(ex.getErrorCode(), "Business Error", ex.getMessage(), request);
    }

    @ExceptionHandler(ValidationException.class)
    public ModelAndView handleValidationException(ValidationException ex, HttpServletRequest request) {
        log.warn("Validation error: {}", ex.getMessage());
        String message = ex.getErrors() != null && !ex.getErrors().isEmpty()
                ? String.join("; ", ex.getErrors())
                : ex.getMessage();
        return buildErrorView("VALIDATION_ERROR", "Validation Error", message, request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ModelAndView handleOptimisticLockException(OptimisticLockingFailureException ex,
                                                      HttpServletRequest request) {
        log.error("Optimistic locking failure: {}", ex.getMessage());
        return buildErrorView("CONCURRENT_MODIFICATION", "Concurrent Modification",
                "This record was modified by another user. Please refresh and try again.", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ModelAndView handleDataIntegrityException(DataIntegrityViolationException ex,
                                                     HttpServletRequest request) {
        log.error("Data integrity violation: {}", ex.getMessage());
        return buildErrorView("DATA_INTEGRITY", "Data Integrity Error",
                "A data constraint was violated. Please check your input.", request);
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
    public ModelAndView handleGenericException(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return buildErrorView("SYSTEM_ERROR", "System Error",
                "An unexpected error occurred. Please contact support with the reference ID below.",
                request);
    }
}
