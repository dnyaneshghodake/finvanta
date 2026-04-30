<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Pending Till Approvals" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<%-- CBS Supervisor Queue for PENDING_OPEN and PENDING_CLOSE tills.
     Per RBI Internal Controls / dual-control: only CHECKER / ADMIN roles
     reach this screen. The service layer enforces maker ≠ checker on each
     individual approve/reject action. --%>

<div class="fv-main">
<ul class="fv-breadcrumb">
    <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
    <li class="active">Teller / Pending Till Approvals</li>
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
        <i class="bi bi-check2-square"></i> Pending Till Approvals
        <span class="badge bg-secondary ms-2"><c:out value="${pendingTills.size()}"/></span>
    </div>
    <div class="card-body">
        <c:choose>
            <c:when test="${empty pendingTills}">
                <p class="text-muted mb-0">No tills awaiting approval at your branch today.</p>
            </c:when>
            <c:otherwise>
                <div class="table-responsive">
                    <table class="table table-sm table-bordered align-middle">
                        <thead class="table-light">
                            <tr>
                                <th>Till ID</th>
                                <th>Teller</th>
                                <th>Branch</th>
                                <th>Status</th>
                                <th class="text-end">Opening Bal</th>
                                <th class="text-end">Current / Counted</th>
                                <th class="text-end">Variance</th>
                                <th>Requested</th>
                                <th>Action</th>
                            </tr>
                        </thead>
                        <tbody>
                            <c:forEach var="t" items="${pendingTills}">
                                <tr>
                                    <td><c:out value="${t.id}"/></td>
                                    <td><c:out value="${t.tellerUserId}"/></td>
                                    <td><c:out value="${t.branchCode}"/></td>
                                    <td>
                                        <c:choose>
                                            <c:when test="${t.status == 'PENDING_OPEN'}">
                                                <span class="badge bg-warning text-dark">PENDING_OPEN</span>
                                            </c:when>
                                            <c:when test="${t.status == 'PENDING_CLOSE'}">
                                                <span class="badge bg-info text-dark">PENDING_CLOSE</span>
                                            </c:when>
                                            <c:otherwise>
                                                <span class="badge bg-secondary"><c:out value="${t.status}"/></span>
                                            </c:otherwise>
                                        </c:choose>
                                    </td>
                                    <td class="text-end">
                                        <fmt:formatNumber value="${t.openingBalance}" type="currency" currencyCode="INR"/>
                                    </td>
                                    <td class="text-end">
                                        <c:choose>
                                            <c:when test="${t.status == 'PENDING_CLOSE' and t.countedBalance != null}">
                                                <fmt:formatNumber value="${t.countedBalance}" type="currency" currencyCode="INR"/>
                                                <small class="text-muted d-block">
                                                    system: <fmt:formatNumber value="${t.currentBalance}" type="currency" currencyCode="INR"/>
                                                </small>
                                            </c:when>
                                            <c:otherwise>
                                                <fmt:formatNumber value="${t.currentBalance}" type="currency" currencyCode="INR"/>
                                            </c:otherwise>
                                        </c:choose>
                                    </td>
                                    <td class="text-end">
                                        <c:choose>
                                            <c:when test="${t.varianceAmount == null}">--</c:when>
                                            <c:when test="${t.varianceAmount.signum() == 0}">
                                                <span class="badge bg-success">zero</span>
                                            </c:when>
                                            <c:when test="${t.varianceAmount.signum() > 0}">
                                                <span class="badge bg-warning text-dark">+<fmt:formatNumber value="${t.varianceAmount}" type="currency" currencyCode="INR"/></span>
                                            </c:when>
                                            <c:otherwise>
                                                <span class="badge bg-danger"><fmt:formatNumber value="${t.varianceAmount}" type="currency" currencyCode="INR"/></span>
                                            </c:otherwise>
                                        </c:choose>
                                    </td>
                                    <td class="small text-muted">
                                        <c:choose>
                                            <c:when test="${t.status == 'PENDING_OPEN'}">
                                                <c:out value="${t.businessDate}"/>
                                            </c:when>
                                            <c:otherwise>
                                                <c:out value="${t.openedAt}"/>
                                            </c:otherwise>
                                        </c:choose>
                                    </td>
                                    <td>
                                        <%-- Approve form: single-button POST. --%>
                                        <c:choose>
                                            <c:when test="${t.status == 'PENDING_OPEN'}">
                                                <form method="post" action="${pageContext.request.contextPath}/teller/till/${t.id}/approve" class="d-inline">
                                                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
                                                    <button type="submit" class="btn btn-sm btn-fv-primary"
                                                            data-confirm="Approve till ${t.id} for ${t.tellerUserId}? This will transition PENDING_OPEN to OPEN.">
                                                        <i class="bi bi-check-lg"></i> Approve
                                                    </button>
                                                </form>
                                                <%-- Reject: inline form with mandatory reason. --%>
                                                <form method="post" action="${pageContext.request.contextPath}/teller/till/${t.id}/reject"
                                                      class="d-inline ms-1 fv-reject-form" data-action="REJECT_OPEN">
                                                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
                                                    <input type="hidden" name="action" value="REJECT_OPEN"/>
                                                    <input type="hidden" name="reason" value=""/>
                                                    <button type="button" class="btn btn-sm btn-outline-danger fv-reject-btn"
                                                            data-prompt="Reject till ${t.id} (PENDING_OPEN)? The till will transition to CLOSED. Enter reason:">
                                                        <i class="bi bi-x-lg"></i> Reject
                                                    </button>
                                                </form>
                                            </c:when>
                                            <c:when test="${t.status == 'PENDING_CLOSE'}">
                                                <form method="post" action="${pageContext.request.contextPath}/teller/till/${t.id}/approve-close" class="d-inline">
                                                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
                                                    <button type="submit" class="btn btn-sm btn-fv-primary"
                                                            data-confirm="Approve close of till ${t.id}? This will transition PENDING_CLOSE to CLOSED.">
                                                        <i class="bi bi-check-lg"></i> Approve Close
                                                    </button>
                                                </form>
                                                <form method="post" action="${pageContext.request.contextPath}/teller/till/${t.id}/reject"
                                                      class="d-inline ms-1 fv-reject-form" data-action="REJECT_CLOSE">
                                                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}"/>
                                                    <input type="hidden" name="action" value="REJECT_CLOSE"/>
                                                    <input type="hidden" name="reason" value=""/>
                                                    <button type="button" class="btn btn-sm btn-outline-danger fv-reject-btn"
                                                            data-prompt="Reject close of till ${t.id} (PENDING_CLOSE)? The till will return to OPEN for recount. Enter reason:">
                                                        <i class="bi bi-x-lg"></i> Reject
                                                    </button>
                                                </form>
                                            </c:when>
                                        </c:choose>
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

<%-- Reject workflow: prompt the supervisor for a reason, write it into the
     hidden input, and submit. Per RBI: rejection reason is mandatory. The
     service-layer enforces non-blank reason, but capturing it client-side
     avoids a round-trip for the common case of an empty reason. --%>
<script>
(function () {
    document.querySelectorAll(".fv-reject-btn").forEach(function (btn) {
        btn.addEventListener("click", function (e) {
            var form = btn.closest("form");
            if (!form) return;
            var promptMsg = btn.getAttribute("data-prompt") || "Enter rejection reason:";
            var reason = window.prompt(promptMsg);
            if (reason === null) return; // cancelled
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
