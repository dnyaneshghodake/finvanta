<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="MFA Management" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <c:if test="${not empty success}"><div class="alert alert-success alert-dismissible fade show"><c:out value="${success}" /><button type="button" class="btn-close" data-bs-dismiss="alert"></button></div></c:if>
    <c:if test="${not empty error}"><div class="alert alert-danger alert-dismissible fade show"><c:out value="${error}" /><button type="button" class="btn-close" data-bs-dismiss="alert"></button></div></c:if>
    <c:if test="${not empty info}"><div class="alert alert-info alert-dismissible fade show"><c:out value="${info}" /><button type="button" class="btn-close" data-bs-dismiss="alert"></button></div></c:if>

    <div class="fv-card">
        <div class="card-header">MFA Management (RBI IT Governance Direction 2023 &sect;8.4)</div>
        <div class="card-body">
            <p class="text-muted">Per RBI: MFA is mandatory for ADMIN users. Enable MFA, then enroll the user by generating a TOTP secret for their authenticator app. Enrollment is complete only after TOTP code verification.</p>
            <div class="table-responsive">
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr>
                        <th>Username</th>
                        <th>Full Name</th>
                        <th>Role</th>
                        <th>MFA Enabled</th>
                        <th>Enrolled</th>
                        <th>Enrolled Date</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="u" items="${users}">
                        <tr>
                            <td class="fw-bold"><c:out value="${u.username}" /></td>
                            <td><c:out value="${u.fullName}" /></td>
                            <td><span class="fv-badge fv-badge-${u.role == 'ADMIN' ? 'active' : 'closed'}"><c:out value="${u.role}" /></span></td>
                            <td>
                                <c:choose>
                                    <c:when test="${u.mfaEnabled}"><span class="fv-badge fv-badge-active">YES</span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-closed">NO</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td>
                                <c:choose>
                                    <c:when test="${u.mfaEnrolledDate != null}"><span class="fv-badge fv-badge-active">ENROLLED</span></c:when>
                                    <c:when test="${u.mfaEnabled && u.mfaEnrolledDate == null}"><span class="fv-badge fv-badge-pending">PENDING</span></c:when>
                                    <c:otherwise><span class="text-muted">--</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td><c:out value="${u.mfaEnrolledDate}" default="--" /></td>
                            <td>
                                <c:if test="${!u.mfaEnabled}">
                                    <form method="post" action="${pageContext.request.contextPath}/admin/mfa/enable" class="d-inline">
                                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                        <input type="hidden" name="username" value="${u.username}" />
                                        <button type="submit" class="btn btn-sm btn-outline-primary" title="Enable MFA"><i class="bi bi-shield-plus"></i> Enable</button>
                                    </form>
                                </c:if>
                                <c:if test="${u.mfaEnabled && u.mfaEnrolledDate == null}">
                                    <form method="post" action="${pageContext.request.contextPath}/admin/mfa/enroll" class="d-inline">
                                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                        <input type="hidden" name="username" value="${u.username}" />
                                        <button type="submit" class="btn btn-sm btn-outline-success" title="Generate TOTP Secret"><i class="bi bi-qr-code"></i> Enroll</button>
                                    </form>
                                </c:if>
                                <c:if test="${u.mfaEnabled && u.role != 'ADMIN'}">
                                    <button type="button" class="btn btn-sm btn-outline-danger" data-bs-toggle="modal" data-bs-target="#disableMfa_${u.username}" title="Disable MFA"><i class="bi bi-shield-x"></i></button>
                                    <!-- Disable MFA Modal -->
                                    <div class="modal fade" id="disableMfa_${u.username}" tabindex="-1">
                                        <div class="modal-dialog"><div class="modal-content">
                                            <div class="modal-header"><h5 class="modal-title">Disable MFA: <c:out value="${u.username}" /></h5><button type="button" class="btn-close" data-bs-dismiss="modal"></button></div>
                                            <form method="post" action="${pageContext.request.contextPath}/admin/mfa/disable">
                                                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                                <input type="hidden" name="username" value="${u.username}" />
                                                <div class="modal-body">
                                                    <p class="text-muted">Per RBI audit norms: reason is mandatory for MFA disable.</p>
                                                    <div class="mb-3"><label class="form-label">Reason *</label><textarea name="reason" class="form-control" rows="2" required></textarea></div>
                                                </div>
                                                <div class="modal-footer"><button type="button" class="btn btn-secondary" data-bs-dismiss="modal">Cancel</button><button type="submit" class="btn btn-danger">Disable MFA</button></div>
                                            </form>
                                        </div></div>
                                    </div>
                                </c:if>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty users}">
                        <tr><td colspan="7" class="text-center text-muted">No users found</td></tr>
                    </c:if>
                </tbody>
            </table>
            </div>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
