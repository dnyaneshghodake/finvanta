<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="CASA Account Pipeline" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <ul class="fv-breadcrumb">
        <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
        <li><a href="${pageContext.request.contextPath}/deposit/accounts">CASA Accounts</a></li>
        <li class="active">Account Pipeline</li>
    </ul>

    <c:if test="${not empty success}">
        <div class="fv-alert alert alert-success"><c:out value="${success}" /></div>
    </c:if>
    <c:if test="${not empty error}">
        <div class="fv-alert alert alert-danger"><c:out value="${error}" /></div>
    </c:if>

    <!-- Stage 1: Pending Activation (Maker submitted → Checker to activate) -->
    <div class="fv-card">
        <div class="card-header">
            <i class="bi bi-hourglass-split text-warning"></i> Pending Activation (Awaiting Checker Approval)
            <span class="badge bg-warning text-dark ms-2"><c:out value="${pendingAccounts.size()}" /></span>
        </div>
        <div class="card-body">
            <div class="table-responsive">
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr>
                        <th>Account No</th>
                        <th>Customer</th>
                        <th>Type</th>
                        <th>Product</th>
                        <th>Branch</th>
                        <th>Rate</th>
                        <th>Min Balance</th>
                        <th>Opened By</th>
                        <th>Opened Date</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="a" items="${pendingAccounts}">
                        <tr>
                            <td><a href="${pageContext.request.contextPath}/deposit/view/${a.accountNumber}"><c:out value="${a.accountNumber}" /></a></td>
                            <td><a href="${pageContext.request.contextPath}/customer/view/${a.customer.id}"><c:out value="${a.customer.firstName}" /> <c:out value="${a.customer.lastName}" /></a></td>
                            <td><span class="badge ${a.savings ? 'bg-success' : 'bg-info'}"><c:out value="${a.accountType}" /></span></td>
                            <td><c:out value="${a.productCode}" /></td>
                            <td><c:out value="${a.branch.branchCode}" /></td>
                            <td><fmt:formatNumber value="${a.interestRate}" maxFractionDigits="4" />%</td>
                            <td class="amount"><fmt:formatNumber value="${a.minimumBalance}" type="number" maxFractionDigits="0" /></td>
                            <td><c:out value="${a.createdBy}" /></td>
                            <td><c:out value="${a.openedDate}" /></td>
                            <td>
                                <form method="post" action="${pageContext.request.contextPath}/deposit/activate/${a.accountNumber}" class="d-inline">
                                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                    <button type="submit" class="btn btn-sm btn-fv-success" data-confirm="Activate this account? It will become operational immediately."><i class="bi bi-check-circle"></i> Activate</button>
                                </form>
                                <a href="${pageContext.request.contextPath}/deposit/view/${a.accountNumber}" class="btn btn-sm btn-outline-secondary">View</a>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty pendingAccounts}">
                        <tr><td colspan="10" class="text-center text-muted">No accounts pending activation</td></tr>
                    </c:if>
                </tbody>
            </table>
            </div>
        </div>
    </div>

    <!-- Stage 2: Active Accounts (Operational) -->
    <div class="fv-card">
        <div class="card-header">
            <i class="bi bi-check-circle text-success"></i> Active Accounts (Operational)
            <span class="badge bg-success ms-2"><c:out value="${activeAccounts.size()}" /></span>
        </div>
        <div class="card-body">
            <div class="table-responsive">
            <table class="table fv-table">
                <thead>
                    <tr>
                        <th>Account No</th>
                        <th>Customer</th>
                        <th>Type</th>
                        <th>Branch</th>
                        <th class="text-end">Ledger Balance</th>
                        <th class="text-end">Available</th>
                        <th>Last Txn</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="a" items="${activeAccounts}">
                        <tr>
                            <td><a href="${pageContext.request.contextPath}/deposit/view/${a.accountNumber}"><c:out value="${a.accountNumber}" /></a></td>
                            <td><c:out value="${a.customer.firstName}" /> <c:out value="${a.customer.lastName}" /></td>
                            <td><span class="badge ${a.savings ? 'bg-success' : 'bg-info'}"><c:out value="${a.accountType}" /></span></td>
                            <td><c:out value="${a.branch.branchCode}" /></td>
                            <td class="text-end amount"><fmt:formatNumber value="${a.ledgerBalance}" type="number" maxFractionDigits="2" /></td>
                            <td class="text-end amount"><fmt:formatNumber value="${a.effectiveAvailable}" type="number" maxFractionDigits="2" /></td>
                            <td><c:out value="${a.lastTransactionDate}" default="--" /></td>
                            <td>
                                <a href="${pageContext.request.contextPath}/deposit/view/${a.accountNumber}" class="btn btn-sm btn-outline-primary">View</a>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty activeAccounts}">
                        <tr><td colspan="8" class="text-center text-muted">No active accounts</td></tr>
                    </c:if>
                </tbody>
            </table>
            </div>
        </div>
    </div>

    <!-- Stage 3: Attention Required (Dormant / Frozen / Inoperative) -->
    <div class="fv-card">
        <div class="card-header">
            <i class="bi bi-exclamation-triangle text-danger"></i> Attention Required (Dormant / Frozen / Inoperative)
            <span class="badge bg-danger ms-2"><c:out value="${attentionAccounts.size()}" /></span>
        </div>
        <div class="card-body">
            <div class="table-responsive">
            <table class="table fv-table">
                <thead>
                    <tr>
                        <th>Account No</th>
                        <th>Customer</th>
                        <th>Type</th>
                        <th>Branch</th>
                        <th>Status</th>
                        <th class="text-end">Balance</th>
                        <th>Since</th>
                        <th>Reason</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="a" items="${attentionAccounts}">
                        <tr>
                            <td><a href="${pageContext.request.contextPath}/deposit/view/${a.accountNumber}"><c:out value="${a.accountNumber}" /></a></td>
                            <td><c:out value="${a.customer.firstName}" /> <c:out value="${a.customer.lastName}" /></td>
                            <td><c:out value="${a.accountType}" /></td>
                            <td><c:out value="${a.branch.branchCode}" /></td>
                            <td>
                                <c:choose>
                                    <c:when test="${a.frozen}"><span class="fv-badge fv-badge-npa">FROZEN &mdash; <c:out value="${a.freezeType}" /></span></c:when>
                                    <c:when test="${a.dormant}"><span class="fv-badge fv-badge-pending">DORMANT</span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-rejected"><c:out value="${a.accountStatus}" /></span></c:otherwise>
                                </c:choose>
                            </td>
                            <td class="text-end amount"><fmt:formatNumber value="${a.ledgerBalance}" type="number" maxFractionDigits="2" /></td>
                            <td><c:out value="${a.dormant ? a.dormantDate : a.updatedAt}" default="--" /></td>
                            <td><c:out value="${a.freezeReason}" default="--" /></td>
                            <td>
                                <a href="${pageContext.request.contextPath}/deposit/view/${a.accountNumber}" class="btn btn-sm btn-outline-primary">View</a>
                                <c:if test="${a.frozen && pageContext.request.isUserInRole('ROLE_ADMIN')}">
                                    <form method="post" action="${pageContext.request.contextPath}/deposit/unfreeze/${a.accountNumber}" class="d-inline">
                                        <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                        <button type="submit" class="btn btn-sm btn-outline-success" data-confirm="Unfreeze this account?"><i class="bi bi-unlock"></i> Unfreeze</button>
                                    </form>
                                </c:if>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty attentionAccounts}">
                        <tr><td colspan="9" class="text-center text-muted">No accounts requiring attention</td></tr>
                    </c:if>
                </tbody>
            </table>
            </div>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>