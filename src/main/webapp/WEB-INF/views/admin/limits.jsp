<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Transaction Limits" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <div class="fv-card">
        <div class="card-header">Transaction Limits (CBS Internal Controls)</div>
        <div class="card-body">
            <p class="text-muted">Per RBI guidelines on internal controls: every financial transaction is validated against per-role limits. Transactions exceeding limits require higher authority approval.</p>
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr>
                        <th>Role</th>
                        <th>Transaction Type</th>
                        <th class="text-end">Per-Transaction Limit</th>
                        <th class="text-end">Daily Aggregate Limit</th>
                        <th>Status</th>
                        <th>Description</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="limit" items="${limits}">
                        <tr>
                            <td class="fw-bold"><c:out value="${limit.role}" /></td>
                            <td><c:out value="${limit.transactionType}" /></td>
                            <td class="text-end amount">
                                <c:choose>
                                    <c:when test="${limit.perTransactionLimit != null}">
                                        <fmt:formatNumber value="${limit.perTransactionLimit}" type="number" maxFractionDigits="2" />
                                    </c:when>
                                    <c:otherwise><span class="text-muted">Unlimited</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td class="text-end amount">
                                <c:choose>
                                    <c:when test="${limit.dailyAggregateLimit != null}">
                                        <fmt:formatNumber value="${limit.dailyAggregateLimit}" type="number" maxFractionDigits="2" />
                                    </c:when>
                                    <c:otherwise><span class="text-muted">Unlimited</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td>
                                <c:choose>
                                    <c:when test="${limit.active}"><span class="fv-badge fv-badge-active">ACTIVE</span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-rejected">INACTIVE</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td><c:out value="${limit.description}" default="—" /></td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty limits}">
                        <tr><td colspan="6" class="text-center text-muted">No limits configured</td></tr>
                    </c:if>
                </tbody>
            </table>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
