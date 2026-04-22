package com.finvanta.api;

import com.finvanta.config.BranchAwareUserDetails;
import com.finvanta.domain.entity.BusinessCalendar;
import com.finvanta.repository.BusinessCalendarRepository;
import com.finvanta.service.BusinessDateService;
import com.finvanta.util.TenantContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/**
 * CBS Business Calendar REST API per Finacle DAYCTRL / Temenos COB Day Control.
 *
 * <p>Thin orchestration layer over {@link BusinessDateService} — no business
 * logic here. All SOD/EOD validation, previous-day checks, holiday region
 * scoping, and audit trail reside in the service layer.
 *
 * <p>Per Finacle DAYCTRL / RBI IT Governance Direction 2023 §7.4:
 * <ul>
 *   <li>Business date is PER BRANCH, not per tenant</li>
 *   <li>System date != business date (EOD may run after midnight)</li>
 *   <li>Only one day can be DAY_OPEN per branch at a time</li>
 *   <li>Days must be processed in strict sequence per branch</li>
 * </ul>
 *
 * <p>CBS Role Matrix for Calendar:
 * <ul>
 *   <li>MAKER/CHECKER/ADMIN → inquiry (today, branch calendar)</li>
 *   <li>ADMIN only → day open/close, calendar generation, holiday management</li>
 * </ul>
 *
 * <p>Used by the Next.js BFF for:
 * <ul>
 *   <li>Dashboard day-status banner (DAY_OPEN / EOD_RUNNING / NOT_OPENED)</li>
 *   <li>Transaction date population (businessDate, not system date)</li>
 *   <li>Admin day-control screens (open/close day, manage holidays)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/calendar")
public class CalendarApiController {

    private final BusinessDateService businessDateService;
    private final BusinessCalendarRepository calendarRepository;

    public CalendarApiController(
            BusinessDateService businessDateService,
            BusinessCalendarRepository calendarRepository) {
        this.businessDateService = businessDateService;
        this.calendarRepository = calendarRepository;
    }

    // === Inquiry ===

    /**
     * Get current business day status for the authenticated user's branch.
     * Per Finacle DAYCTRL: this is the primary endpoint the dashboard calls
     * after login to determine whether transactions are allowed.
     *
     * <p>Returns null businessDate if no day is open (NOT_OPENED state).
     * The UI must show "Business day not opened" banner and disable
     * transaction buttons in this case.
     */
    @GetMapping("/today")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<DayStatusResponse>>
            getToday() {
        Long branchId = resolveUserBranchId();
        if (branchId == null) {
            // HO/system user without branch — return minimal context
            return ResponseEntity.ok(ApiResponse.success(
                    new DayStatusResponse(
                            null, null, "NOT_OPENED", false,
                            null, null, null, null)));
        }
        BusinessCalendar openDay = businessDateService
                .getOpenDayOrNull(branchId);
        return ResponseEntity.ok(ApiResponse.success(
                DayStatusResponse.from(openDay, branchId)));
    }

    /**
     * Get current business day status for a specific branch.
     * Per Finacle DAYCTRL: used by ADMIN to monitor day status across branches.
     */
    @GetMapping("/branch/{branchId}/today")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<DayStatusResponse>>
            getBranchToday(@PathVariable Long branchId) {
        BusinessCalendar openDay = businessDateService
                .getOpenDayOrNull(branchId);
        return ResponseEntity.ok(ApiResponse.success(
                DayStatusResponse.from(openDay, branchId)));
    }

    /**
     * Get calendar entries for a branch (for calendar management UI).
     * Per Finacle DAYCTRL: shows all dates with their status and holiday flags.
     */
    @GetMapping("/branch/{branchId}")
    @PreAuthorize("hasAnyRole('MAKER', 'CHECKER', 'ADMIN')")
    public ResponseEntity<ApiResponse<List<CalendarEntryResponse>>>
            getBranchCalendar(@PathVariable Long branchId) {
        String tenantId = TenantContext.getCurrentTenant();
        var entries = calendarRepository
                .findByTenantIdAndBranchIdOrderByBusinessDateDesc(
                        tenantId, branchId);
        var items = entries.stream()
                .map(CalendarEntryResponse::from).toList();
        return ResponseEntity.ok(ApiResponse.success(items));
    }

    // === Day Control (ADMIN only) ===

    /**
     * Open business day at a branch. ADMIN only.
     * Per Finacle SOD: validates no other day is open, previous day is closed,
     * date exists in calendar, date is not a holiday.
     * Auto-creates default transaction batch for the day.
     */
    @PostMapping("/day/open")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CalendarEntryResponse>>
            openDay(@Valid @RequestBody DayControlRequest req) {
        LocalDate date = LocalDate.parse(req.businessDate());
        BusinessCalendar cal = businessDateService
                .openDay(date, req.branchId());
        return ResponseEntity.ok(ApiResponse.success(
                CalendarEntryResponse.from(cal),
                "Business day opened: " + req.businessDate()));
    }

    /**
     * Close business day at a branch. ADMIN only.
     * Per Finacle DAYCTRL: EOD must be complete before closing.
     */
    @PostMapping("/day/close")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CalendarEntryResponse>>
            closeDay(@Valid @RequestBody DayControlRequest req) {
        LocalDate date = LocalDate.parse(req.businessDate());
        BusinessCalendar cal = businessDateService
                .closeDay(date, req.branchId());
        return ResponseEntity.ok(ApiResponse.success(
                CalendarEntryResponse.from(cal),
                "Business day closed: " + req.businessDate()));
    }

    // === Calendar Generation (ADMIN only) ===

    /**
     * Generate business calendar for a month across all operational branches.
     * Per Finacle DAYCTRL / RBI NI Act: idempotent — safe to call multiple times.
     * Weekends auto-detected per tenant business day policy (MON_TO_SAT / MON_TO_FRI).
     */
    @PostMapping("/generate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<GenerateResponse>>
            generateCalendar(
                    @Valid @RequestBody GenerateRequest req) {
        int created = businessDateService
                .generateCalendarForMonth(
                        req.year(), req.month());
        return ResponseEntity.ok(ApiResponse.success(
                new GenerateResponse(
                        req.year(), req.month(), created),
                created + " calendar entries created"));
    }

    // === Holiday Management (ADMIN only) ===

    /**
     * Add holiday to a date across applicable branches.
     * Per RBI NI Act: NATIONAL holidays apply to all branches,
     * STATE holidays apply only to branches in the matching region.
     */
    @PostMapping("/holiday")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>>
            addHoliday(@Valid @RequestBody HolidayRequest req) {
        LocalDate date = LocalDate.parse(req.date());
        businessDateService.addHoliday(
                date, req.description(),
                req.holidayType(), req.region());
        return ResponseEntity.ok(ApiResponse.success(
                "Holiday added: " + req.date(),
                req.description()));
    }

    /**
     * Remove holiday from a date across all branches.
     * Per Finacle DAYCTRL: cannot remove holiday if day is already open/closed.
     */
    @DeleteMapping("/holiday")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>>
            removeHoliday(@RequestParam String date) {
        LocalDate parsedDate = LocalDate.parse(date);
        businessDateService.removeHoliday(parsedDate);
        return ResponseEntity.ok(ApiResponse.success(
                "Holiday removed: " + date));
    }

    // === Helper ===

    private Long resolveUserBranchId() {
        Authentication auth = SecurityContextHolder
                .getContext().getAuthentication();
        if (auth != null && auth.getPrincipal()
                instanceof BranchAwareUserDetails principal) {
            return principal.getBranchId();
        }
        return null;
    }

    // === Request DTOs ===

    public record DayControlRequest(
            @NotBlank String businessDate,
            @NotNull Long branchId) {}

    public record GenerateRequest(
            @NotNull Integer year,
            @NotNull Integer month) {}

    public record HolidayRequest(
            @NotBlank String date,
            @NotBlank String description,
            String holidayType,
            String region) {}

    // === Response DTOs ===

    /**
     * Current day status for a branch — used by dashboard banner.
     * Per Finacle DAYCTRL: the UI renders controls based on dayStatus.
     */
    public record DayStatusResponse(
            Long branchId,
            String businessDate,
            String dayStatus,
            boolean isHoliday,
            String holidayDescription,
            String dayOpenedBy,
            String dayOpenedAt,
            String previousBusinessDate) {
        static DayStatusResponse from(
                BusinessCalendar cal, Long branchId) {
            if (cal == null) {
                return new DayStatusResponse(
                        branchId, null, "NOT_OPENED", false,
                        null, null, null, null);
            }
            return new DayStatusResponse(
                    branchId,
                    cal.getBusinessDate().toString(),
                    cal.getDayStatus().name(),
                    cal.isHoliday(),
                    cal.getHolidayDescription(),
                    cal.getDayOpenedBy(),
                    cal.getDayOpenedAt() != null
                            ? cal.getDayOpenedAt().toString()
                            : null,
                    null);
        }
    }

    /**
     * Full calendar entry for calendar management screens.
     */
    public record CalendarEntryResponse(
            Long id,
            String businessDate,
            String branchCode,
            String dayStatus,
            boolean isHoliday,
            String holidayDescription,
            String holidayType,
            String holidayRegion,
            boolean eodComplete,
            boolean locked,
            String dayOpenedBy,
            String dayOpenedAt,
            String dayClosedBy,
            String dayClosedAt) {
        static CalendarEntryResponse from(
                BusinessCalendar c) {
            return new CalendarEntryResponse(
                    c.getId(),
                    c.getBusinessDate().toString(),
                    c.getBranchCode(),
                    c.getDayStatus().name(),
                    c.isHoliday(),
                    c.getHolidayDescription(),
                    c.getHolidayType(),
                    c.getHolidayRegion(),
                    c.isEodComplete(),
                    c.isLocked(),
                    c.getDayOpenedBy(),
                    c.getDayOpenedAt() != null
                            ? c.getDayOpenedAt().toString()
                            : null,
                    c.getDayClosedBy(),
                    c.getDayClosedAt() != null
                            ? c.getDayClosedAt().toString()
                            : null);
        }
    }

    public record GenerateResponse(
            int year, int month, int entriesCreated) {}
}
