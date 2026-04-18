<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Standing Instruction Dashboard" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <ul class="fv-breadcrumb">
        <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
        <li class="active">SI Dashboard</li>
    </ul>

    <c:if test="${not empty success}"><div class="fv-alert alert alert-success"><c:out value="${success}"/></div></c:if>
    <c:if test="${not empty error}"><div class="fv-alert alert alert-danger"><c:out value="${error}"/></div></c:if>

    <!-- Summary Metrics -->
    <div class="row g-3 mb-3">
        <div class="col"><div class="fv-stat-card"><div class="stat-value"><c:out value="${totalActive}" /></div><div class="stat-label">Active SIs</div></div></div>
        <div class="col"><div class="fv-stat-card stat-warning"><div class="stat-value"><c:out value="${totalPaused}" /></div><div class="stat-label">Paused</div></div></div>
        <div class="col"><div class="fv-stat-card stat-danger"><div class="stat-value"><c:out value="${totalFailed}" /></div><div class="stat-label">Failed (Active)</div></div></div>
        <div class="col"><div class="fv-stat-card"><div class="stat-value"><c:out value="${totalPending}" /></div><div class="stat-label">Pending Approval</div></div></div>
    </div>

    <!-- Pending SIs Requiring Checker Approval (Maker-Checker) -->
    <c:if test="${not empty pendingSIs}">
    <div class="fv-card mb-3">
        <div class="card-header text-warning"><i class="bi bi-hourglass-split"></i> Pending Approval (Maker-Checker) <span class="badge bg-warning text-dark"><c:out value="${pendingSIs.size()}" /></span></div>
        <div class="card-body">
            <div class="table-responsive">
            <table class="table fv-table">
                <thead><tr>
                    <th>SI Ref</th><th>Type</th><th>Customer</th><th>Source CASA</th><th>Destination</th>
                    <th>Amount</th><th>Frequency</th><th>Maker</th><th>Created</th><th>Action</th>
                </tr></thead>
                <tbody>
                <c:forEach var="si" items="${pendingSIs}">
                    <tr>
                        <td class="font-monospace small"><c:out value="${si.siReference}" /></td>
                        <td><span class="badge bg-info"><c:out value="${si.destinationType}" /></span></td>
                        <td><c:out value="${si.customer.firstName}" /> <c:out value="${si.customer.lastName}" /></td>
                        <td><a href="${pageContext.request.contextPath}/deposit/view/${si.sourceAccountNumber}"><c:out value="${si.sourceAccountNumber}" /></a></td>
                        <td><c:out value="${si.destinationAccountNumber}" default="--" /></td>
                        <td><c:if test="${si.amount != null}"><fmt:formatNumber value="${si.amount}" type="currency" currencyCode="INR"/></c:if><c:if test="${si.amount == null}">Dynamic</c:if></td>
                        <td><c:out value="${si.frequency}" /></td>
                        <td><c:out value="${si.createdBy}" /></td>
                        <td><c:out value="${si.createdAt}" /></td>
                        <td>
                            <form method="post" action="${pageContext.request.contextPath}/loan/si/approve/${si.siReference}" class="d-inline">
                                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                <button type="submit" class="btn btn-sm btn-fv-success" data-confirm="Approve SI ${si.siReference}? It will become active immediately."><i class="bi bi-check-circle"></i> Approve</button>
                            </form>
                            <form method="post" action="${pageContext.request.contextPath}/loan/si/reject/${si.siReference}" class="d-inline">
                                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                <input type="hidden" name="reason" value="" class="fv-reason-field" />
                                <button type="button" class="btn btn-sm btn-outline-danger"
                                    data-fv-reason-prompt="Rejection reason (mandatory):"
                                    data-fv-reason-confirm="Reject SI ${si.siReference}?"
                                    onclick="fvPromptReason(this);">
                                    <i class="bi bi-x-circle"></i> Reject
                                </button>
                            </form>
                        </td>
                    </tr>
                </c:forEach>
                </tbody>
            </table>
            </div>
        </div>
    </div>
    </c:if>

    <!-- Active SIs by Type -->
    <c:if test="${not empty sisByType}">
    <div class="fv-card mb-3">
        <div class="card-header">Active SIs by Type</div>
        <div class="card-body">
            <div class="row">
                <c:forEach var="entry" items="${sisByType}">
                    <div class="col-auto">
                        <span class="badge ${entry[0] == 'LOAN_EMI' ? 'bg-primary' : 'bg-info'} fs-6 me-2">
                            <c:out value="${entry[0]}" />: <c:out value="${entry[1]}" />
                        </span>
                    </div>
                </c:forEach>
            </div>
        </div>
    </div>
    </c:if>

    <!-- 7-Day Execution Forecast -->
    <c:if test="${not empty executionForecast}">
    <div class="fv-card mb-3">
        <div class="card-header"><i class="bi bi-calendar-week"></i> Upcoming Execution Forecast (7 days)</div>
        <div class="card-body">
            <div class="row g-2">
                <c:forEach var="entry" items="${executionForecast}">
                    <div class="col">
                        <div class="text-center p-2 border rounded ${entry.value > 0 ? 'bg-light' : ''}">
                            <div class="small text-muted"><c:out value="${entry.key}" /></div>
                            <div class="fs-5 fw-bold ${entry.value > 0 ? 'text-primary' : 'text-muted'}"><c:out value="${entry.value}" /></div>
                            <div class="small">SIs due</div>
                        </div>
                    </div>
                </c:forEach>
            </div>
        </div>
    </div>
    </c:if>

    <!-- Failed SIs Requiring Attention -->
    <c:if test="${not empty failedSIs}">
    <div class="fv-card mb-3">
        <div class="card-header text-danger"><i class="bi bi-exclamation-triangle"></i> Failed SIs Requiring Attention <span class="badge bg-danger"><c:out value="${failedSIs.size()}" /></span></div>
        <div class="card-body">
            <div class="table-responsive">
            <table class="table fv-table">
                <thead><tr>
                    <th>SI Ref</th><th>Type</th><th>Source CASA</th><th>Loan</th>
                    <th>Last Status</th><th>Failure Reason</th><th>Retries</th><th>Last Exec</th>
                </tr></thead>
                <tbody>
                <c:forEach var="si" items="${failedSIs}">
                    <tr>
                        <td class="font-monospace small"><c:out value="${si.siReference}" /></td>
                        <td><span class="badge bg-primary"><c:out value="${si.destinationType}" /></span></td>
                        <td><a href="${pageContext.request.contextPath}/deposit/view/${si.sourceAccountNumber}"><c:out value="${si.sourceAccountNumber}" /></a></td>
                        <td><c:if test="${not empty si.loanAccountNumber}"><a href="${pageContext.request.contextPath}/loan/account/${si.loanAccountNumber}"><c:out value="${si.loanAccountNumber}" /></a></c:if></td>
                        <td><span class="fv-badge fv-badge-npa"><c:out value="${si.lastExecutionStatus}" /></span></td>
                        <td class="small text-danger"><c:out value="${si.lastFailureReason}" /></td>
                        <td><c:out value="${si.retriesDone}" />/<c:out value="${si.maxRetries}" /></td>
                        <td><c:out value="${si.lastExecutionDate}" /></td>
                    </tr>
                </c:forEach>
                </tbody>
            </table>
            </div>
        </div>
    </div>
    </c:if>

    <!-- All Standing Instructions -->
    <div class="fv-card">
        <div class="card-header"><i class="bi bi-arrow-repeat"></i> All Standing Instructions <span class="badge bg-secondary"><c:out value="${allSIs.size()}" /></span></div>
        <div class="card-body">
            <div class="table-responsive">
            <table class="table fv-table fv-datatable table-sm">
                <thead><tr>
                    <th>SI Ref</th><th>Type</th><th>Customer</th><th>Source CASA</th><th>Loan</th>
                    <th>Frequency</th><th>Exec Day</th><th>Next Exec</th>
                    <th>Last Status</th><th>Execs</th><th>Fails</th><th>Status</th>
                </tr></thead>
                <tbody>
                <c:forEach var="si" items="${allSIs}">
                    <tr>
                        <td class="font-monospace small"><c:out value="${si.siReference}" /></td>
                        <td><span class="badge ${si.destinationType == 'LOAN_EMI' ? 'bg-primary' : 'bg-info'}"><c:out value="${si.destinationType}" /></span></td>
                        <td><c:out value="${si.customer.firstName}" /> <c:out value="${si.customer.lastName}" /></td>
                        <td><a href="${pageContext.request.contextPath}/deposit/view/${si.sourceAccountNumber}"><c:out value="${si.sourceAccountNumber}" /></a></td>
                        <td><c:if test="${not empty si.loanAccountNumber}"><a href="${pageContext.request.contextPath}/loan/account/${si.loanAccountNumber}"><c:out value="${si.loanAccountNumber}" /></a></c:if><c:if test="${empty si.loanAccountNumber}">--</c:if></td>
                        <td><c:out value="${si.frequency}" /></td>
                        <td><c:out value="${si.executionDay}" /></td>
                        <td><c:out value="${si.nextExecutionDate}" default="--" /></td>
                        <td>
                            <c:choose>
                                <c:when test="${si.lastExecutionStatus == 'SUCCESS'}"><span class="fv-badge fv-badge-active">SUCCESS</span></c:when>
                                <c:when test="${si.lastExecutionStatus != null && si.lastExecutionStatus.startsWith('FAILED')}"><span class="fv-badge fv-badge-npa"><c:out value="${si.lastExecutionStatus}" /></span></c:when>
                                <c:otherwise><span class="text-muted">--</span></c:otherwise>
                            </c:choose>
                        </td>
                        <td><c:out value="${si.totalExecutions}" /></td>
                        <td><c:if test="${si.totalFailures > 0}"><span class="text-danger fw-bold"><c:out value="${si.totalFailures}" /></span></c:if><c:if test="${si.totalFailures == 0}">0</c:if></td>
                        <td>
                            <c:choose>
                                <c:when test="${si.status == 'ACTIVE'}"><span class="fv-badge fv-badge-active">ACTIVE</span></c:when>
                                <c:when test="${si.status == 'PAUSED'}"><span class="fv-badge fv-badge-pending">PAUSED</span></c:when>
                                <c:when test="${si.status == 'EXPIRED'}"><span class="fv-badge fv-badge-closed">EXPIRED</span></c:when>
                                <c:when test="${si.status == 'CANCELLED'}"><span class="fv-badge fv-badge-rejected">CANCELLED</span></c:when>
                                <c:otherwise><span class="fv-badge fv-badge-pending"><c:out value="${si.status}" /></span></c:otherwise>
                            </c:choose>
                        </td>
                    </tr>
                </c:forEach>
                <c:if test="${empty allSIs}"><tr><td colspan="12" class="text-center text-muted">No standing instructions found</td></tr></c:if>
                </tbody>
            </table>
            </div>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
