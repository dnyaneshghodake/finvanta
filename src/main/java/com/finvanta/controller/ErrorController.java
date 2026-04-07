package com.finvanta.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

/**
 * CBS Error Controller — Custom error pages per Tier-1 banking standards.
 *
 * Per RBI IT Governance Direction 2023 and OWASP:
 * - Never expose stack traces, server versions, or internal paths to users
 * - Error pages must be branded and provide navigation back to safe pages
 * - 403 (Forbidden) must clearly state the user lacks authorization
 * - 404 (Not Found) must not reveal URL structure
 * - 500 (Internal Error) must log details server-side, show generic message to user
 */
@Controller
@RequestMapping("/error")
public class ErrorController implements org.springframework.boot.web.servlet.error.ErrorController {

    @GetMapping("/403")
    public ModelAndView accessDenied() {
        ModelAndView mav = new ModelAndView("error/error");
        mav.addObject("errorCode", "403");
        mav.addObject("errorTitle", "Access Denied");
        mav.addObject("errorMessage",
            "You do not have permission to access this resource. "
                + "This action requires a higher authorization level.");
        return mav;
    }

    @RequestMapping("")
    public ModelAndView handleError(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int statusCode = status != null ? Integer.parseInt(status.toString()) : 500;

        ModelAndView mav = new ModelAndView("error/error");
        mav.addObject("errorCode", String.valueOf(statusCode));

        if (statusCode == 403) {
            mav.addObject("errorTitle", "Access Denied");
            mav.addObject("errorMessage",
                "You do not have permission to access this resource. "
                    + "This action requires a higher authorization level.");
        } else if (statusCode == 404) {
            mav.addObject("errorTitle", "Page Not Found");
            mav.addObject("errorMessage",
                "The page you are looking for does not exist or has been moved.");
        } else {
            mav.addObject("errorTitle", "System Error");
            mav.addObject("errorMessage",
                "An unexpected error occurred. Please try again or contact your system administrator.");
        }

        return mav;
    }
}
