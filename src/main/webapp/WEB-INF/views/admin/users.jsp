<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="User Management" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <c:if test="${not empty success}"><div class="alert alert-success"><c:out value="${success}"/></div></c:if>
    <c:if test="${not empty error}"><div class="alert alert-danger"><c:out value="${error}"/></div></c:if>

    <!-- Create User Form -->
    <div class="fv-card mb-3">
        <div class="card-header"><i class="bi bi-person-plus"></i> Create New User</div>
        <div class="card-body">
            <form method="post" action="${pageContext.request.contextPath}/admin/users/create" class="fv-form">
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
                <div class="row mb-3">
                    <div class="col-md-2">
                        <label class="form-label">Username *</label>
                        <input type="text" name="username" class="form-control" required minlength="3" maxlength="100" placeholder="e.g., maker3"/>
                    </div>
                    <div class="col-md-2">
                        <label class="form-label">Password *</label>
                        <input type="password" name="password" class="form-control" required minlength="8" placeholder="Min 8 chars"/>
                    </div>
                    <div class="col-md-3">
                        <label class="form-label">Full Name *</label>
                        <input type="text" name="fullName" class="form-control" required maxlength="200" placeholder="e.g., Rajiv Menon"/>
                    </div>
                    <div class="col-md-2">
                        <label class="form-label">Email</label>
                        <input type="email" name="email" class="form-control" maxlength="200"/>
                    </div>
                    <div class="col-md-1">
                        <label class="form-label">Role *</label>
                        <select name="role" class="form-select" required>
                            <c:forEach var="r" items="${roles}">
                                <option value="${r}"><c:out value="${r}"/></option>
                            </c:forEach>
                        </select>
                    </div>
                    <div class="col-md-2">
                        <label class="form-label">Branch *</label>
                        <select name="branchId" class="form-select" required>
                            <c:forEach var="b" items="${branches}">
                                <option value="${b.id}"><c:out value="${b.branchCode}"/> - <c:out value="${b.branchName}"/></option>
                            </c:forEach>
                        </select>
                    </div>
                </div>
                <button type="submit" class="btn btn-fv-primary btn-sm" data-confirm="Create this user?"><i class="bi bi-person-plus"></i> Create User</button>
            </form>
        </div>
    </div>

    <!-- User List -->
    <div class="fv-card">
        <div class="card-header"><i class="bi bi-people"></i> All Users <span class="badge bg-secondary"><c:out value="${users.size()}"/></span></div>
        <div class="card-body">
            <div class="table-responsive">
            <table class="table fv-table fv-datatable table-sm">
                <thead><tr>
                    <th>Username</th><th>Full Name</th><th>Email</th><th>Role</th><th>Branch</th>
                    <th>Active</th><th>Locked</th><th>MFA</th><th>Pwd Expiry</th><th>Last Login</th><th>Actions</th>
                </tr></thead>
                <tbody>
                <c:forEach var="u" items="${users}">
                    <tr class="${!u.active ? 'table-secondary' : u.locked ? 'table-warning' : ''}">
                        <td class="fw-bold"><c:out value="${u.username}"/></td>
                        <td><c:out value="${u.fullName}"/></td>
                        <td><small><c:out value="${u.email}" default="--"/></small></td>
                        <td><span class="badge ${u.role == 'ADMIN' ? 'bg-danger' : u.role == 'CHECKER' ? 'bg-primary' : u.role == 'MAKER' ? 'bg-success' : 'bg-info'}"><c:out value="${u.role}"/></span></td>
                        <td><c:out value="${u.branch.branchCode}" default="--"/></td>
                        <td><c:choose><c:when test="${u.active}"><span class="fv-badge fv-badge-active">Active</span></c:when><c:otherwise><span class="fv-badge fv-badge-rejected">Inactive</span></c:otherwise></c:choose></td>
                        <td>
                            <c:if test="${u.locked}"><span class="fv-badge fv-badge-npa">LOCKED</span><br/><small class="text-muted">Attempts: <c:out value="${u.failedLoginAttempts}"/></small></c:if>
                            <c:if test="${!u.locked}">--</c:if>
                        </td>
                        <td>
                            <c:choose>
                                <c:when test="${u.mfaEnabled and not empty u.mfaEnrolledDate}"><span class="fv-badge fv-badge-approved">Enrolled</span></c:when>
                                <c:when test="${u.mfaEnabled}"><span class="fv-badge fv-badge-pending">Pending</span></c:when>
                                <c:otherwise><span class="text-muted">--</span></c:otherwise>
                            </c:choose>
                        </td>
                        <td>
                            <c:choose>
                                <c:when test="${u.passwordExpired}"><span class="fv-badge fv-badge-npa">EXPIRED</span></c:when>
                                <c:when test="${not empty u.passwordExpiryDate}"><c:out value="${u.passwordExpiryDate}"/></c:when>
                                <c:otherwise>--</c:otherwise>
                            </c:choose>
                        </td>
                        <td>
                            <c:choose>
                                <c:when test="${not empty u.lastLoginAt}"><small><c:out value="${u.lastLoginAt}"/><br/><c:out value="${u.lastLoginIp}" default=""/></small></c:when>
                                <c:otherwise><small class="text-muted">Never</small></c:otherwise>
                            </c:choose>
                        </td>
                        <td>
                            <form method="post" action="${pageContext.request.contextPath}/admin/users/toggle-active/${u.id}" class="d-inline">
                                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
                                <c:choose>
                                    <c:when test="${u.active}">
                                        <button type="submit" class="btn btn-sm btn-outline-warning" data-confirm="Deactivate this user?"><i class="bi bi-person-x"></i> Deactivate</button>
                                    </c:when>
                                    <c:otherwise>
                                        <button type="submit" class="btn btn-sm btn-outline-success" data-confirm="Activate this user?"><i class="bi bi-person-check"></i> Activate</button>
                                    </c:otherwise>
                                </c:choose>
                            </form>
                            <c:if test="${u.locked}">
                            <form method="post" action="${pageContext.request.contextPath}/admin/users/unlock/${u.id}" class="d-inline">
                                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
                                <button type="submit" class="btn btn-sm btn-outline-primary" data-confirm="Unlock this user?"><i class="bi bi-unlock"></i> Unlock</button>
                            </form>
                            </c:if>
                            <form method="post" action="${pageContext.request.contextPath}/admin/users/reset-password/${u.id}" class="d-inline">
                                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
                                <input type="hidden" name="newPassword" value="" id="pwd_${u.id}"/>
                                <button type="submit" class="btn btn-sm btn-outline-secondary"
                                    onclick="var p=prompt('New password (min 8 chars):'); if(!p||p.length<8){alert('Password must be at least 8 characters');return false;} document.getElementById('pwd_${u.id}').value=p; return confirm('Reset password for this user?');">
                                    <i class="bi bi-key"></i> Reset Pwd
                                </button>
                            </form>
                        </td>
                    </tr>
                </c:forEach>
                <c:if test="${empty users}"><tr><td colspan="11" class="text-center text-muted">No users found</td></tr></c:if>
                </tbody>
            </table>
            </div>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
