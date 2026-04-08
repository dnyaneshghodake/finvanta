<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Product Master" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <div class="fv-card">
        <div class="card-header">Product Master (Finacle PDDEF)</div>
        <div class="card-body">
            <p class="text-muted">Product configuration drives GL codes, interest methods, limits, and fee schedules for all loan operations.</p>
            <div class="table-responsive">
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr>
                        <th>Code</th>
                        <th>Product Name</th>
                        <th>Category</th>
                        <th>Method / Type</th>
                        <th>Rate Range</th>
                        <th>Amount Range</th>
                        <th>Tenure</th>
                        <th>Penal</th>
                        <th>Status</th>
                        <th></th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="p" items="${products}">
                        <tr>
                            <td class="fw-bold"><c:out value="${p.productCode}" /></td>
                            <td><c:out value="${p.productName}" /><br/><small class="text-muted"><c:out value="${p.currencyCode}" /></small></td>
                            <td><c:out value="${p.productCategory}" /></td>
                            <td><c:out value="${p.interestMethod}" /><br/><small class="text-muted"><c:out value="${p.interestType}" /></small></td>
                            <td><fmt:formatNumber value="${p.minInterestRate}" maxFractionDigits="2" />% &ndash; <fmt:formatNumber value="${p.maxInterestRate}" maxFractionDigits="2" />%</td>
                            <td class="amount"><fmt:formatNumber value="${p.minLoanAmount}" type="number" maxFractionDigits="0" /> &ndash; <fmt:formatNumber value="${p.maxLoanAmount}" type="number" maxFractionDigits="0" /></td>
                            <td><c:out value="${p.minTenureMonths}" /> &ndash; <c:out value="${p.maxTenureMonths}" /> mo</td>
                            <td><fmt:formatNumber value="${p.defaultPenalRate}" maxFractionDigits="2" />%</td>
                            <td>
                                <c:choose>
                                    <c:when test="${p.active}"><span class="fv-badge fv-badge-active">ACTIVE</span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-rejected">INACTIVE</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td><a href="${pageContext.request.contextPath}/admin/products/${p.id}" class="btn btn-sm btn-outline-secondary"><i class="bi bi-diagram-2"></i> GL</a></td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty products}">
                        <tr><td colspan="10" class="text-center text-muted">No products configured</td></tr>
                    </c:if>
                </tbody>
            </table>
            </div>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
