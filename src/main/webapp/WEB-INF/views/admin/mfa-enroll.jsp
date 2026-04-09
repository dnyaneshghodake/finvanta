<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="MFA Enrollment" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <c:if test="${not empty error}"><div class="alert alert-danger alert-dismissible fade show"><c:out value="${error}" /><button type="button" class="btn-close" data-bs-dismiss="alert"></button></div></c:if>

    <div class="fv-card">
        <div class="card-header">MFA Enrollment: <c:out value="${username}" /></div>
        <div class="card-body">
            <div class="row">
                <div class="col-md-6">
                    <h5>Step 1: Scan QR Code</h5>
                    <p class="text-muted">Open Google Authenticator, Microsoft Authenticator, or Authy and scan this QR code:</p>
                    <div class="text-center p-3 mb-3" style="background:#fff;border-radius:8px;border:1px solid #dee2e6;">
                        <!-- QR code rendered via JavaScript using otpauth URI -->
                        <div id="qrcode" style="display:inline-block;"></div>
                    </div>
                    <p class="text-muted small">Can't scan? Enter this secret manually in your authenticator app:</p>
                    <div class="input-group mb-3">
                        <span class="input-group-text"><i class="bi bi-key"></i></span>
                        <input type="text" class="form-control font-monospace" value="${secret}" readonly id="secretField" />
                        <button class="btn btn-outline-secondary" type="button" onclick="navigator.clipboard.writeText(document.getElementById('secretField').value)"><i class="bi bi-clipboard"></i></button>
                    </div>
                </div>
                <div class="col-md-6">
                    <h5>Step 2: Verify Code</h5>
                    <p class="text-muted">Enter the 6-digit code from your authenticator app to complete enrollment:</p>
                    <form method="post" action="${pageContext.request.contextPath}/admin/mfa/verify">
                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                        <input type="hidden" name="username" value="${username}" />
                        <div class="mb-3">
                            <label class="form-label">TOTP Code (6 digits)</label>
                            <input type="text" name="totpCode" class="form-control form-control-lg text-center font-monospace"
                                   maxlength="6" pattern="[0-9]{6}" required autocomplete="off"
                                   placeholder="000000" style="letter-spacing:0.5em;font-size:1.5rem;" />
                        </div>
                        <button type="submit" class="btn btn-primary"><i class="bi bi-check-circle"></i> Verify &amp; Activate</button>
                        <a href="${pageContext.request.contextPath}/admin/mfa" class="btn btn-secondary ms-2">Cancel</a>
                    </form>
                </div>
            </div>
        </div>
    </div>
</div>

<!-- CBS Security: otpauth URI passed via data attribute to prevent XSS.
     Per OWASP: never interpolate server-side values directly into JavaScript string literals.
     Using data-uri with <c:out> HTML-escapes the value, then JavaScript reads it safely. -->
<div id="otpData" data-uri="<c:out value='${otpAuthUri}' />" style="display:none;"></div>
<script>
    // Simple QR code rendering using a canvas-based approach
    // In production, use a proper QR library like qrcode.js
    (function() {
        var dataEl = document.getElementById('otpData');
        var uri = dataEl ? dataEl.getAttribute('data-uri') : '';
        var container = document.getElementById('qrcode');
        if (uri && container) {
            // Fallback: show the URI as a link if QR JS is not available
            var code = document.createElement('code');
            code.style.cssText = 'word-break:break-all;font-size:0.75rem;';
            code.textContent = uri;
            var p = document.createElement('p');
            p.className = 'text-muted small';
            p.textContent = 'otpauth:// URI:';
            container.appendChild(p);
            container.appendChild(code);
        }
    })();
</script>

<%@ include file="../layout/footer.jsp" %>
