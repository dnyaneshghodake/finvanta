<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="IRAC Classification Report" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <ul class="fv-breadcrumb">
        <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
        <li class="active">IRAC Report</li>
    </ul>
    <div class="row g-3 mb-3">
        <div class="col"><div class="fv-stat-card"><div class="stat-value"><c:out value="${totalAccounts}" /></div><div class="stat-label">Total Accounts</div></div></div>
        <div class="col"><div class="fv-stat-card"><div class="stat-value amount"><fmt:formatNumber value="${totalOutstanding}" type="number" maxFractionDigits="0" /></div><div class="stat-label">Total Outstanding</div></div></div>
        <div class="col"><div class="fv-stat-card stat-danger"><div class="stat-value amount"><fmt:formatNumber value="${totalNpaOutstanding}" type="number" maxFractionDigits="0" /></div><div class="stat-label">NPA Outstanding</div></div></div>
        <div class="col"><div class="fv-stat-card ${npaRatio > 5 ? 'stat-danger' : 'stat-success'}"><div class="stat-value"><fmt:formatNumber value="${npaRatio}" maxFractionDigits="2" />%</div><div class="stat-label">Gross NPA Ratio</div></div></div>
    </div>

    <div class="fv-card">
        <div class="card-header">RBI IRAC Asset Classification &mdash; <c:out value="${businessDate}" /></div>
        <div class="card-body">
            <table class="table fv-table">
                <thead>
                    <tr>
                        <th>Status</th>
                        <th>IRAC Category</th>
                        <th class="text-end">Accounts</th>
                        <th class="text-end">Outstanding (INR)</th>
                        <th class="text-end">Provisioning (INR)</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="row" items="${iracData}">
                        <tr class="${row.isNpa ? 'table-light' : ''}">
                            <td>
                                <c:choose>
                                    <c:when test="${row.isNpa}"><span class="fv-badge fv-badge-npa"><c:out value="${row.status}" /></span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-active"><c:out value="${row.status}" /></span></c:otherwise>
                                </c:choose>
                            </td>
                            <td><c:out value="${row.category}" /></td>
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