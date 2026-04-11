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
                        <!-- CBS: QR code generated server-side as Base64 PNG data URI.
                             Per Finacle/Temenos: no external CDN/JS dependency for air-gapped networks.
                             Falls back to manual secret entry if QR generation fails. -->
                        <c:choose>
                            <c:when test="${not empty qrCodeDataUri}">
                                <img src="${qrCodeDataUri}" alt="Scan this QR code with your authenticator app"
                                     style="image-rendering:pixelated;width:280px;height:280px;" />
                            </c:when>
                            <c:otherwise>
                                <div class="alert alert-warning mb-0" style="font-size:0.85rem;">
                                    <i class="bi bi-exclamation-triangle"></i> QR code generation failed.
                                    Please enter the secret key manually in your authenticator app (see below).
                                </div>
                            </c:otherwise>
                        </c:choose>
                    </div>
                    <p class="text-muted small"><i class="bi bi-keyboard"></i> Can't scan? Enter this secret manually in your authenticator app:</p>
                    <div class="input-group mb-3">
                        <span class="input-group-text"><i class="bi bi-key"></i></span>
                        <input type="text" class="form-control font-monospace" value="<c:out value='${secret}' />" readonly id="secretField" />
                        <button class="btn btn-outline-secondary" type="button" onclick="navigator.clipboard.writeText(document.getElementById('secretField').value)" title="Copy to clipboard"><i class="bi bi-clipboard"></i> Copy</button>
                    </div>
                </div>
                <div class="col-md-6">
                    <h5>Step 2: Verify Code</h5>
                    <p class="text-muted">Enter the 6-digit code from your authenticator app to complete enrollment:</p>
                    <form method="post" action="${pageContext.request.contextPath}/admin/mfa/verify">
                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                        <input type="hidden" name="username" value="<c:out value='${username}' />" />
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

<!-- CBS: QR code is rendered server-side via QrCodeGenerator (pure Java, zero deps).
     No client-side JavaScript QR generation needed — works on air-gapped bank networks.
     The otpauth:// URI is NOT exposed to the browser (only the rendered PNG image).
     Manual secret key is available as fallback for environments without camera access. -->

<%@ include file="../layout/footer.jsp" %>
