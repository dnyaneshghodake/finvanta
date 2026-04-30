<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Pending Vault Movements" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<%-- CBS Vault Custodian Queue: PENDING vault BUY / SELL movements awaiting
     dual-control sign-off. Per RBI Internal Controls: only CHECKER / ADMIN
     reach this screen. The service layer enforces maker ≠ checker on each
     individual approve action. --%>

<div class="fv-main">
<ul class="fv-breadcrumb">
    <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
    <li><a href="${pageContext.request.contextPath}/teller/vault">Vault</a></li>
    <li class="active">Pending Movements</li>
</ul>

<c:if test="${not empty success}"><div class="fv-alert alert alert-success"><c:out value="${success}"/></div></c:if>
<c:if test="${not empty error}">
    <div class="fv-alert alert alert-danger">
        <c:if test="${not empty errorCode}"><strong>[<c:out value="${errorCode}"/>]</strong> </c:if>
        <c:out value="${error}"/>
    </div>
</c:if>

<div class="fv-card">
    <div class="card-header">
        <i class="bi bi-arrow-left-right"></i> Pending Vault Movements
        <span class="badge bg-secondary ms-2"><c:out value="${pendingMovements.size()}"/></span>
    </div>
    <div class="card-body">
        <c:choose>
            <c:when test="${empty pendingMovements}">
                <p class="text-muted mb-0">No vault movements awaiting approval today.</p>
            </c:when>
            <c:otherwise>
                <div class="table-responsive">
                    <table class="table table-sm table-bordered align-middle">
                        <thead class="table-light">
                            <tr>
                                <th>Movement Ref</th>
                                <th>Type</th>
                                <th>Branch</th>
                                <th>Till</th>
                                <th>Vault</th>
                                <th class="text-end">Amount</th>
                                <th>Requested By</th>
                                <th>Requested At</th>
                                <th>Action</th>
                            </tr>
                        </thead>
                        <tbody>
                            <c:forEach var="m" items="${pendingMovements}">
                                <tr>
                                    <td><code><c:out value="${m.movementRef}"/></code></td>
                                    <td>
                                        <c:choose>
                                            <c:when test="${m.movementType == 'BUY'}">
                                                <span class="badge bg-info text-dark">BUY (Vault &rarr; Till)</span>
                                            </c:when>
                                            <c:otherwise>
                                                <span class="badge bg-warning text-dark">SELL (Till &rarr; Vault)</span>
                                            </c:otherwise>
                                        </c:choose>
                                    </td>
                                    <td><c:out value="${m.branchCode}"/></td>
                                    <td><c:out value="${m.tillId}"/></td>
                                    <td><c:out value="${m.vaultId}"/></td>
                                    <td class="text-end">
                                        <strong><fmt:formatNumber value="${m.amount}" type="currency" currencyCode="INR"/></strong>
                                    </td>
                                    <td><c:out value="${m.requestedBy}"/></td>
                                    <td class="small text-muted"><c:out value="${m.requestedAt}"/></td>
                                    <td>
                                        <form method="post" action="${pageContext.request.contextPath}/teller/vault/movement/${m.id}/approve" class="d-inline">
                                            <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
                                            <button type="submit" class="btn btn-sm btn-fv-primary"
                                                    data-confirm="Approve ${m.movementType} of INR ${m.amount}? Balances will move atomically.">
                                                <i class="bi bi-check-lg"></i> Approve
                                            </button>
                                        </form>
                                        <form method="post" action="${pageContext.request.contextPath}/teller/vault/movement/${m.id}/reject"
                                              class="d-inline ms-1 fv-vault-reject-form">
                                            <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
                                            <input type="hidden" name="reason" value=""/>
                                            <button type="button" class="btn btn-sm btn-outline-danger fv-vault-reject-btn"
                                                    data-prompt="Reject ${m.movementType} ${m.movementRef}? Enter reason:">
                                                <i class="bi bi-x-lg"></i> Reject
                                            </button>
                                        </form>
                                    </td>
                                </tr>
                            </c:forEach>
                        </tbody>
                    </table>
                </div>
            </c:otherwise>
        </c:choose>
    </div>
</div>
</div>

<script>
(function () {
    document.querySelectorAll(".fv-vault-reject-btn").forEach(function (btn) {
        btn.addEventListener("click", function () {
            var form = btn.closest("form");
            if (!form) return;
            var promptMsg = btn.getAttribute("data-prompt") || "Enter rejection reason:";
            var reason = window.prompt(promptMsg);
            if (reason === null) return;
            reason = reason.trim();
            if (reason.length === 0) {
                alert("Rejection reason is mandatory per RBI audit norms.");
                return;
            }
            var reasonInput = form.querySelector("input[name=reason]");
            if (reasonInput) reasonInput.value = reason;
            form.submit();
        });
    });
})();
</script>

<%@ include file="../layout/footer.jsp" %>
