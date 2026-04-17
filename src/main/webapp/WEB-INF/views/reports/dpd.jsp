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
        <div class="card-header"><i class="bi bi-clock-history"></i> DPD Distribution Report &mdash; <c:out value="${businessDate}" /> (${totalAccounts} accounts)</div>
        <div class="card-body">
            <%-- CBS Tier-1: Count column uses `.amount` class so digits right-align AND use
                 monospace tabular-nums — same treatment as Outstanding/Provisioning so auditors
                 can visually scan the column. Finacle DPD_REPORT & Temenos IRAC.REPORT pattern. --%>
            <div class="table-responsive">
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
                    <c:set var="dpdTotalCount" value="0" />
                    <c:set var="dpdTotalOutstanding" value="0" />
                    <c:set var="dpdTotalProvisioning" value="0" />
                    <c:forEach var="row" items="${dpdData}">
                        <tr>
                            <td class="fw-bold"><c:out value="${row.bucket}" /></td>
                            <td class="amount"><c:out value="${row.count}" /></td>
                            <td class="amount"><fmt:formatNumber value="${row.outstanding}" type="number" maxFractionDigits="2" /></td>
                            <td class="amount"><fmt:formatNumber value="${row.provisioning}" type="number" maxFractionDigits="2" /></td>
                        </tr>
                        <c:set var="dpdTotalCount" value="${dpdTotalCount + row.count}" />
                        <c:set var="dpdTotalOutstanding" value="${dpdTotalOutstanding + row.outstanding}" />
                        <c:set var="dpdTotalProvisioning" value="${dpdTotalProvisioning + row.provisioning}" />
                    </c:forEach>
                    <c:if test="${empty dpdData}">
                        <tr><td colspan="4" class="text-center text-muted">No DPD data available</td></tr>
                    </c:if>
                </tbody>
                <%-- CBS Tier-1: Totals row per RBI DSA reporting template (aggregate accounts,
                     outstanding principal, provisioning across all DPD buckets). --%>
                <c:if test="${not empty dpdData}">
                <tfoot>
                    <tr class="fw-bold" style="background:#f8f9fa;border-top:2px solid var(--fv-primary);">
                        <td>Total</td>
                        <td class="amount"><c:out value="${dpdTotalCount}" /></td>
                        <td class="amount"><fmt:formatNumber value="${dpdTotalOutstanding}" type="number" maxFractionDigits="2" /></td>
                        <td class="amount"><fmt:formatNumber value="${dpdTotalProvisioning}" type="number" maxFractionDigits="2" /></td>
                    </tr>
                </tfoot>
                </c:if>
            </table>
            </div>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
