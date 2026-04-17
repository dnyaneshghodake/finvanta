/**
 * Finvanta CBS - Application JavaScript
 * Offline only | No CDN | Banking Grade
 *
 * CBS Tier-1 UI per Finacle/Temenos/BNP standards:
 * - Loading overlay with double-click prevention
 * - Styled confirmation modal (replaces browser confirm())
 * - Keyboard shortcuts (F2=Save, F3=Cancel, Ctrl+P=Print)
 * - Collapsible section panels
 * - Tab navigation
 * - INR amount formatting (Indian numbering: 12,50,000.00)
 * - DataTables initialization
 * - Active sidebar highlighting
 */
document.addEventListener('DOMContentLoaded', function () {

    // ================================================================
    // LOADING OVERLAY — Per Finacle "Processing..." spinner
    // Injected once into DOM; shown on form submit to prevent double-click.
    // ================================================================
    var overlay = document.createElement('div');
    overlay.className = 'fv-loading-overlay';
    overlay.id = 'fvLoadingOverlay';
    overlay.innerHTML = '<div class="fv-loading-spinner"></div>'
        + '<div class="fv-loading-text">Processing&hellip;</div>';
    document.body.appendChild(overlay);

    function showLoading() { overlay.classList.add('active'); }
    function hideLoading() { overlay.classList.remove('active'); }
    /* Expose globally for AJAX callers */
    window.fvShowLoading = showLoading;
    window.fvHideLoading = hideLoading;

    // ================================================================
    // CONFIRMATION MODAL — Per Finacle styled modal dialogs
    // Replaces browser confirm() for [data-confirm] buttons.
    // ================================================================
    var confirmModal = document.createElement('div');
    confirmModal.className = 'modal fade fv-confirm-modal';
    confirmModal.id = 'fvConfirmModal';
    confirmModal.setAttribute('tabindex', '-1');
    confirmModal.innerHTML =
        '<div class="modal-dialog modal-dialog-centered modal-sm">'
        + '<div class="modal-content">'
        + '<div class="modal-header">'
        + '<h6 class="modal-title"><i class="bi bi-exclamation-triangle-fill me-1"></i> Confirm Action</h6>'
        + '<button type="button" class="btn-close" data-bs-dismiss="modal"></button>'
        + '</div>'
        + '<div class="modal-body">'
        + '<div class="fv-confirm-icon"><i class="bi bi-question-circle"></i></div>'
        + '<div id="fvConfirmMessage"></div>'
        + '</div>'
        + '<div class="modal-footer">'
        + '<button type="button" class="btn btn-sm btn-outline-secondary" data-bs-dismiss="modal">'
        + '<i class="bi bi-x-circle"></i> Cancel <span class="fv-kbd">Esc</span></button>'
        + '<button type="button" class="btn btn-sm btn-danger" id="fvConfirmOk">'
        + '<i class="bi bi-check-circle"></i> Confirm</button>'
        + '</div></div></div>';
    document.body.appendChild(confirmModal);

    var bsConfirmModal = null;
    var pendingConfirmAction = null;

    function initConfirmModal() {
        if (!bsConfirmModal && typeof bootstrap !== 'undefined') {
            bsConfirmModal = new bootstrap.Modal(confirmModal);
        }
    }

    document.getElementById('fvConfirmOk').addEventListener('click', function () {
        if (bsConfirmModal) bsConfirmModal.hide();
        if (pendingConfirmAction) {
            pendingConfirmAction();
            pendingConfirmAction = null;
        }
    });

    /**
     * Submit a form while ensuring the `.fv-form` submit event listener fires
     * (loading overlay + double-click prevention). `form.submit()` bypasses
     * addEventListener('submit') handlers — `requestSubmit()` does not.
     * Falls back to `form.submit()` for older browsers (IE11 / legacy Edge).
     */
    function safeFormSubmit(form) {
        if (typeof form.requestSubmit === 'function') {
            form.requestSubmit();
        } else {
            form.submit();
        }
    }

    /* Bind [data-confirm] buttons to styled modal instead of browser confirm() */
    document.querySelectorAll('[data-confirm]').forEach(function (btn) {
        btn.addEventListener('click', function (e) {
            e.preventDefault();
            e.stopPropagation();
            initConfirmModal();
            var message = this.getAttribute('data-confirm');
            document.getElementById('fvConfirmMessage').textContent = message;
            var targetBtn = this;
            pendingConfirmAction = function () {
                /* If button is inside a form, submit the form */
                var form = targetBtn.closest('form');
                if (form) {
                    safeFormSubmit(form);
                } else if (targetBtn.tagName === 'A') {
                    window.location.href = targetBtn.href;
                }
            };
            if (bsConfirmModal) bsConfirmModal.show();
        });
    });

    // ================================================================
    // REASON PROMPT MODAL — Per Finacle: styled modal replaces prompt()
    // Used for rejection/reversal flows that require a mandatory reason.
    // Triggered by buttons with data-fv-reason-prompt / data-fv-reason-confirm.
    // ================================================================
    var reasonModal = document.createElement('div');
    reasonModal.className = 'modal fade fv-confirm-modal fv-confirm-info';
    reasonModal.id = 'fvReasonModal';
    reasonModal.setAttribute('tabindex', '-1');
    reasonModal.innerHTML =
        '<div class="modal-dialog modal-dialog-centered">'
        + '<div class="modal-content">'
        + '<div class="modal-header">'
        + '<h6 class="modal-title" id="fvReasonTitle"><i class="bi bi-chat-left-text me-1"></i> Provide Reason</h6>'
        + '<button type="button" class="btn-close" data-bs-dismiss="modal"></button>'
        + '</div>'
        + '<div class="modal-body">'
        + '<label for="fvReasonInput" class="form-label fw-bold" id="fvReasonLabel">Reason (mandatory):</label>'
        + '<textarea id="fvReasonInput" class="form-control" rows="3" required minlength="3" '
        + 'placeholder="Enter reason (minimum 3 characters)"></textarea>'
        + '<div id="fvReasonError" class="text-danger small mt-1" style="display:none;">'
        + 'Reason is mandatory and must be at least 3 characters.</div>'
        + '</div>'
        + '<div class="modal-footer">'
        + '<button type="button" class="btn btn-sm btn-outline-secondary" data-bs-dismiss="modal">'
        + '<i class="bi bi-x-circle"></i> Cancel <span class="fv-kbd">Esc</span></button>'
        + '<button type="button" class="btn btn-sm btn-danger" id="fvReasonOk">'
        + '<i class="bi bi-check-circle"></i> Confirm</button>'
        + '</div></div></div>';
    document.body.appendChild(reasonModal);

    var bsReasonModal = null;
    var pendingReasonAction = null;

    function initReasonModal() {
        if (!bsReasonModal && typeof bootstrap !== 'undefined') {
            bsReasonModal = new bootstrap.Modal(reasonModal);
        }
    }

    /* Current minimum length for the active reason prompt — set per invocation */
    var reasonMinLength = 3;

    document.getElementById('fvReasonOk').addEventListener('click', function () {
        var input = document.getElementById('fvReasonInput');
        var errorDiv = document.getElementById('fvReasonError');
        var val = input.value.trim();
        if (!val || val.length < reasonMinLength) {
            errorDiv.textContent = 'Input is mandatory and must be at least '
                + reasonMinLength + ' characters.';
            errorDiv.style.display = 'block';
            input.classList.add('is-invalid');
            input.focus();
            return;
        }
        errorDiv.style.display = 'none';
        input.classList.remove('is-invalid');
        if (bsReasonModal) bsReasonModal.hide();
        if (pendingReasonAction) {
            pendingReasonAction(val);
            pendingReasonAction = null;
        }
    });

    /**
     * CBS Reason Prompt — replaces browser prompt() + confirm() for flows
     * that require a mandatory reason (rejection, reversal, etc.).
     * Called via onclick="fvPromptReason(this)" on buttons with:
     *   data-fv-reason-prompt="Label text"
     *   data-fv-reason-confirm="Confirmation message"
     *   data-fv-reason-minlength="N" (optional, default 3)
     *
     * @param {HTMLElement} btn The button that triggered the prompt
     */
    window.fvPromptReason = function (btn) {
        initReasonModal();
        var promptLabel = btn.getAttribute('data-fv-reason-prompt') || 'Reason (mandatory):';
        var confirmMsg = btn.getAttribute('data-fv-reason-confirm') || 'Confirm this action?';
        reasonMinLength = parseInt(btn.getAttribute('data-fv-reason-minlength') || '3', 10);
        document.getElementById('fvReasonLabel').textContent = promptLabel;
        document.getElementById('fvReasonTitle').innerHTML =
            '<i class="bi bi-chat-left-text me-1"></i> ' + confirmMsg;
        var input = document.getElementById('fvReasonInput');
        input.value = '';
        input.setAttribute('minlength', reasonMinLength);
        input.setAttribute('placeholder',
            'Enter value (minimum ' + reasonMinLength + ' characters)');
        input.classList.remove('is-invalid');
        document.getElementById('fvReasonError').style.display = 'none';

        var form = btn.closest('form');
        pendingReasonAction = function (reason) {
            if (form) {
                var reasonField = form.querySelector('.fv-reason-field');
                if (reasonField) reasonField.value = reason;
                safeFormSubmit(form);
            }
        };
        if (bsReasonModal) bsReasonModal.show();
        /* Focus textarea after modal animation */
        reasonModal.addEventListener('shown.bs.modal', function handler() {
            input.focus();
            reasonModal.removeEventListener('shown.bs.modal', handler);
        });
    };

    // ================================================================
    // FORM SUBMIT — Loading overlay + double-click prevention
    // ================================================================
    document.querySelectorAll('.fv-form').forEach(function (form) {
        form.addEventListener('submit', function (e) {
            if (!form.checkValidity()) {
                e.preventDefault();
                e.stopPropagation();
                form.classList.add('was-validated');
                return;
            }
            form.classList.add('was-validated');
            /* Disable submit button and show overlay */
            var submitBtn = form.querySelector('[type="submit"]');
            if (submitBtn) {
                submitBtn.disabled = true;
                submitBtn.innerHTML = '<i class="bi bi-hourglass-split"></i> Processing...';
            }
            showLoading();
        });
    });

    // ================================================================
    // COLLAPSIBLE SECTION PANELS — Per Finacle accordion
    // Click .fv-section-header to toggle the next .fv-section-body
    // ================================================================
    document.querySelectorAll('.fv-section-header').forEach(function (header) {
        header.addEventListener('click', function () {
            var body = this.nextElementSibling;
            if (!body || !body.classList.contains('fv-section-body')) return;
            var isCollapsed = this.classList.toggle('collapsed');
            if (isCollapsed) {
                body.classList.add('fv-collapsed');
            } else {
                body.classList.remove('fv-collapsed');
            }
        });
    });

    // ================================================================
    // TAB NAVIGATION — Per Finacle CIF_MASTER tabbed panels
    // ================================================================
    document.querySelectorAll('.fv-tabs .nav-link').forEach(function (tab) {
        tab.addEventListener('click', function (e) {
            e.preventDefault();
            var targetId = this.getAttribute('data-fv-tab');
            if (!targetId) return;
            /* Deactivate all tabs in this tab group */
            var tabGroup = this.closest('.fv-tabs');
            tabGroup.querySelectorAll('.nav-link').forEach(function (t) { t.classList.remove('active'); });
            this.classList.add('active');
            /* Deactivate all panes, activate target */
            var container = tabGroup.parentElement;
            container.querySelectorAll('.fv-tab-pane').forEach(function (p) { p.classList.remove('active'); });
            var targetPane = container.querySelector('#' + targetId);
            if (targetPane) targetPane.classList.add('active');
        });
    });

    // ================================================================
    // KEYBOARD SHORTCUTS — Per Finacle F2=Save, F3=Cancel, Ctrl+P=Print
    // CBS Tier-1: Added Alt+S (Save), Alt+N (New), Alt+F (Search) for
    // broader browser compatibility. F2/F3 conflict with browser defaults
    // in some environments (F2=edit cell, F3=find in Firefox).
    // ================================================================
    document.addEventListener('keydown', function (e) {
        /* F2 or Alt+S = Submit the first visible .fv-form */
        if (e.key === 'F2' || (e.altKey && (e.key === 's' || e.key === 'S'))) {
            e.preventDefault();
            var form = document.querySelector('.fv-form');
            if (form) {
                var submitBtn = form.querySelector('[type="submit"]');
                if (submitBtn && !submitBtn.disabled) submitBtn.click();
            }
        }
        /* F3 or Alt+C = Cancel / Go back */
        if (e.key === 'F3' || (e.altKey && (e.key === 'c' || e.key === 'C'))) {
            e.preventDefault();
            var cancelBtn = document.querySelector('[data-fv-cancel]');
            if (cancelBtn) {
                window.location.href = cancelBtn.href || cancelBtn.getAttribute('data-fv-cancel');
            } else {
                window.history.back();
            }
        }
        /* Alt+N = Navigate to "New" / "Add" action on current page */
        if (e.altKey && (e.key === 'n' || e.key === 'N')) {
            e.preventDefault();
            var newBtn = document.querySelector('[data-fv-new]')
                || document.querySelector('a[href*="/add"]')
                || document.querySelector('a[href*="/apply"]')
                || document.querySelector('a[href*="/open"]');
            if (newBtn) window.location.href = newBtn.href;
        }
        /* Alt+F = Focus the first search/filter input on the page */
        if (e.altKey && (e.key === 'f' || e.key === 'F')) {
            e.preventDefault();
            var searchInput = document.querySelector('input[name="q"]')
                || document.querySelector('.dataTables_filter input')
                || document.querySelector('input[type="search"]');
            if (searchInput) searchInput.focus();
        }
        /* Ctrl+P = Print (browser default, but we ensure overlay is hidden) */
        if (e.ctrlKey && e.key === 'p') {
            hideLoading();
        }
    });

    // ================================================================
    // INR AMOUNT FORMATTING — Indian numbering system (12,50,000.00)
    // Auto-formats [data-fv-type="amount"] inputs on blur.
    // ================================================================
    window.fvFormatINR = function (num) {
        if (num === null || num === undefined || isNaN(num)) return '--';
        var parts = parseFloat(num).toFixed(2).split('.');
        var intPart = parts[0];
        var decPart = parts[1];
        var lastThree = intPart.slice(-3);
        var otherNumbers = intPart.slice(0, -3);
        if (otherNumbers !== '' && otherNumbers !== '-') {
            lastThree = ',' + lastThree;
        }
        var formatted = otherNumbers.replace(/\B(?=(\d{2})+(?!\d))/g, ',') + lastThree;
        return formatted + '.' + decPart;
    };

    document.querySelectorAll('[data-fv-type="amount"]').forEach(function (input) {
        input.addEventListener('blur', function () {
            if (this.value && !isNaN(this.value)) {
                this.setAttribute('title', '\u20B9 ' + window.fvFormatINR(this.value));
            }
        });
    });

    // ================================================================
    // DataTables initialization for all CBS tables
    // ================================================================
    if (typeof jQuery !== 'undefined' && typeof jQuery.fn.DataTable !== 'undefined') {
        jQuery('.fv-datatable').each(function () {
            if (!jQuery.fn.DataTable.isDataTable(this)) {
                /* CBS: Remove JSP-rendered empty-state colspan rows before DataTables init.
                   When tables are empty, JSPs render <tr><td colspan="N">No data</td></tr>.
                   DataTables interprets this as a data row with 1 column, causing
                   "Requested unknown parameter" errors. Removing them lets DataTables
                   show its own emptyTable message instead. */
                jQuery(this).find('tbody tr').each(function () {
                    if (jQuery(this).children('td[colspan]').length === 1
                        && jQuery(this).children('td').length === 1) {
                        jQuery(this).remove();
                    }
                });
                jQuery(this).DataTable({
                    paging: true,
                    searching: true,
                    ordering: true,
                    pageLength: 25,
                    lengthMenu: [10, 25, 50, 100],
                    language: {
                        search: 'Filter:',
                        lengthMenu: 'Show _MENU_ records',
                        info: 'Showing _START_ to _END_ of _TOTAL_ records',
                        emptyTable: 'No records available',
                        zeroRecords: 'No matching records found'
                    },
                    dom: '<"row"<"col-sm-6"l><"col-sm-6"f>>rtip'
                });
            }
        });
    }

    // ================================================================
    // Active sidebar link highlighting
    // ================================================================
    var currentPath = window.location.pathname;
    document.querySelectorAll('.fv-sidebar .nav-link').forEach(function (link) {
        var href = link.getAttribute('href');
        if (href && currentPath.indexOf(href) === 0 && href !== '/') {
            link.classList.add('active');
        }
    });

    // ================================================================
    // ALERT AUTO-DISMISS — Per Finacle: success messages fade after 5s
    // ================================================================
    document.querySelectorAll('.fv-alert.alert-success').forEach(function (alert) {
        setTimeout(function () {
            alert.style.transition = 'opacity 0.5s ease';
            alert.style.opacity = '0';
            setTimeout(function () { alert.style.display = 'none'; }, 500);
        }, 5000);
    });

    // ================================================================
    // FORM DIRTY-STATE WARNING — Per Finacle: "Unsaved changes" on navigate
    // Tracks .fv-form inputs for changes; warns on beforeunload if dirty.
    // ================================================================
    var formDirty = false;
    document.querySelectorAll('.fv-form').forEach(function (form) {
        form.addEventListener('input', function () { formDirty = true; });
        form.addEventListener('change', function () { formDirty = true; });
        form.addEventListener('submit', function () { formDirty = false; });
    });
    window.addEventListener('beforeunload', function (e) {
        if (formDirty) {
            e.preventDefault();
            e.returnValue = 'You have unsaved changes. Are you sure you want to leave?';
        }
    });

    // ================================================================
    // SESSION TIMEOUT WARNING — Per Finacle: countdown before expiry
    // Shows a warning banner 2 minutes before the server session expires.
    // Default session timeout: 30 minutes (1800s). Configurable via
    // data-fv-session-timeout attribute on <body>.
    // ================================================================
    var sessionTimeout = parseInt(document.body.getAttribute('data-fv-session-timeout') || '1800', 10);
    var warningAt = (sessionTimeout - 120) * 1000; /* 2 minutes before expiry */
    var expiryAt = sessionTimeout * 1000;

    var sessionBanner = document.createElement('div');
    sessionBanner.className = 'fv-session-warning';
    sessionBanner.id = 'fvSessionWarning';
    sessionBanner.innerHTML = '<i class="bi bi-clock-history"></i> '
        + 'Session expires in <strong id="fvSessionCountdown">2:00</strong> minutes. '
        + '<button type="button" class="btn btn-sm btn-warning ms-2" id="fvSessionExtend">'
        + '<i class="bi bi-arrow-clockwise"></i> Extend Session</button>';
    document.body.appendChild(sessionBanner);

    var sessionTimer = null;
    var countdownTimer = null;

    function showSessionWarning() {
        sessionBanner.classList.add('active');
        var remaining = 120;
        countdownTimer = setInterval(function () {
            remaining--;
            var m = Math.floor(remaining / 60);
            var s = remaining % 60;
            var el = document.getElementById('fvSessionCountdown');
            if (el) el.textContent = m + ':' + (s < 10 ? '0' : '') + s;
            if (remaining <= 0) {
                clearInterval(countdownTimer);
                /* CBS Tier-1: Redirect to login using context path, not relative path.
                   The previous implementation stripped the last path segment, which broke
                   for multi-segment paths (e.g., /customer/view/123 → /customer/view/login).
                   Per Finacle/Temenos: session expiry always redirects to the login page
                   at the application context root. */
                var ctx = document.querySelector('meta[name="ctx"]');
                var base = ctx ? ctx.getAttribute('content') : '';
                window.location.href = base + '/login?expired=true';
            }
        }, 1000);
    }

    function resetSessionTimer() {
        sessionBanner.classList.remove('active');
        if (sessionTimer) clearTimeout(sessionTimer);
        if (countdownTimer) clearInterval(countdownTimer);
        sessionTimer = setTimeout(showSessionWarning, warningAt);
    }

    document.getElementById('fvSessionExtend').addEventListener('click', function () {
        /* Ping server to extend session, then reset timer */
        fetch(window.location.href, { method: 'HEAD', credentials: 'same-origin' })
            .then(function () { resetSessionTimer(); })
            .catch(function () { resetSessionTimer(); });
    });

    /* Reset timer on any user interaction */
    ['click', 'keydown', 'scroll'].forEach(function (evt) {
        document.addEventListener(evt, function () {
            if (!sessionBanner.classList.contains('active')) {
                resetSessionTimer();
            }
        }, { passive: true });
    });
    resetSessionTimer();

});
