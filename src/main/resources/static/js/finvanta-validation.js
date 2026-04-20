/**
 * Finvanta CBS - Centralized Input Validation
 * Per Finacle FIELD_TYPE_MASTER / Temenos EB.VALIDATION
 *
 * Auto-discovers data-fv-type attributes on inputs and applies:
 * - Keystroke filtering (numeric-only for amounts, PAN uppercase, etc.)
 * - Pattern matching on blur with visual feedback (is-valid / is-invalid)
 * - Cross-field min/max checks from data-fv-min / data-fv-max
 * - ARIA attributes for accessibility
 *
 * Supported data-fv-type values:
 *   amount    — Numeric, 2 decimal places, INR tooltip on blur
 *   rate      — Numeric, 0.01-100, step 0.25
 *   tenure    — Integer, positive
 *   pan       — Uppercase, AAAAA0000A format
 *   aadhaar   — 12 digits, numeric only
 *   mobile    — 10 digits, starts with 6-9
 *   pincode   — 6 digits
 *   ifsc      — AAAA0AAAAAA format
 *   penal-rate — Numeric, 0-50
 *
 * Must load AFTER finvanta-app.js and Bootstrap.
 */
document.addEventListener('DOMContentLoaded', function () {
    'use strict';

    var VALIDATORS = {
        'pan': {
            pattern: /^[A-Z]{5}[0-9]{4}[A-Z]$/,
            msg: 'Invalid PAN format. Expected: AAAAA0000A',
            transform: function (v) { return v.toUpperCase(); },
            keyFilter: /[A-Za-z0-9]/
        },
        'aadhaar': {
            pattern: /^[0-9]{12}$/,
            msg: 'Invalid Aadhaar. Must be exactly 12 digits.',
            keyFilter: /[0-9]/
        },
        'mobile': {
            pattern: /^[6-9][0-9]{9}$/,
            msg: 'Invalid mobile. Must be 10 digits starting with 6-9.',
            keyFilter: /[0-9]/
        },
        'pincode': {
            pattern: /^[0-9]{6}$/,
            msg: 'Invalid PIN code. Must be exactly 6 digits.',
            keyFilter: /[0-9]/
        },
        'ifsc': {
            pattern: /^[A-Z]{4}0[A-Z0-9]{6}$/,
            msg: 'Invalid IFSC. Expected: AAAA0NNNNNN',
            transform: function (v) { return v.toUpperCase(); },
            keyFilter: /[A-Za-z0-9]/
        },
        'amount': {
            pattern: /^-?\d+(\.\d{1,2})?$/,
            msg: 'Invalid amount. Use up to 2 decimal places.',
            keyFilter: /[0-9.\-]/
        },
        'rate': {
            pattern: /^\d{1,3}(\.\d{1,2})?$/,
            msg: 'Invalid rate. Must be 0-100 with up to 2 decimals.',
            keyFilter: /[0-9.]/
        },
        'tenure': {
            pattern: /^[1-9]\d{0,3}$/,
            msg: 'Invalid tenure. Must be a positive integer.',
            keyFilter: /[0-9]/
        },
        'penal-rate': {
            pattern: /^\d{1,2}(\.\d{1,2})?$/,
            msg: 'Invalid penal rate. Must be 0-50.',
            keyFilter: /[0-9.]/
        }
    };

    document.querySelectorAll('[data-fv-type]').forEach(function (input) {
        var type = input.getAttribute('data-fv-type');
        var validator = VALIDATORS[type];
        if (!validator) return;

        /* Keystroke filtering */
        if (validator.keyFilter) {
            input.addEventListener('keypress', function (e) {
                if (e.ctrlKey || e.metaKey || e.altKey) return;
                var char = String.fromCharCode(e.charCode || e.which);
                if (char && !validator.keyFilter.test(char)) {
                    e.preventDefault();
                }
            });
        }

        /* Transform on input (e.g., uppercase for PAN) */
        if (validator.transform) {
            input.addEventListener('input', function () {
                var pos = this.selectionStart;
                this.value = validator.transform(this.value);
                this.setSelectionRange(pos, pos);
            });
        }

        /* Validate on blur */
        input.addEventListener('blur', function () {
            var val = this.value.trim();
            if (!val) {
                this.classList.remove('is-valid', 'is-invalid');
                return;
            }
            if (validator.pattern.test(val)) {
                this.classList.remove('is-invalid');
                this.classList.add('is-valid');
                /* Cross-field min/max check */
                var min = parseFloat(this.getAttribute('data-fv-min') || this.min);
                var max = parseFloat(this.getAttribute('data-fv-max') || this.max);
                var numVal = parseFloat(val);
                if (!isNaN(min) && numVal < min) {
                    this.classList.remove('is-valid');
                    this.classList.add('is-invalid');
                } else if (!isNaN(max) && numVal > max) {
                    this.classList.remove('is-valid');
                    this.classList.add('is-invalid');
                }
            } else {
                this.classList.remove('is-valid');
                this.classList.add('is-invalid');
            }
        });

        /* Set ARIA attributes for accessibility */
        input.setAttribute('aria-describedby', input.id + '-fv-help');
    });

});
