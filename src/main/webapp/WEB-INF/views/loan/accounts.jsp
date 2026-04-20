<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Loan Accounts" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <ul class="fv-breadcrumb">
        <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
        <li class="active">Loan Accounts</li>
    </ul>

    <c:if test="${not empty success}">
        <div class="fv-alert alert alert-success"><c:out value="${success}" /></div>
    </c:if>

    <div class="fv-card">
        <div class="card-header"><i class="bi bi-bank"></i> Active Loan Accounts</div>
        <div class="card-body">
            <!-- CBS: Loan Account search per Finacle LOANINQ -->
            <form method="get" action="${pageContext.request.contextPath}/loan/accounts/search" class="row g-2 mb-3">
                <div class="col-auto">
                    <input type="text" name="q" class="form-control form-control-sm fv-search-input" placeholder="Search by account no, CIF, customer name..." value="<c:out value='${searchQuery}'/>" minlength="2" />
                </div>
                <div class="col-auto">
                    <button type="submit" class="btn btn-sm btn-fv-primary"><i class="bi bi-search"></i> Search</button>
                </div>
                <c:if test="${not empty searchQuery}">
                <div class="col-auto">
                    <a href="${pageContext.request.contextPath}/loan/accounts" class="btn btn-sm btn-outline-secondary">Clear</a>
                </div>
                </c:if>
            </form>
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr>
                        <th>Account No.</th>
                        <th>Customer</th>
                        <th>Product</th>
                        <th class="text-end">Sanctioned</th>
                        <th class="text-end">Outstanding</th>
                        <th>Rate</th>
                        <th class="text-end">EMI</th>
                        <th>DPD</th>
                        <th>Status</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="acc" items="${accounts}">
                        <tr>
                            <td><a href="${pageContext.request.contextPath}/loan/account/${acc.accountNumber}"><c:out value="${acc.accountNumber}" /></a></td>
                            <td><a href="${pageContext.request.contextPath}/customer/view/${acc.customer.id}"><c:out value="${acc.customer.fullName}" /></a></td>
                            <td><c:out value="${acc.productType}" /></td>
                            <td class="amount"><fmt:formatNumber value="${acc.sanctionedAmount}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${acc.totalOutstanding}" type="number" maxFractionDigits="2" /></td>
                            <td><fmt:formatNumber value="${acc.interestRate}" maxFractionDigits="2" />%</td>
                            <td class="amount"><fmt:formatNumber value="${acc.emiAmount}" type="number" maxFractionDigits="2" /></td>
                            <td>
                                <c:choose>
                                    <c:when test="${acc.daysPastDue > 90}"><span class="fv-badge fv-badge-npa"><c:out value="${acc.daysPastDue}" /></span></c:when>
                                    <c:when test="${acc.daysPastDue > 0}"><span class="fv-badge fv-badge-pending"><c:out value="${acc.daysPastDue}" /></span></c:when>
                                    <c:otherwise><c:out value="${acc.daysPastDue}" /></c:otherwise>
                                </c:choose>
                            </td>
                            <td>
                                <c:choose>
                                    <c:when test="${acc.status.npa}"><span class="fv-badge fv-badge-npa"><c:out value="${acc.status}" /></span></c:when>
                                    <c:when test="${acc.status.sma}"><span class="fv-badge fv-badge-pending"><c:out value="${acc.status}" /></span></c:when>
                                    <c:when test="${acc.status.terminal}"><span class="fv-badge fv-badge-closed"><c:out value="${acc.status}" /></span></c:when>
                                    <c:when test="${acc.status == 'RESTRUCTURED'}"><span class="fv-badge fv-badge-pending"><c:out value="${acc.status}" /></span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-active"><c:out value="${acc.status}" /></span></c:otherwise>
                                </c:choose>
                            </td>
                            <td>
                                <a href="${pageContext.request.contextPath}/loan/account/${acc.accountNumber}" class="btn btn-sm btn-fv-primary">View</a>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty accounts}">
                        <tr><td colspan="10" class="text-center text-muted">No active accounts</td></tr>
                    </c:if>
                </tbody>
            </table>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
