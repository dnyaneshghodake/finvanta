<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Charge Configuration" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <div class="fv-card">
        <div class="card-header">Charge Configuration (Finacle CHRG_MASTER)</div>
        <div class="card-body">
            <p class="text-muted">Fee schedules with FLAT/PERCENTAGE/SLAB calculation and GST support. All charges are posted via TransactionEngine with 3-leg journal entries.</p>
            <div class="table-responsive">
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr>
                        <th>Code</th>
                        <th>Name</th>
                        <th>Trigger</th>
                        <th>Type</th>
                        <th class="text-end">Amount / Rate</th>
                        <th class="text-end">Min</th>
                        <th class="text-end">Max</th>
                        <th>GST</th>
                        <th>GL Income</th>
                        <th>Product</th>
                        <th>Status</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="c" items="${charges}">
                        <tr>
                            <td class="fw-bold"><c:out value="${c.chargeCode}" /></td>
                            <td><c:out value="${c.chargeName}" /></td>
                            <td><c:out value="${c.eventTrigger}" /></td>
                            <td><c:out value="${c.calculationType}" /></td>
                            <td class="text-end amount">
                                <c:choose>
                                    <c:when test="${c.calculationType == 'FLAT'}"><fmt:formatNumber value="${c.baseAmount}" type="number" maxFractionDigits="2" /></c:when>
                                    <c:when test="${c.calculationType == 'PERCENTAGE'}"><fmt:formatNumber value="${c.percentage}" maxFractionDigits="2" />%</c:when>
                                    <c:otherwise>SLAB</c:otherwise>
                                </c:choose>
                            </td>
                            <td class="text-end amount"><c:if test="${c.minAmount != null}"><fmt:formatNumber value="${c.minAmount}" type="number" maxFractionDigits="2" /></c:if><c:if test="${c.minAmount == null}">--</c:if></td>
                            <td class="text-end amount"><c:if test="${c.maxAmount != null}"><fmt:formatNumber value="${c.maxAmount}" type="number" maxFractionDigits="2" /></c:if><c:if test="${c.maxAmount == null}">--</c:if></td>
                            <td>
                                <c:choose>
                                    <c:when test="${c.gstApplicable}"><span class="fv-badge fv-badge-active"><fmt:formatNumber value="${c.gstRate}" maxFractionDigits="0" />%</span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-closed">Exempt</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td class="font-monospace"><c:out value="${c.glChargeIncome}" /></td>
                            <td><c:out value="${c.productCode}" default="ALL" /></td>
                            <td>
                                <c:choose>
                                    <c:when test="${c.isActive}"><span class="fv-badge fv-badge-active">ACTIVE</span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-rejected">INACTIVE</span></c:otherwise>
                                </c:choose>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty charges}">
                        <tr><td colspan="11" class="text-center text-muted">No charge configurations found</td></tr>
                    </c:if>
                </tbody>
            </table>
            </div>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
