<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Finvanta CBS - MFA Verification</title>
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/bootstrap.min.css">
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/bootstrap-icons.css">
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/finvanta-theme.css">
    <style>
        .mfa-container { max-width: 440px; margin: 80px auto; }
        .mfa-card { background: #1e293b; border-radius: 12px; padding: 40px; box-shadow: 0 8px 32px rgba(0,0,0,0.3); }
        .mfa-title { color: #60a5fa; font-size: 1.5rem; font-weight: 600; margin-bottom: 8px; }
        .mfa-subtitle { color: #94a3b8; font-size: 0.9rem; margin-bottom: 24px; }
        .totp-input { font-size: 2rem; letter-spacing: 0.5em; text-align: center; font-family: monospace;
                      background: #0f172a; color: #e2e8f0; border: 2px solid #334155; border-radius: 8px; padding: 12px; }
        .totp-input:focus { border-color: #60a5fa; box-shadow: 0 0 0 3px rgba(96,165,250,0.2); outline: none; }
        .mfa-brand { text-align: center; margin-bottom: 32px; }
        .mfa-brand h1 { color: #60a5fa; font-size: 1.8rem; font-weight: 700; margin: 0; }
        .mfa-brand small { color: #64748b; font-size: 0.75rem; }
    </style>
</head>
<body style="background:#0f172a;min-height:100vh;">
    <div class="mfa-container">
        <div class="mfa-brand">
            <h1>FINVANTA</h1>
            <small>Core Banking System</small>
        </div>

        <div class="mfa-card">
            <div class="mfa-title"><i class="bi bi-shield-lock"></i> Two-Factor Authentication</div>
            <div class="mfa-subtitle">
                Enter the 6-digit code from your authenticator app to complete login.
            </div>

            <c:if test="${not empty error}">
                <div class="alert alert-danger alert-dismissible fade show" style="font-size:0.85rem;">
                    <c:out value="${error}" />
                    <button type="button" class="btn-close btn-close-white" data-bs-dismiss="alert"></button>
                </div>
            </c:if>

            <form method="post" action="${pageContext.request.contextPath}/mfa/verify" id="mfaForm">
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                <div class="mb-2">
                    <input type="text" name="totpCode" id="totpInput" class="form-control totp-input"
                           inputmode="numeric" maxlength="6" pattern="[0-9]{6}" required autocomplete="one-time-code"
                           placeholder="------" autofocus />
                </div>
                <div id="totpHint" class="mb-3" style="text-align:center;font-size:0.8rem;min-height:20px;"></div>
                <button type="submit" id="verifyBtn" class="btn btn-primary w-100" style="padding:12px;font-size:1rem;" disabled>
                    <i class="bi bi-check-circle"></i> Verify
                </button>
            </form>
            <script>
                (function() {
                    var input = document.getElementById('totpInput');
                    var btn = document.getElementById('verifyBtn');
                    var hint = document.getElementById('totpHint');

                    // Block non-numeric characters at keystroke level
                    input.addEventListener('keypress', function(e) {
                        if (e.key < '0' || e.key > '9') {
                            e.preventDefault();
                        }
                    });

                    // Strip non-digits on paste/input (handles paste, autofill, drag-drop)
                    input.addEventListener('input', function() {
                        var cleaned = this.value.replace(/[^0-9]/g, '').substring(0, 6);
                        if (cleaned !== this.value) {
                            this.value = cleaned;
                        }
                        var len = cleaned.length;
                        if (len === 0) {
                            hint.innerHTML = '<span style="color:#64748b;">Enter 6 digits from your authenticator app</span>';
                            btn.disabled = true;
                        } else if (len < 6) {
                            hint.innerHTML = '<span style="color:#f59e0b;">' + len + ' of 6 digits entered</span>';
                            btn.disabled = true;
                        } else {
                            hint.innerHTML = '<span style="color:#4ade80;">&#10003; Ready to verify</span>';
                            btn.disabled = false;
                        }
                    });

                    // Prevent form submission if not exactly 6 digits
                    document.getElementById('mfaForm').addEventListener('submit', function(e) {
                        var val = input.value.replace(/[^0-9]/g, '');
                        if (val.length !== 6) {
                            e.preventDefault();
                            hint.innerHTML = '<span style="color:#ef4444;">Please enter exactly 6 digits</span>';
                            input.focus();
                            return false;
                        }
                    });

                    // Initialize hint
                    hint.innerHTML = '<span style="color:#64748b;">Enter 6 digits from your authenticator app</span>';
                })();
            </script>

            <div class="text-center mt-3">
                <form method="post" action="${pageContext.request.contextPath}/logout" class="d-inline">
                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                    <button type="submit" class="btn btn-link" style="color:#94a3b8;font-size:0.8rem;text-decoration:none;">
                        <i class="bi bi-box-arrow-left"></i> Cancel &amp; Logout
                    </button>
                </form>
            </div>

            <div class="text-center mt-2" style="color:#475569;font-size:0.75rem;">
                Logged in as: <strong><c:out value="${username}" /></strong>
                <c:if test="${not empty remainingAttempts && remainingAttempts < 5}">
                    <br/><span style="color:#f59e0b;"><i class="bi bi-exclamation-triangle"></i> <c:out value="${remainingAttempts}" /> attempt(s) remaining</span>
                </c:if>
            </div>
        </div>
    </div>

    <script src="${pageContext.request.contextPath}/js/bootstrap.bundle.min.js"></script>
</body>
</html>
