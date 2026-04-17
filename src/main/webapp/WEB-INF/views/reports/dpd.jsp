<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="DPD Distribution Report" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <ul class="fv-breadcrumb">
        <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
        <li class="active">DPD Report</li>
    </ul>
    <div class="fv-card">
        <div class="card-header">DPD Distribution Report &mdash; <c:out value="${businessDate}" /> (${totalAccounts} accounts)</div>
        <div class="card-body">
            <table class="table fv-table">
                <thead>
                    <tr>
                        <th>DPD Bucket</th>
                        <th class="text-end">Accounts</th>
                        <th class="text-end">Outstanding (INR)</th>
                        <th class="text-end">Provisioning (INR)</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="row" items="${dpdData}">
                        <tr>
                            <td class="fw-bold"><c:out value="${row.bucket}" /></td>
                            <td class="text-end"><c:out value="${row.count}" /></td>
                            <td class="amount"><fmt:formatNumber value="${row.outstanding}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${row.provisioning}" type="number" maxFractionDigits="2" /></td>
                        </tr>
                    </c:forEach>
                </tbody>
            </table>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>