<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Financial Statements" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <ul class="fv-breadcrumb">
        <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
        <li class="active">Financial Statements</li>
    </ul>

    <div class="row g-3">
        <!-- Balance Sheet -->
        <div class="col-md-6">
            <div class="fv-card">
                <div class="card-header"><i class="bi bi-file-earmark-bar-graph"></i> Balance Sheet</div>
                <div class="card-body">
                    <h6 class="text-primary">Assets</h6>
                    <table class="table table-sm mb-2">
                        <c:forEach var="entry" items="${assets}">
                            <tr><td><c:out value="${entry.key}"/></td><td class="text-end amount"><fmt:formatNumber value="${entry.value}" type="number" maxFractionDigits="2"/></td></tr>
                        </c:forEach>
                        <tr class="table-active fw-bold"><td>Total Assets</td><td class="text-end amount"><fmt:formatNumber value="${totalAssets}" type="number" maxFractionDigits="2"/></td></tr>
                    </table>

                    <h6 class="text-danger">Liabilities</h6>
                    <table class="table table-sm mb-2">
                        <c:forEach var="entry" items="${liabilities}">
                            <tr><td><c:out value="${entry.key}"/></td><td class="text-end amount"><fmt:formatNumber value="${entry.value}" type="number" maxFractionDigits="2"/></td></tr>
                        </c:forEach>
                        <tr class="table-active fw-bold"><td>Total Liabilities</td><td class="text-end amount"><fmt:formatNumber value="${totalLiabilities}" type="number" maxFractionDigits="2"/></td></tr>
                    </table>

                    <h6 class="text-success">Equity</h6>
                    <table class="table table-sm mb-2">
                        <c:forEach var="entry" items="${equity}">
                            <tr><td><c:out value="${entry.key}"/></td><td class="text-end amount"><fmt:formatNumber value="${entry.value}" type="number" maxFractionDigits="2"/></td></tr>
                        </c:forEach>
                        <tr class="table-active fw-bold"><td>Total Equity</td><td class="text-end amount"><fmt:formatNumber value="${totalEquity}" type="number" maxFractionDigits="2"/></td></tr>
                    </table>

                    <div class="alert ${balanceCheck.signum() == 0 ? 'alert-success' : 'alert-danger'} mt-3">
                        <strong>Balance Check:</strong> Assets - (Liabilities + Equity + Net Profit) =
                        <fmt:formatNumber value="${balanceCheck}" type="number" maxFractionDigits="2"/>
                        <c:if test="${balanceCheck.signum() == 0}"> ✅ BALANCED</c:if>
                        <c:if test="${balanceCheck.signum() != 0}"> ❌ IMBALANCED</c:if>
                    </div>
                </div>
            </div>
        </div>

        <!-- Income Statement -->
        <div class="col-md-6">
            <div class="fv-card">
                <div class="card-header"><i class="bi bi-graph-up"></i> Income Statement (P&amp;L)</div>
                <div class="card-body">
                    <h6 class="text-success">Income</h6>
                    <table class="table table-sm mb-2">
                        <c:forEach var="entry" items="${income}">
                            <tr><td><c:out value="${entry.key}"/></td><td class="text-end amount text-success"><fmt:formatNumber value="${entry.value}" type="number" maxFractionDigits="2"/></td></tr>
                        </c:forEach>
                        <tr class="table-active fw-bold"><td>Total Income</td><td class="text-end amount text-success"><fmt:formatNumber value="${totalIncome}" type="number" maxFractionDigits="2"/></td></tr>
                    </table>

                    <h6 class="text-danger">Expenses</h6>
                    <table class="table table-sm mb-2">
                        <c:forEach var="entry" items="${expenses}">
                            <tr><td><c:out value="${entry.key}"/></td><td class="text-end amount text-danger"><fmt:formatNumber value="${entry.value}" type="number" maxFractionDigits="2"/></td></tr>
                        </c:forEach>
                        <tr class="table-active fw-bold"><td>Total Expenses</td><td class="text-end amount text-danger"><fmt:formatNumber value="${totalExpenses}" type="number" maxFractionDigits="2"/></td></tr>
                    </table>

                    <div class="fv-stat-card mt-3 ${netProfit.signum() >= 0 ? '' : 'stat-danger'}">
                        <div class="stat-value amount"><fmt:formatNumber value="${netProfit}" type="number" maxFractionDigits="2"/> INR</div>
                        <div class="stat-label">${netProfit.signum() >= 0 ? 'Net Profit' : 'Net Loss'}</div>
                    </div>
                </div>
            </div>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
