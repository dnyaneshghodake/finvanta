<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Unclaimed Deposits (RBI UDGAM)" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <div class="row g-3 mb-3">
        <div class="col">
            <div class="fv-stat-card">
                <div class="stat-icon"><i class="bi bi-archive"></i></div>
                <div class="stat-value"><c:out value="${totalCount}"/></div>
                <div class="stat-label">Unclaimed Accounts</div>
            </div>
        </div>
        <div class="col">
            <div class="fv-stat-card stat-danger">
                <div class="stat-icon"><i class="bi bi-currency-rupee"></i></div>
                <div class="stat-value amount"><fmt:formatNumber value="${totalUnclaimed}" type="number" maxFractionDigits="2"/></div>
                <div class="stat-label">Total Unclaimed (INR)</div>
            </div>
        </div>
        <div class="col">
            <div class="fv-stat-card">
                <div class="stat-icon"><i class="bi bi-calendar"></i></div>
                <div class="stat-value"><c:out value="${businessDate}"/></div>
                <div class="stat-label">Report Date</div>
            </div>
        </div>
    </div>

    <div class="fv-card">
        <div class="card-header">
            Unclaimed Deposits — INOPERATIVE Accounts (10yr+ no transaction)
            <a href="${pageContext.request.contextPath}/reports/udgam/export" class="btn btn-sm btn-outline-success float-end"><i class="bi bi-download"></i> Export CSV for UDGAM</a>
        </div>
        <div class="card-body">
            <p class="text-muted small">Per RBI Unclaimed Deposits Direction 2024: accounts with no customer-initiated transaction for 10+ years with non-zero balance must be reported to RBI UDGAM portal.</p>
            <div class="table-responsive">
            <table class="table fv-table fv-datatable table-sm">
                <thead>
                    <tr>
                        <th>Account No</th><th>Customer CIF</th><th>Customer Name</th>
                        <th>Type</th><th>Branch</th><th class="text-end">Balance (INR)</th>
                        <th>Last Transaction</th><th>Opened</th><th>Dormant Since</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="da" items="${unclaimedAccounts}">
                        <tr>
                            <td><a href="${pageContext.request.contextPath}/deposit/view/${da.accountNumber}"><c:out value="${da.accountNumber}"/></a></td>
                            <td><c:out value="${da.customer.customerNumber}"/></td>
                            <td><a href="${pageContext.request.contextPath}/customer/view/${da.customer.id}"><c:out value="${da.customer.firstName}"/> <c:out value="${da.customer.lastName}"/></a></td>
                            <td><c:out value="${da.accountType}"/></td>
                            <td><c:out value="${da.branch.branchCode}"/></td>
                            <td class="text-end amount"><fmt:formatNumber value="${da.ledgerBalance}" type="number" maxFractionDigits="2"/></td>
                            <td><c:out value="${da.lastTransactionDate}" default="--"/></td>
                            <td><c:out value="${da.openedDate}" default="--"/></td>
                            <td><c:out value="${da.dormantDate}" default="--"/></td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty unclaimedAccounts}">
                        <tr><td colspan="9" class="text-center text-muted">No unclaimed deposits found. All accounts have recent activity.</td></tr>
                    </c:if>
                </tbody>
            </table>
            </div>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
