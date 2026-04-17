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
                    form.submit();
                } else if (targetBtn.tagName === 'A') {
                    window.location.href = targetBtn.href;
                }
            };
            if (bsConfirmModal) bsConfirmModal.show();
        });
    });

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
    // ================================================================
    document.addEventListener('keydown', function (e) {
        /* F2 = Submit the first visible .fv-form */
        if (e.key === 'F2') {
            e.preventDefault();
            var form = document.querySelector('.fv-form');
            if (form) {
                var submitBtn = form.querySelector('[type="submit"]');
                if (submitBtn && !submitBtn.disabled) submitBtn.click();
            }
        }
        /* F3 = Cancel / Go back */
        if (e.key === 'F3') {
            e.preventDefault();
            var cancelBtn = document.querySelector('[data-fv-cancel]');
            if (cancelBtn) {
                window.location.href = cancelBtn.href || cancelBtn.getAttribute('data-fv-cancel');
            } else {
                window.history.back();
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

});
