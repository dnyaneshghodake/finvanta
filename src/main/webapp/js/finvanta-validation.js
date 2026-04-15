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
        document.querySelectorAll('[data-fv-type]').forEach(function(el) {
            bindField(el);
        });
        document.querySelectorAll('form.fv-form, form[data-fv-validate]').forEach(function(form) {
            bindForm(form);
        });
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

        // Number fields: block negative on keydown
        if (t.numType === 'number' || el.type === 'number') {
            el.addEventListener('keydown', function(e) {
                if (e.key === '-' || e.key === 'e' || e.key === 'E') {
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

            // Cross-field min <= max
            form.querySelectorAll('[data-fv-max-field]').forEach(function(minEl) {
                var maxEl = form.querySelector('[name="' + minEl.getAttribute('data-fv-max-field') + '"]');
                if (!minEl.value || !maxEl || !maxEl.value) return;
                var lo = parseFloat(minEl.value), hi = parseFloat(maxEl.value);
                if (!isNaN(lo) && !isNaN(hi) && lo > hi && hi > 0) {
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
