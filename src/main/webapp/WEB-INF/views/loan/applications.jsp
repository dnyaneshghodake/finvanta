<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Loan Applications" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <c:if test="${not empty success}">
        <div class="fv-alert alert alert-success"><c:out value="${success}" /></div>
    </c:if>
    <c:if test="${not empty error}">
        <div class="fv-alert alert alert-danger"><c:out value="${error}" /></div>
    </c:if>

    <div class="fv-card">
        <div class="card-header">Submitted Applications (Pending Verification)</div>
        <div class="card-body">
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr>
                        <th>App No.</th>
                        <th>Customer</th>
                        <th>Product</th>
                        <th class="text-end">Amount</th>
                        <th>Rate</th>
                        <th>Tenure</th>
                        <th>Status</th>
                        <th>Date</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="app" items="${applications}">
                        <tr>
                            <td><c:out value="${app.applicationNumber}" /></td>
                            <td><a href="${pageContext.request.contextPath}/customer/view/${app.customer.id}"><c:out value="${app.customer.fullName}" /></a></td>
                            <td><c:out value="${app.productType}" /></td>
                            <td class="amount"><fmt:formatNumber value="${app.requestedAmount}" type="number" maxFractionDigits="2" /></td>
                            <td><fmt:formatNumber value="${app.interestRate}" type="number" maxFractionDigits="2" />%</td>
                            <td><c:out value="${app.tenureMonths}" /> mo</td>
                            <td><span class="fv-badge fv-badge-pending"><c:out value="${app.status}" /></span></td>
                            <td><c:out value="${app.applicationDate}" /></td>
                            <td>
                                <a href="${pageContext.request.contextPath}/loan/verify/${app.id}" class="btn btn-sm btn-success">Verify</a>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty applications}">
                        <tr><td colspan="9" class="text-center text-muted">No pending applications</td></tr>
                    </c:if>
                </tbody>
            </table>
        </div>
    </div>

    <div class="fv-card">
        <div class="card-header">Verified Applications (Pending Approval)</div>
        <div class="card-body">
            <table class="table fv-table">
                <thead>
                    <tr>
                        <th>App No.</th>
                        <th>Customer</th>
                        <th>Product</th>
                        <th class="text-end">Amount</th>
                        <th>Verified By</th>
                        <th>Status</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="app" items="${verifiedApplications}">
                        <tr>
                            <td><c:out value="${app.applicationNumber}" /></td>
                            <td><a href="${pageContext.request.contextPath}/customer/view/${app.customer.id}"><c:out value="${app.customer.fullName}" /></a></td>
                            <td><c:out value="${app.productType}" /></td>
                            <td class="amount"><fmt:formatNumber value="${app.requestedAmount}" type="number" maxFractionDigits="2" /></td>
                            <td><c:out value="${app.verifiedBy}" /></td>
                            <td><span class="fv-badge fv-badge-approved"><c:out value="${app.status}" /></span></td>
                            <td>
                                <a href="${pageContext.request.contextPath}/loan/approve/${app.id}" class="btn btn-sm btn-fv-primary">Approve</a>
                                <form method="post" action="${pageContext.request.contextPath}/loan/reject/${app.id}" class="d-inline">
                                    <input type="hidden" name="reason" value="Not meeting criteria" />
                                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                    <button type="submit" class="btn btn-sm btn-danger" data-confirm="Reject this application?">Reject</button>
                                </form>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty verifiedApplications}">
                        <tr><td colspan="7" class="text-center text-muted">No verified applications pending approval</td></tr>
                    </c:if>
                </tbody>
            </table>
        </div>
    </div>

    <div class="fv-card">
        <div class="card-header">Approved Applications (Ready for Account Creation)</div>
        <div class="card-body">
            <table class="table fv-table">
                <thead>
                    <tr>
                        <th>App No.</th>
                        <th>Customer</th>
                        <th class="text-end">Approved Amount</th>
                        <th>Approved By</th>
                        <th>Date</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="app" items="${approvedApplications}">
                        <tr>
                            <td><c:out value="${app.applicationNumber}" /></td>
                            <td><a href="${pageContext.request.contextPath}/customer/view/${app.customer.id}"><c:out value="${app.customer.fullName}" /></a></td>
                            <td class="amount"><fmt:formatNumber value="${app.approvedAmount}" type="number" maxFractionDigits="2" /></td>
                            <td><c:out value="${app.approvedBy}" /></td>
                            <td><c:out value="${app.approvedDate}" /></td>
                            <td>
                                <form method="post" action="${pageContext.request.contextPath}/loan/create-account/${app.id}" class="d-inline">
                                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                    <button type="submit" class="btn btn-sm btn-success">Create Account</button>
                                </form>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty approvedApplications}">
                        <tr><td colspan="6" class="text-center text-muted">No approved applications</td></tr>
                    </c:if>
                </tbody>
            </table>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
