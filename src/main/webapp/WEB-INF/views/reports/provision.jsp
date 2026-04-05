<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Provision Adequacy Report" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <div class="row g-3 mb-3">
        <div class="col"><div class="fv-stat-card"><div class="stat-value amount"><fmt:formatNumber value="${totalOutstanding}" type="number" maxFractionDigits="0" /></div><div class="stat-label">Total Outstanding</div></div></div>
        <div class="col"><div class="fv-stat-card stat-warning"><div class="stat-value amount"><fmt:formatNumber value="${totalProvisioning}" type="number" maxFractionDigits="0" /></div><div class="stat-label">Total Provisioning</div></div></div>
        <div class="col"><div class="fv-stat-card"><div class="stat-value"><fmt:formatNumber value="${provisionCoverageRatio}" maxFractionDigits="2" />%</div><div class="stat-label">Provision Coverage</div></div></div>
    </div>

    <div class="fv-card">
        <div class="card-header">Provision Adequacy — Account Level — <c:out value="${businessDate}" /></div>
        <div class="card-body">
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr>
                        <th>Account No.</th>
                        <th>Customer</th>
                        <th>Status</th>
                        <th>DPD</th>
                        <th class="text-end">Outstanding (INR)</th>
                        <th class="text-end">Provisioning (INR)</th>
                        <th class="text-end">Rate %</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="row" items="${provisionData}">
                        <tr>
                            <td><a href="${pageContext.request.contextPath}/loan/account/${row.accountNumber}"><c:out value="${row.accountNumber}" /></a></td>
                            <td><c:out value="${row.customerName}" /></td>
                            <td>
                                <c:choose>
                                    <c:when test="${row.status == 'NPA_SUBSTANDARD' or row.status == 'NPA_DOUBTFUL' or row.status == 'NPA_LOSS'}"><span class="fv-badge fv-badge-npa"><c:out value="${row.status}" /></span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-pending"><c:out value="${row.status}" /></span></c:otherwise>
                                </c:choose>
                            </td>
                            <td><c:out value="${row.dpd}" /></td>
                            <td class="amount"><fmt:formatNumber value="${row.outstanding}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${row.provisioning}" type="number" maxFractionDigits="2" /></td>
                            <td class="text-end"><fmt:formatNumber value="${row.provisionRate}" maxFractionDigits="2" />%</td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty provisionData}">
                        <tr><td colspan="7" class="text-center text-muted">No accounts with provisioning</td></tr>
                    </c:if>
                </tbody>
            </table>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>