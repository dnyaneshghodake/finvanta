<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Finvanta CBS - Change Password</title>
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/bootstrap.min.css">
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/bootstrap-icons.css">
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/finvanta-theme.css">
    <style>
        .pwd-container { max-width: 480px; margin: 60px auto; }
        .pwd-card { background: #1e293b; border-radius: 12px; padding: 36px; box-shadow: 0 8px 32px rgba(0,0,0,0.3); }
        .pwd-title { color: #60a5fa; font-size: 1.4rem; font-weight: 600; margin-bottom: 8px; }
        .pwd-subtitle { color: #94a3b8; font-size: 0.85rem; margin-bottom: 20px; }
        .pwd-brand { text-align: center; margin-bottom: 24px; }
        .pwd-brand h1 { color: #60a5fa; font-size: 1.8rem; font-weight: 700; margin: 0; }
        .pwd-brand small { color: #64748b; font-size: 0.75rem; }
        .form-control { background: #0f172a; color: #e2e8f0; border: 1px solid #334155; }
        .form-control:focus { border-color: #60a5fa; box-shadow: 0 0 0 3px rgba(96,165,250,0.2); color: #e2e8f0; background: #0f172a; }
        .form-label { color: #94a3b8; font-size: 0.85rem; }
        .policy-list { color: #64748b; font-size: 0.75rem; margin: 0; padding-left: 16px; }
        .policy-list li { margin-bottom: 2px; }
        /* CBS: Dark-theme alert overrides for password change page */
        .pwd-card .alert-warning { background: #422006; color: #fbbf24; border-color: #854d0e; }
        .pwd-card .alert-warning strong { color: #fde68a; }
        .pwd-card .alert-danger { background: #450a0a; color: #fca5a5; border-color: #991b1b; }
        .pwd-card .alert-success { background: #052e16; color: #86efac; border-color: #166534; }
    </style>
</head>
<body style="background:#0f172a;min-height:100vh;">
    <div class="pwd-container">
        <div class="pwd-brand">
            <h1>FINVANTA</h1>
            <small>Core Banking System</small>
        </div>

        <div class="pwd-card">
            <div class="pwd-title"><i class="bi bi-key"></i> Change Password</div>

            <c:if test="${expired}">
                <div class="alert alert-warning" style="font-size:0.85rem;">
                    <i class="bi bi-exclamation-triangle"></i> <strong>Your password has expired.</strong>
                    Per RBI IT Governance policy, passwords must be changed every 90 days.
                    Please set a new password to continue.
                </div>
            </c:if>

            <c:if test="${not empty success}">
                <div class="alert alert-success" style="font-size:0.85rem;"><c:out value="${success}" /></div>
            </c:if>
            <c:if test="${not empty error}">
                <div class="alert alert-danger" style="font-size:0.85rem;"><c:out value="${error}" /></div>
            </c:if>

            <div class="pwd-subtitle">
                Logged in as: <strong><c:out value="${username}" /></strong>
            </div>

            <form method="post" action="${pageContext.request.contextPath}/password/change" id="pwdForm">
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                <div class="mb-3">
                    <label class="form-label">Current Password *</label>
                    <input type="password" name="currentPassword" class="form-control" required autocomplete="current-password" />
                </div>
                <div class="mb-3">
                    <label class="form-label">New Password *</label>
                    <input type="password" name="newPassword" id="newPwd" class="form-control" required minlength="8" autocomplete="new-password" />
                </div>
                <div class="mb-3">
                    <label class="form-label">Confirm New Password *</label>
                    <input type="password" name="confirmPassword" id="confirmPwd" class="form-control" required minlength="8" autocomplete="new-password" />
                    <div id="matchHint" style="font-size:0.75rem;min-height:18px;margin-top:4px;"></div>
                </div>

                <div class="mb-3">
                    <p class="form-label mb-1">Password Policy (RBI IT Governance):</p>
                    <ul class="policy-list">
                        <li>Minimum 8 characters</li>
                        <li>At least 1 uppercase letter (A-Z)</li>
                        <li>At least 1 lowercase letter (a-z)</li>
                        <li>At least 1 digit (0-9)</li>
                        <li>At least 1 special character (@$!%*?&#^()-_=+)</li>
                        <li>Cannot reuse last 3 passwords</li>
                        <li>Expires every 90 days</li>
                    </ul>
                </div>

                <button type="submit" id="submitBtn" class="btn btn-primary w-100" style="padding:10px;" disabled>
                    <i class="bi bi-check-circle"></i> Change Password
                </button>
            </form>

            <c:if test="${!expired}">
                <div class="text-center mt-3">
                    <a href="${pageContext.request.contextPath}/dashboard" style="color:#94a3b8;font-size:0.8rem;text-decoration:none;">
                        <i class="bi bi-arrow-left"></i> Back to Dashboard
                    </a>
                </div>
            </c:if>
            <c:if test="${expired}">
                <div class="text-center mt-3">
                    <form method="post" action="${pageContext.request.contextPath}/logout" class="d-inline">
                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                        <button type="submit" class="btn btn-link" style="color:#94a3b8;font-size:0.8rem;text-decoration:none;">
                            <i class="bi bi-box-arrow-left"></i> Logout
                        </button>
                    </form>
                </div>
            </c:if>
        </div>
    </div>

    <script src="${pageContext.request.contextPath}/js/bootstrap.bundle.min.js"></script>
    <script>
        (function() {
            var newPwd = document.getElementById('newPwd');
            var confirmPwd = document.getElementById('confirmPwd');
            var hint = document.getElementById('matchHint');
            var btn = document.getElementById('submitBtn');

            function validate() {
                var np = newPwd.value;
                var cp = confirmPwd.value;
                if (np.length === 0 && cp.length === 0) {
                    hint.innerHTML = '';
                    btn.disabled = true;
                    return;
                }
                if (np.length < 8) {
                    hint.innerHTML = '<span style="color:#f59e0b;">Minimum 8 characters required</span>';
                    btn.disabled = true;
                    return;
                }
                if (cp.length > 0 && np !== cp) {
                    hint.innerHTML = '<span style="color:#ef4444;">Passwords do not match</span>';
                    btn.disabled = true;
                    return;
                }
                if (cp.length > 0 && np === cp) {
                    hint.innerHTML = '<span style="color:#4ade80;">Passwords match</span>';
                    btn.disabled = false;
                    return;
                }
                hint.innerHTML = '';
                btn.disabled = true;
            }

            newPwd.addEventListener('input', validate);
            confirmPwd.addEventListener('input', validate);
        })();
    </script>
</body>
</html>
