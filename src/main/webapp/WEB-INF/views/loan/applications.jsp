<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Loan Applications" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="main-content">
    <div class="top-bar">
        <h2>Loan Applications</h2>
        <div class="user-info">
            <a href="${pageContext.request.contextPath}/loan/apply" class="btn btn-primary btn-sm">+ New Application</a>
        </div>
    </div>
    <div class="content-area">
        <c:if test="${not empty success}">
            <div class="alert alert-success"><c:out value="${success}" /></div>
        </c:if>
        <c:if test="${not empty error}">
            <div class="alert alert-error"><c:out value="${error}" /></div>
        </c:if>

        <div class="card">
            <h3>Submitted Applications (Pending Verification)</h3>
            <table>
                <thead>
                    <tr>
                        <th>App No.</th>
                        <th>Customer</th>
                        <th>Product</th>
                        <th class="text-right">Amount</th>
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
                            <td><c:out value="${app.customer.fullName}" /></td>
                            <td><c:out value="${app.productType}" /></td>
                            <td class="text-right amount"><fmt:formatNumber value="${app.requestedAmount}" type="number" maxFractionDigits="2" /></td>
                            <td><fmt:formatNumber value="${app.interestRate}" type="number" maxFractionDigits="2" />%</td>
                            <td><c:out value="${app.tenureMonths}" /> mo</td>
                            <td><span class="badge badge-pending"><c:out value="${app.status}" /></span></td>
                            <td><c:out value="${app.applicationDate}" /></td>
                            <td>
                                <a href="${pageContext.request.contextPath}/loan/verify/${app.id}" class="btn btn-success btn-sm">Verify</a>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty applications}">
                        <tr><td colspan="9" style="text-align: center; color: #999;">No pending applications</td></tr>
                    </c:if>
                </tbody>
            </table>
        </div>

        <div class="card">
            <h3>Verified Applications (Pending Approval)</h3>
            <table>
                <thead>
                    <tr>
                        <th>App No.</th>
                        <th>Customer</th>
                        <th>Product</th>
                        <th class="text-right">Amount</th>
                        <th>Verified By</th>
                        <th>Status</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="app" items="${verifiedApplications}">
                        <tr>
                            <td><c:out value="${app.applicationNumber}" /></td>
                            <td><c:out value="${app.customer.fullName}" /></td>
                            <td><c:out value="${app.productType}" /></td>
                            <td class="text-right amount"><fmt:formatNumber value="${app.requestedAmount}" type="number" maxFractionDigits="2" /></td>
                            <td><c:out value="${app.verifiedBy}" /></td>
                            <td><span class="badge badge-approved"><c:out value="${app.status}" /></span></td>
                            <td>
                                <a href="${pageContext.request.contextPath}/loan/approve/${app.id}" class="btn btn-primary btn-sm">Approve</a>
                                <form method="post" action="${pageContext.request.contextPath}/loan/reject/${app.id}" style="display:inline;">
                                    <input type="hidden" name="reason" value="Not meeting criteria" />
                                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                    <button type="submit" class="btn btn-danger btn-sm" onclick="return confirm('Reject this application?')">Reject</button>
                                </form>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty verifiedApplications}">
                        <tr><td colspan="7" style="text-align: center; color: #999;">No verified applications pending approval</td></tr>
                    </c:if>
                </tbody>
            </table>
        </div>

        <div class="card">
            <h3>Approved Applications (Ready for Account Creation)</h3>
            <table>
                <thead>
                    <tr>
                        <th>App No.</th>
                        <th>Customer</th>
                        <th class="text-right">Approved Amount</th>
                        <th>Approved By</th>
                        <th>Date</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="app" items="${approvedApplications}">
                        <tr>
                            <td><c:out value="${app.applicationNumber}" /></td>
                            <td><c:out value="${app.customer.fullName}" /></td>
                            <td class="text-right amount"><fmt:formatNumber value="${app.approvedAmount}" type="number" maxFractionDigits="2" /></td>
                            <td><c:out value="${app.approvedBy}" /></td>
                            <td><c:out value="${app.approvedDate}" /></td>
                            <td>
                                <form method="post" action="${pageContext.request.contextPath}/loan/create-account/${app.id}" style="display:inline;">
                                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                    <button type="submit" class="btn btn-success btn-sm">Create Account</button>
                                </form>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty approvedApplications}">
                        <tr><td colspan="6" style="text-align: center; color: #999;">No approved applications</td></tr>
                    </c:if>
                </tbody>
            </table>
        </div>
    </div>

<%@ include file="../layout/footer.jsp" %>
