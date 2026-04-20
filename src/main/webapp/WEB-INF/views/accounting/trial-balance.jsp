<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Trial Balance" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <ul class="fv-breadcrumb">
        <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
        <li class="active">Trial Balance</li>
    </ul>

    <!-- Balance Status -->
    <div class="row g-3 mb-3">
        <div class="col">
            <div class="fv-stat-card">
                <div class="stat-icon"><i class="bi bi-arrow-up-right"></i></div>
                <div class="stat-value amount"><fmt:formatNumber value="${trialBalance.totalDebit}" type="number" maxFractionDigits="2" /></div>
                <div class="stat-label">Total Debits</div>
            </div>
        </div>
        <div class="col">
            <div class="fv-stat-card">
                <div class="stat-icon"><i class="bi bi-arrow-down-left"></i></div>
                <div class="stat-value amount"><fmt:formatNumber value="${trialBalance.totalCredit}" type="number" maxFractionDigits="2" /></div>
                <div class="stat-label">Total Credits</div>
            </div>
        </div>
        <div class="col">
            <div class="fv-stat-card ${trialBalance.isBalanced ? 'stat-success' : 'stat-danger'}">
                <div class="stat-icon"><i class="bi bi-check-circle"></i></div>
                <div class="stat-value"><c:choose><c:when test="${trialBalance.isBalanced}">BALANCED</c:when><c:otherwise>IMBALANCED</c:otherwise></c:choose></div>
                <div class="stat-label">Balance Status</div>
            </div>
        </div>
    </div>

    <div class="fv-card">
        <div class="card-header">GL Account Balances</div>
        <div class="card-body">
            <!-- CBS: GL Account search per Finacle GLINQ -->
            <form method="get" action="${pageContext.request.contextPath}/accounting/gl/search" class="row g-2 mb-3">
                <div class="col-auto">
                    <input type="text" name="q" class="form-control form-control-sm fv-search-input" placeholder="Search by GL code, name, type (ASSET/LIABILITY)..." value="<c:out value='${searchQuery}'/>" minlength="2" />
                </div>
                <div class="col-auto">
                    <button type="submit" class="btn btn-sm btn-fv-primary"><i class="bi bi-search"></i> Search</button>
                </div>
                <c:if test="${not empty searchQuery}">
                <div class="col-auto">
                    <a href="${pageContext.request.contextPath}/accounting/trial-balance" class="btn btn-sm btn-outline-secondary">Clear</a>
                </div>
                </c:if>
            </form>
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr>
                        <th>GL Code</th>
                        <th>GL Name</th>
                        <th>Account Type</th>
                        <th class="text-end">Debit Balance</th>
                        <th class="text-end">Credit Balance</th>
                        <th class="text-end">Net Balance</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="entry" items="${trialBalance.accounts}">
                        <tr>
                            <td><c:out value="${entry.value.glCode}" /></td>
                            <td><c:out value="${entry.value.glName}" /></td>
                            <td><c:out value="${entry.value.accountType}" /></td>
                            <td class="amount"><fmt:formatNumber value="${entry.value.debitBalance}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${entry.value.creditBalance}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount fw-bold"><fmt:formatNumber value="${entry.value.netBalance}" type="number" maxFractionDigits="2" /></td>
                        </tr>
                    </c:forEach>
                </tbody>
                <tfoot>
                    <tr class="fw-bold table-light">
                        <td colspan="3">TOTAL</td>
                        <td class="amount"><fmt:formatNumber value="${trialBalance.totalDebit}" type="number" maxFractionDigits="2" /></td>
                        <td class="amount"><fmt:formatNumber value="${trialBalance.totalCredit}" type="number" maxFractionDigits="2" /></td>
                        <td class="amount"><fmt:formatNumber value="${trialBalance.totalDebit - trialBalance.totalCredit}" type="number" maxFractionDigits="2" /></td>
                    </tr>
                </tfoot>
            </table>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
