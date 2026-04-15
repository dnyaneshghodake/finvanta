/**
 * Finvanta CBS — Centralized Input Validation Library
 * Per Finacle FIELD_TYPE_MASTER / Temenos EB.VALIDATION / BNP CORTEX FIELD_REGISTRY.
 *
 * In Tier-1 CBS platforms, input validation is NEVER scattered across individual
 * screens. It resides in a centralized validation library that every form references.
 *
 * Usage — declarative via data-fv-type attributes on any input:
 *   <input data-fv-type="code"       />  Uppercase alphanumeric + underscore
 *   <input data-fv-type="name"       />  Letters, digits, spaces, hyphens, parens
 *   <input data-fv-type="alpha"      />  Letters and spaces only
 *   <input data-fv-type="numeric"    />  Digits only (whole numbers, positive)
 *   <input data-fv-type="amount"     />  Positive decimal (0.01 step, min 0)
 *   <input data-fv-type="rate"       />  Percentage 0-100
 *   <input data-fv-type="penal-rate" />  Percentage 0-36 (RBI usury ceiling)
 *   <input data-fv-type="tenure"     />  Positive integer 1-600 months
 *   <input data-fv-type="pan"        />  Indian PAN: ABCDE1234F
 *   <input data-fv-type="ifsc"       />  IFSC: ABCD0123456
 *   <input data-fv-type="mobile"     />  Indian mobile: 10 digits starting 6-9
 *   <input data-fv-type="email"      />  Email format
 *   <input data-fv-type="pincode"    />  Indian PIN: 6 digits
 *   <input data-fv-type="account-no" />  Alphanumeric, no spaces
 *   <input data-fv-type="no-special" />  Alphanumeric + spaces only
 *   <input data-fv-type="uppercase"  />  Auto-uppercase only
 *
 * Cross-field: data-fv-max-field="maxRate" data-fv-label="Min Rate"
 *
 * Server-side validation is AUTHORITATIVE. This is defense-in-depth UX.
 */
var FV = FV || {};

FV.Validation = (function() {
    'use strict';

    /* ================================================================
     * FIELD TYPE REGISTRY — Per Finacle FIELD_TYPE_MASTER
     * ================================================================ */
    var TYPES = {
        'code': {
            re: /^[A-Z0-9_]{2,50}$/,
            strip: /[^A-Z0-9_]/g,
            msg: '2-50 chars: uppercase letters, digits, underscore only',
            tx: 'upper', a: { minlength: 2, maxlength: 50 }
        },
        'name': {
            re: /^[A-Za-z0-9\s\-\(\)\/&,.]{2,200}$/,
            strip: /[^A-Za-z0-9\s\-\(\)\/&,.]/g,
            msg: 'Letters, digits, spaces, hyphens, parentheses, slashes, ampersands, commas, periods only',
            a: { minlength: 2, maxlength: 200 }
        },
        'alpha': {
            re: /^[A-Za-z\s]{1,200}$/,
            strip: /[^A-Za-z\s]/g,
            msg: 'Letters and spaces only',
            a: { maxlength: 200 }
        },
        'numeric': {
            re: /^[0-9]+$/,
            strip: /[^0-9]/g,
            msg: 'Positive whole numbers only',
            a: { min: 0, step: 1 }
        },
        'amount': {
            msg: 'Positive amount (up to 2 decimal places)',
            a: { min: 0, step: 0.01 }, numType: 'number'
        },
        'rate': {
            msg: 'Percentage between 0% and 100%',
            a: { min: 0, max: 100, step: 0.01 }, numType: 'number'
        },
        'penal-rate': {
            msg: 'Penal rate 0-36% (RBI usury ceiling)',
            a: { min: 0, max: 36, step: 0.01 }, numType: 'number'
        },
        'tenure': {
            msg: 'Tenure in months (1-600)',
            a: { min: 1, max: 600, step: 1 }, numType: 'number'
        },
        'pan': {
            re: /^[A-Z]{5}[0-9]{4}[A-Z]$/,
            strip: /[^A-Z0-9]/g,
            msg: 'Indian PAN: ABCDE1234F',
            tx: 'upper', a: { minlength: 10, maxlength: 10 }
        },
        'ifsc': {
            re: /^[A-Z]{4}0[A-Z0-9]{6}$/,
            strip: /[^A-Z0-9]/g,
            msg: 'IFSC: ABCD0123456',
            tx: 'upper', a: { minlength: 11, maxlength: 11 }
        },
        'mobile': {
            re: /^[6-9][0-9]{9}$/,
            strip: /[^0-9]/g,
            msg: '10 digits starting with 6-9',
            a: { minlength: 10, maxlength: 10 }
        },
        'email': {
            re: /^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}$/,
            msg: 'Valid email address',
            a: { maxlength: 200 }
        },
        'pincode': {
            re: /^[1-9][0-9]{5}$/,
            strip: /[^0-9]/g,
            msg: 'Indian PIN code: 6 digits',
            a: { minlength: 6, maxlength: 6 }
        },
        'account-no': {
            re: /^[A-Za-z0-9]{4,40}$/,
            strip: /[^A-Za-z0-9]/g,
            msg: 'Alphanumeric, no spaces',
            a: { minlength: 4, maxlength: 40 }
        },
        'no-special': {
            re: /^[A-Za-z0-9\s]{1,500}$/,
            strip: /[^A-Za-z0-9\s]/g,
            msg: 'No special characters allowed'
        },
        'uppercase': {
            tx: 'upper'
        }
    };

    /* ================================================================
     * INIT — Auto-discover all data-fv-type fields on DOMContentLoaded
     * ================================================================ */
    function init() {
        injectSpinnerCSS();
        document.querySelectorAll('[data-fv-type]').forEach(function(el) {
            bindField(el);
        });
        document.querySelectorAll('form.fv-form, form[data-fv-validate]').forEach(function(form) {
            bindForm(form);
        });
    }

    /* ================================================================
     * CSS INJECTION — Hide number field spinner arrows globally
     * Per Finacle FIELD_TYPE_MASTER / Temenos EB.VALIDATION:
     * Financial amount fields must NOT have spinner arrows because:
     *   1. Spinners can decrement below zero (negative amounts)
     *   2. Spinners increment by 'step' which may not match business logic
     *   3. Accidental scroll/click on spinner changes financial values silently
     *   4. Tier-1 CBS UX standard: amounts are typed, never spun
     *
     * Applied to all inputs with class 'fv-no-spinner' (added by bindField
     * for positive-only number types: amount, rate, penal-rate, tenure, numeric).
     * ================================================================ */
    function injectSpinnerCSS() {
        if (document.getElementById('fv-spinner-css')) return;
        var style = document.createElement('style');
        style.id = 'fv-spinner-css';
        style.textContent =
            '/* CBS: Hide number spinners on financial fields per Finacle FIELD_TYPE_MASTER */\n' +
            'input.fv-no-spinner::-webkit-outer-spin-button,\n' +
            'input.fv-no-spinner::-webkit-inner-spin-button {\n' +
            '    -webkit-appearance: none;\n' +
            '    margin: 0;\n' +
            '}\n' +
            'input.fv-no-spinner[type="number"] {\n' +
            '    -moz-appearance: textfield;\n' +
            '}\n';
        document.head.appendChild(style);
    }

    /* ================================================================
     * BIND FIELD — Apply type rules to a single input element
     * ================================================================ */
    function bindField(el) {
        var t = TYPES[el.getAttribute('data-fv-type')];
        if (!t) return;

        // HTML5 attributes
        if (t.a) {
            for (var k in t.a) {
                if (!el.hasAttribute(k)) el.setAttribute(k, t.a[k]);
            }
        }
        if (t.msg && !el.title) el.title = t.msg;
        if (t.re && !el.getAttribute('pattern')) el.setAttribute('pattern', t.re.source);
        if (t.numType && el.type === 'text') el.type = t.numType;

        // Real-time keystroke filter + transform
        if (t.strip || t.tx) {
            el.addEventListener('input', function() {
                var v = this.value;
                if (t.tx === 'upper') v = v.toUpperCase();
                if (t.strip) v = v.replace(t.strip, '');
                if (v !== this.value) {
                    var p = this.selectionStart;
                    this.value = v;
                    try { this.setSelectionRange(p, p); } catch(e) {}
                }
            });
        }

        // Blur validation — visual feedback
        if (t.re) {
            el.addEventListener('blur', function() {
                if (this.value && !t.re.test(this.value)) {
                    this.classList.add('is-invalid');
                    ensureFeedback(this, t.msg);
                } else {
                    this.classList.remove('is-invalid');
                }
            });
        }

        // === CBS CRITICAL: Positive-only number field enforcement ===
        // Per Finacle FIELD_TYPE_MASTER / Temenos EB.VALIDATION:
        // Financial amount fields must NEVER accept negative values.
        // In banking, a negative amount is a different transaction type
        // (debit vs credit), not a negative number in the same field.
        //
        // Attack vectors blocked:
        //   1. Keyboard: '-', 'e', 'E' keys blocked on keydown
        //   2. Spinner arrows: CSS hides them (see injectSpinnerCSS below)
        //   3. Paste/autofill: 'input' event clamps value to min bound
        //   4. Mouse wheel: 'wheel' event prevented on focused number fields
        //   5. Form submit: final negative check in bindForm validation
        var isPositiveNum = (t.numType === 'number' || el.type === 'number')
            && t.a && t.a.min !== undefined && t.a.min >= 0;

        if (isPositiveNum) {
            // Mark for CSS spinner removal
            el.classList.add('fv-no-spinner');

            // 1. Block negative/scientific keys
            el.addEventListener('keydown', function(e) {
                if (e.key === '-' || e.key === 'e' || e.key === 'E') {
                    e.preventDefault();
                }
            });

            // 2. Clamp on input (catches paste, autofill, spinner overshoot)
            el.addEventListener('input', function() {
                if (this.value === '') return;
                var val = parseFloat(this.value);
                if (isNaN(val)) return;
                var minVal = parseFloat(this.getAttribute('min'));
                var maxVal = parseFloat(this.getAttribute('max'));
                if (!isNaN(minVal) && val < minVal) {
                    this.value = minVal;
                }
                if (!isNaN(maxVal) && val > maxVal) {
                    this.value = maxVal;
                }
            });

            // 3. Block mouse wheel on focused number fields
            el.addEventListener('wheel', function(e) {
                if (document.activeElement === this) {
                    e.preventDefault();
                }
            }, { passive: false });

            // 4. Clamp on blur (final safety net)
            el.addEventListener('blur', function() {
                if (this.value === '') return;
                var val = parseFloat(this.value);
                if (isNaN(val)) return;
                var minVal = parseFloat(this.getAttribute('min'));
                if (!isNaN(minVal) && val < minVal) {
                    this.value = minVal;
                    this.classList.add('is-invalid');
                    ensureFeedback(this, t.msg || 'Value cannot be negative');
                }
            });
        } else if (t.numType === 'number' || el.type === 'number') {
            // Non-positive number fields (rare in CBS) — still block 'e'
            el.addEventListener('keydown', function(e) {
                if (e.key === 'e' || e.key === 'E') {
                    e.preventDefault();
                }
            });
        }
    }

    /* ================================================================
     * BIND FORM — Cross-field validation on submit
     * ================================================================ */
    function bindForm(form) {
        form.addEventListener('submit', function(e) {
            var errs = [];

            // Per-field pattern check
            form.querySelectorAll('[data-fv-type]').forEach(function(el) {
                var t = TYPES[el.getAttribute('data-fv-type')];
                if (!t || !t.re || !el.value) return;
                if (!t.re.test(el.value)) {
                    var lbl = el.getAttribute('data-fv-label')
                        || labelFor(el) || el.name;
                    errs.push(lbl + ': ' + t.msg);
                    el.classList.add('is-invalid');
                }
            });

            // Negative value check — final safety net for all positive-only fields
            // Catches any negative that slipped through keydown/input/blur guards
            form.querySelectorAll('.fv-no-spinner').forEach(function(el) {
                if (el.value === '') return;
                var val = parseFloat(el.value);
                var minVal = parseFloat(el.getAttribute('min'));
                if (!isNaN(val) && !isNaN(minVal) && val < minVal) {
                    var lbl = el.getAttribute('data-fv-label')
                        || labelFor(el) || el.name;
                    errs.push(lbl + ' cannot be less than ' + minVal + '. Current value: ' + val);
                    el.classList.add('is-invalid');
                    el.value = minVal;
                }
            });

            // Cross-field min <= max
            form.querySelectorAll('[data-fv-max-field]').forEach(function(minEl) {
                var maxEl = form.querySelector('[name="' + minEl.getAttribute('data-fv-max-field') + '"]');
                if (!minEl.value || !maxEl || !maxEl.value) return;
                var lo = parseFloat(minEl.value), hi = parseFloat(maxEl.value);
                if (!isNaN(lo) && !isNaN(hi) && lo > hi) {
                    var ml = minEl.getAttribute('data-fv-label') || minEl.name;
                    var xl = maxEl.getAttribute('data-fv-label') || maxEl.name;
                    errs.push(ml + ' (' + lo + ') cannot exceed ' + xl + ' (' + hi + ').');
                }
            });

            if (errs.length > 0) {
                e.preventDefault();
                e.stopPropagation();
                showErrors(form, errs);
                return false;
            }
        });
    }

    /* ================================================================
     * ERROR DISPLAY — Bootstrap alert banner at top of form
     * ================================================================ */
    function showErrors(form, errs) {
        var old = document.getElementById('fv-validation-errors');
        if (old) old.remove();

        var div = document.createElement('div');
        div.id = 'fv-validation-errors';
        div.className = 'alert alert-danger alert-dismissible fade show mt-2';
        div.setAttribute('role', 'alert');

        var h = '<strong><i class="bi bi-exclamation-triangle"></i> Validation Error</strong><ul class="mb-0 mt-1">';
        for (var i = 0; i < errs.length; i++) {
            h += '<li>' + esc(errs[i]) + '</li>';
        }
        h += '</ul><button type="button" class="btn-close" data-bs-dismiss="alert"></button>';
        div.innerHTML = h;

        form.insertBefore(div, form.firstChild);
        div.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }

    /* ================================================================
     * HELPERS
     * ================================================================ */
    function labelFor(el) {
        // Try previous sibling label, or parent's label
        var lbl = el.previousElementSibling;
        if (lbl && lbl.tagName === 'LABEL') return lbl.textContent.replace('*', '').trim();
        var parent = el.closest('.col-md-2, .col-md-3, .col-md-4, .col');
        if (parent) {
            lbl = parent.querySelector('label');
            if (lbl) return lbl.textContent.replace('*', '').trim();
        }
        return null;
    }

    function ensureFeedback(el, msg) {
        var next = el.nextElementSibling;
        if (next && next.classList.contains('invalid-feedback')) {
            next.textContent = msg;
        } else {
            var fb = document.createElement('div');
            fb.className = 'invalid-feedback';
            fb.textContent = msg;
            el.parentNode.insertBefore(fb, el.nextSibling);
        }
    }

    function esc(s) {
        var d = document.createElement('div');
        d.appendChild(document.createTextNode(s));
        return d.innerHTML;
    }

    // Public API
    return {
        init: init,
        TYPES: TYPES,
        bindField: bindField
    };
})();

// Auto-initialize on DOM ready
document.addEventListener('DOMContentLoaded', FV.Validation.init);
