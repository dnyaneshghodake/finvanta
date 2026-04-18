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
    // WCAG 2.1 AA — role="alert" on all .fv-alert elements
    // Per RBI Accessibility / WCAG SC 4.1.3: status messages must be
    // programmatically exposed to assistive technologies. login.jsp has
    // role="alert" but other JSPs omit it. Setting it centrally here
    // ensures all server-rendered alerts are announced by screen readers.
    // ================================================================
    document.querySelectorAll('.fv-alert').forEach(function (el) {
        if (!el.hasAttribute('role')) el.setAttribute('role', 'alert');
    });

    // ================================================================
    // WCAG 2.1 AA — scope="col" on all <th> in <thead>
    // Per WCAG SC 1.3.1 (Info and Relationships): table headers must
    // programmatically identify the cells they relate to. Setting
    // scope="col" centrally avoids editing every JSP table header.
    // ================================================================
    document.querySelectorAll('.fv-table thead th, .fv-datatable thead th').forEach(function (th) {
        if (!th.hasAttribute('scope')) th.setAttribute('scope', 'col');
    });

    // ================================================================
    // CONFIRMATION MODAL — Per Finacle styled modal dialogs
    // Replaces browser confirm() for [data-confirm] buttons.
    // WCAG: aria-labelledby + aria-describedby for screen readers.
    // ================================================================
    var confirmModal = document.createElement('div');
    confirmModal.className = 'modal fade fv-confirm-modal';
    confirmModal.id = 'fvConfirmModal';
    confirmModal.setAttribute('tabindex', '-1');
    confirmModal.setAttribute('aria-labelledby', 'fvConfirmTitle');
    confirmModal.setAttribute('aria-describedby', 'fvConfirmMessage');
    confirmModal.innerHTML =
        '<div class="modal-dialog modal-dialog-centered modal-sm">'
        + '<div class="modal-content">'
        + '<div class="modal-header">'
        + '<h6 class="modal-title" id="fvConfirmTitle"><i class="bi bi-exclamation-triangle-fill me-1"></i> Confirm Action</h6>'
        + '<button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>'
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
     * Submit a form with loading overlay and double-click prevention.
     * Shows the overlay and disables the submit button BEFORE calling
     * form.submit(). We use form.submit() (not requestSubmit()) because
     * requestSubmit() fires the submit event handler which disables the
     * button — and then requestSubmit() itself fails because the submitter
     * is disabled. form.submit() bypasses event handlers but we manually
     * trigger the overlay here to compensate.
     */
    function safeFormSubmit(form) {
        var submitBtn = form.querySelector('[type="submit"]');
        if (submitBtn) {
            submitBtn.disabled = true;
            submitBtn.innerHTML = '<i class="bi bi-hourglass-split"></i> Processing...';
        }
        /* Reset dirty flag BEFORE submit — form.submit() bypasses the submit
           event listener (which normally resets formDirty), so the beforeunload
           handler would show "You have unsaved changes" and block navigation. */
        formDirty = false;
        showLoading();
        form.submit();
    }

    /* Bind [data-confirm] buttons to styled modal instead of browser confirm().
       CBS Tier-1: uses event delegation on document.body so dynamically-added
       [data-confirm] buttons (e.g., via AJAX pagination) are also handled. */
    document.body.addEventListener('click', function (e) {
        var btn = e.target.closest('[data-confirm]');
        if (!btn) return;
        e.preventDefault();
        e.stopPropagation();
        initConfirmModal();
        var message = btn.getAttribute('data-confirm');
        document.getElementById('fvConfirmMessage').textContent = message;
        pendingConfirmAction = function () {
            /* If button is inside a form, submit the form */
            var form = btn.closest('form');
            if (form) {
                safeFormSubmit(form);
            } else if (btn.tagName === 'A') {
                window.location.href = btn.href;
            }
        };
        if (bsConfirmModal) bsConfirmModal.show();
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
    // CBS Tier-1: Full enterprise shortcut set per audit blueprint:
    //   F2 / Alt+S  = Save/Submit
    //   F3 / Alt+C  = Cancel/Back
    //   Alt+N       = New/Add
    //   Alt+F       = Focus search
    //   Alt+A       = Approve (maker-checker)
    //   Alt+R       = Reject (maker-checker)
    //   Ctrl+Enter  = Submit focused form
    //   Ctrl+P      = Print
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
        /* Alt+A = Approve — clicks the first visible Approve button on maker-checker screens.
           Per Finacle/Temenos: approval is a high-frequency action for CHECKER role.
           The button's data-confirm will still trigger the styled modal before execution. */
        if (e.altKey && (e.key === 'a' || e.key === 'A')) {
            e.preventDefault();
            var approveBtn = document.querySelector('[data-fv-approve]')
                || document.querySelector('button.btn-fv-success[data-confirm]');
            if (approveBtn && !approveBtn.disabled) approveBtn.click();
        }
        /* Alt+R = Reject — clicks the first visible Reject button.
           The button's data-confirm or fvPromptReason will still fire. */
        if (e.altKey && (e.key === 'r' || e.key === 'R')) {
            e.preventDefault();
            var rejectBtn = document.querySelector('[data-fv-reject]')
                || document.querySelector('button.btn-fv-danger[data-confirm]');
            if (rejectBtn && !rejectBtn.disabled) rejectBtn.click();
        }
        /* Ctrl+Enter = Submit the form that contains the currently focused element.
           Per CBS enterprise standard: Ctrl+Enter submits from any field in the form
           without requiring Tab to the submit button. */
        if (e.ctrlKey && e.key === 'Enter') {
            e.preventDefault();
            var focusedForm = document.activeElement ? document.activeElement.closest('form') : null;
            if (focusedForm) {
                var formSubmitBtn = focusedForm.querySelector('[type="submit"]');
                if (formSubmitBtn && !formSubmitBtn.disabled) formSubmitBtn.click();
            }
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
    // CBS TRANSACTION PREVIEW — Per Finacle TRAN_PREVIEW / Temenos OFS.VALIDATE
    // Calls /deposit/preview/{accountNumber} on amount blur to show a pre-posting
    // validation checklist. Renders into #txnPreviewPanel on deposit/withdraw pages.
    // ================================================================
    (function initTxnPreview() {
        var panel = document.getElementById('txnPreviewPanel');
        if (!panel) return; // Not on a transaction form page

        var amountInput = document.querySelector('input[data-fv-type="amount"]');
        if (!amountInput) return;

        // Detect transaction type and account number from the form action URL
        var form = amountInput.closest('form');
        if (!form) return;
        var action = form.getAttribute('action') || '';
        var txnType = action.indexOf('/deposit/deposit/') >= 0 ? 'CASH_DEPOSIT'
            : action.indexOf('/deposit/withdraw/') >= 0 ? 'CASH_WITHDRAWAL' : null;
        if (!txnType) return;

        // Extract account number from the URL path
        var pathParts = action.split('/');
        var accountNumber = pathParts[pathParts.length - 1];
        if (!accountNumber) return;

        var ctx = document.querySelector('meta[name="ctx"]');
        var basePath = ctx ? ctx.getAttribute('content') : '';
        var debounceTimer = null;

        amountInput.addEventListener('blur', function () {
            var amount = parseFloat(this.value);
            if (!amount || amount <= 0) {
                panel.style.display = 'none';
                return;
            }
            // Debounce: wait 300ms after blur before calling preview
            clearTimeout(debounceTimer);
            debounceTimer = setTimeout(function () {
                fetchPreview(accountNumber, amount, txnType);
            }, 300);
        });

        function fetchPreview(accNo, amount, type) {
            var url = basePath + '/deposit/preview/' + encodeURIComponent(accNo)
                + '?amount=' + amount + '&txnType=' + encodeURIComponent(type);
            fetch(url, { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
                .then(function (resp) { return resp.json(); })
                .then(function (preview) { renderPreview(preview); })
                .catch(function (err) {
                    panel.innerHTML = '<div class="alert alert-warning small"><i class="bi bi-exclamation-triangle"></i> Preview unavailable: ' + err.message + '</div>';
                    panel.style.display = 'block';
                });
        }

        function renderPreview(p) {
            if (!p || !p.checks || p.checks.length === 0) {
                panel.style.display = 'none';
                return;
            }
            var canPost = p.canPost;
            var blockers = p.blockerCount || 0;
            var html = '<div class="fv-card ' + (canPost ? 'fv-card-pass' : 'fv-card-fail') + '">';
            html += '<div class="card-header"><i class="bi ' + (canPost ? 'bi-check-circle-fill' : 'bi-exclamation-triangle-fill') + '"></i> ';
            html += 'Transaction Preview &mdash; ';
            if (canPost) {
                html += '<strong>ALL CHECKS PASSED</strong>';
                if (p.requiresApproval) {
                    html += ' <span class="badge bg-warning text-dark ms-2"><i class="bi bi-hourglass-split"></i> Requires Checker Approval</span>';
                }
            } else {
                html += '<strong>' + blockers + ' BLOCKER' + (blockers > 1 ? 'S' : '') + ' FOUND</strong>';
            }
            html += '</div><div class="card-body p-0">';

            // Account summary row
            if (p.accountNumber) {
                html += '<div class="px-3 py-2 small" style="background:#f8f9fa;border-bottom:1px solid var(--fv-border);">';
                html += '<strong>' + escHtml(p.accountNumber) + '</strong>';
                if (p.accountHolder) html += ' &mdash; ' + escHtml(p.accountHolder);
                if (p.branchCode) html += ' | Branch: ' + escHtml(p.branchCode);
                if (p.currentBalance != null) html += ' | Balance: <strong>INR ' + fmtNum(p.currentBalance) + '</strong>';
                if (p.projectedBalance != null) html += ' &rarr; <strong>INR ' + fmtNum(p.projectedBalance) + '</strong>';
                html += '</div>';
            }

            // Validation checklist table
            html += '<table class="table table-sm mb-0"><thead><tr><th style="width:30px;"></th><th>Category</th><th>Check</th><th>Status</th><th>Detail</th></tr></thead><tbody>';
            for (var i = 0; i < p.checks.length; i++) {
                var c = p.checks[i];
                var icon = c.passed ? '<i class="bi bi-check-circle-fill text-success"></i>'
                    : '<i class="bi bi-x-circle-fill text-danger"></i>';
                html += '<tr' + (!c.passed ? ' class="table-danger"' : '') + '>';
                html += '<td class="text-center">' + icon + '</td>';
                html += '<td><strong>' + escHtml(c.category) + '</strong></td>';
                html += '<td>' + escHtml(c.description) + '</td>';
                html += '<td>' + (c.passed ? '<span class="fv-badge fv-badge-active">PASS</span>' : '<span class="fv-badge fv-badge-rejected">BLOCKED</span>') + '</td>';
                html += '<td class="small text-muted">' + escHtml(c.detail || '') + '</td>';
                html += '</tr>';
            }
            html += '</tbody></table>';

            // GL Journal Lines preview
            if (p.journalLines && p.journalLines.length > 0) {
                html += '<div class="px-3 py-2 small" style="background:#e8eaf6;border-top:2px solid var(--fv-primary);"><strong><i class="bi bi-journal-text"></i> GL Journal Lines (Double-Entry)</strong></div>';
                html += '<table class="table table-sm mb-0"><thead><tr><th>GL Code</th><th>GL Name</th><th>DR/CR</th><th class="text-end">Amount</th><th>Narration</th></tr></thead><tbody>';
                for (var j = 0; j < p.journalLines.length; j++) {
                    var gl = p.journalLines[j];
                    html += '<tr><td class="font-monospace">' + escHtml(gl.glCode) + '</td>';
                    html += '<td>' + escHtml(gl.glName) + '</td>';
                    html += '<td><span class="' + (gl.debitCredit === 'DEBIT' ? 'text-danger' : 'text-success') + '">' + gl.debitCredit + '</span></td>';
                    html += '<td class="text-end amount">' + fmtNum(gl.amount) + '</td>';
                    html += '<td class="small">' + escHtml(gl.narration || '') + '</td></tr>';
                }
                html += '</tbody></table>';
            }

            html += '</div></div>';
            panel.innerHTML = html;
            panel.style.display = 'block';
        }

        function escHtml(s) {
            if (!s) return '';
            var d = document.createElement('div');
            d.textContent = s;
            return d.innerHTML;
        }

        function fmtNum(n) {
            if (n === null || n === undefined) return '--';
            return typeof window.fvFormatINR === 'function' ? window.fvFormatINR(n) : Number(n).toFixed(2);
        }
    })();

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
