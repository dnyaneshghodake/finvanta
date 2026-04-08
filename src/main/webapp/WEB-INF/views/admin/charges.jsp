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
                        <th>Code / Name</th>
                        <th>Trigger</th>
                        <th>Calculation</th>
                        <th class="text-end">Bounds</th>
                        <th>GST</th>
                        <th>GL / Product</th>
                        <th>Status</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="c" items="${charges}">
                        <tr>
                            <td><span class="fw-bold"><c:out value="${c.chargeCode}" /></span><br/><small class="text-muted"><c:out value="${c.chargeName}" /></small></td>
                            <td><c:out value="${c.eventTrigger}" /></td>
                            <td>
                                <c:out value="${c.calculationType}" />:
                                <span class="amount">
                                <c:choose>
                                    <c:when test="${c.calculationType == 'FLAT'}"><fmt:formatNumber value="${c.baseAmount}" type="number" maxFractionDigits="2" /></c:when>
                                    <c:when test="${c.calculationType == 'PERCENTAGE'}"><fmt:formatNumber value="${c.percentage}" maxFractionDigits="2" />%</c:when>
                                    <c:otherwise>Tiered</c:otherwise>
                                </c:choose>
                                </span>
                            </td>
                            <td class="text-end"><c:if test="${c.minAmount != null || c.maxAmount != null}"><small class="text-muted"><c:if test="${c.minAmount != null}"><fmt:formatNumber value="${c.minAmount}" type="number" maxFractionDigits="0" /></c:if><c:if test="${c.minAmount == null}">0</c:if> &ndash; <c:if test="${c.maxAmount != null}"><fmt:formatNumber value="${c.maxAmount}" type="number" maxFractionDigits="0" /></c:if><c:if test="${c.maxAmount == null}">No cap</c:if></small></c:if><c:if test="${c.minAmount == null && c.maxAmount == null}">--</c:if></td>
                            <td>
                                <c:choose>
                                    <c:when test="${c.gstApplicable}"><span class="fv-badge fv-badge-active"><fmt:formatNumber value="${c.gstRate}" maxFractionDigits="0" />%</span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-closed">Exempt</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td><span class="font-monospace"><c:out value="${c.glChargeIncome}" /></span><br/><small class="text-muted"><c:out value="${c.productCode}" default="ALL" /></small></td>
                            <td>
                                <c:choose>
                                    <c:when test="${c.isActive}"><span class="fv-badge fv-badge-active">ACTIVE</span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-rejected">INACTIVE</span></c:otherwise>
                                </c:choose>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty charges}">
                        <tr><td colspan="7" class="text-center text-muted">No charge configurations found</td></tr>
                    </c:if>
                </tbody>
            </table>
            </div>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
