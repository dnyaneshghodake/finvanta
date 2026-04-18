<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Loan Applications" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <ul class="fv-breadcrumb">
        <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
        <li class="active">Loan Applications</li>
    </ul>

    <c:if test="${not empty success}">
        <div class="fv-alert alert alert-success"><c:out value="${success}" /></div>
    </c:if>
    <c:if test="${not empty error}">
        <div class="fv-alert alert alert-danger"><c:out value="${error}" /></div>
    </c:if>

    <!-- CBS: Loan Application search per Finacle APPINQ -->
    <form method="get" action="${pageContext.request.contextPath}/loan/applications/search" class="row g-2 mb-3">
        <div class="col-auto">
            <input type="text" name="q" class="form-control form-control-sm" placeholder="Search by app no, CIF, customer name..." value="<c:out value='${searchQuery}'/>" minlength="2" style="width:320px;" />
        </div>
        <div class="col-auto">
            <button type="submit" class="btn btn-sm btn-fv-primary"><i class="bi bi-search"></i> Search</button>
        </div>
        <c:if test="${not empty searchQuery}">
        <div class="col-auto">
            <a href="${pageContext.request.contextPath}/loan/applications" class="btn btn-sm btn-outline-secondary">Clear</a>
        </div>
        </c:if>
    </form>

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
                                <a href="${pageContext.request.contextPath}/loan/verify/${app.id}" class="btn btn-sm btn-fv-success"><i class="bi bi-clipboard-check"></i> Verify</a>
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
                                    <input type="hidden" name="reason" value="" class="fv-reason-field" />
                                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                    <button type="button" class="btn btn-sm btn-fv-danger"
                                        data-fv-reason-prompt="Rejection reason (mandatory per RBI Fair Practices Code):"
                                        data-fv-reason-confirm="Reject this loan application?"
                                        onclick="fvPromptReason(this);"><i class="bi bi-x-circle"></i> Reject</button>
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
                        <th>Rate</th>
                        <th>Tenure</th>
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
                            <td><fmt:formatNumber value="${app.interestRate}" type="number" maxFractionDigits="2" />%</td>
                            <td><c:out value="${app.tenureMonths}" /> mo</td>
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
                        <tr><td colspan="8" class="text-center text-muted">No approved applications</td></tr>
                    </c:if>
                </tbody>
            </table>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
