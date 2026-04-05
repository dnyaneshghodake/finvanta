<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Loan Accounts" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="main-content">
    <div class="top-bar">
        <h2>Loan Accounts</h2>
        <div class="user-info">
            <span><c:out value="${pageContext.request.userPrincipal.name}" /></span>
        </div>
    </div>
    <div class="content-area">
        <c:if test="${not empty success}">
            <div class="alert alert-success"><c:out value="${success}" /></div>
        </c:if>

        <div class="card">
            <h3>Active Loan Accounts</h3>
            <table>
                <thead>
                    <tr>
                        <th>Account No.</th>
                        <th>Customer</th>
                        <th>Product</th>
                        <th class="text-right">Sanctioned</th>
                        <th class="text-right">Outstanding</th>
                        <th>Rate</th>
                        <th>EMI</th>
                        <th>DPD</th>
                        <th>Status</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="acc" items="${accounts}">
                        <tr>
                            <td><c:out value="${acc.accountNumber}" /></td>
                            <td><c:out value="${acc.customer.fullName}" /></td>
                            <td><c:out value="${acc.productType}" /></td>
                            <td class="text-right amount"><fmt:formatNumber value="${acc.sanctionedAmount}" type="number" maxFractionDigits="2" /></td>
                            <td class="text-right amount"><fmt:formatNumber value="${acc.totalOutstanding}" type="number" maxFractionDigits="2" /></td>
                            <td><fmt:formatNumber value="${acc.interestRate}" maxFractionDigits="2" />%</td>
                            <td class="amount"><fmt:formatNumber value="${acc.emiAmount}" type="number" maxFractionDigits="2" /></td>
                            <td style="color: ${acc.daysPastDue > 90 ? '#c62828' : (acc.daysPastDue > 0 ? '#ef6c00' : '#333')}; font-weight: bold;">
                                <c:out value="${acc.daysPastDue}" />
                            </td>
                            <td>
                                <span class="badge ${acc.status.npa() ? 'badge-npa' : 'badge-active'}">
                                    <c:out value="${acc.status}" />
                                </span>
                            </td>
                            <td>
                                <a href="${pageContext.request.contextPath}/loan/account/${acc.accountNumber}" class="btn btn-primary btn-sm">View</a>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty accounts}">
                        <tr><td colspan="10" style="text-align: center; color: #999;">No active accounts</td></tr>
                    </c:if>
                </tbody>
            </table>
        </div>
    </div>

<%@ include file="../layout/footer.jsp" %>
