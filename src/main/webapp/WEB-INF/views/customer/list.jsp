<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Customers" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <c:if test="${not empty success}">
        <div class="fv-alert alert alert-success"><c:out value="${success}" /></div>
    </c:if>
    <div class="fv-card">
        <div class="card-header">Customer List <a href="${pageContext.request.contextPath}/customer/add" class="btn btn-sm btn-fv-primary float-end"><i class="bi bi-plus-circle"></i> Add Customer</a></div>
        <div class="card-body">
            <div class="table-responsive">
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr>
                        <th>Customer No.</th>
                        <th>Name</th>
                        <th>PAN</th>
                        <th>Mobile</th>
                        <th>KYC</th>
                        <th>CIBIL</th>
                        <th>Branch</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="cust" items="${customers}">
                        <tr>
                            <td><c:out value="${cust.customerNumber}" /></td>
                            <td><c:out value="${cust.fullName}" /></td>
                            <td><c:out value="${cust.panNumber}" /></td>
                            <td><c:out value="${cust.mobileNumber}" /></td>
                            <td>
                                <c:choose>
                                    <c:when test="${cust.kycVerified}"><span class="fv-badge fv-badge-active">Verified</span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-rejected">Pending</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td><c:out value="${cust.cibilScore}" /></td>
                            <td><c:out value="${cust.branch.branchCode}" /></td>
                            <td>
                                <a href="${pageContext.request.contextPath}/customer/view/${cust.id}" class="btn btn-sm btn-fv-primary"><i class="bi bi-eye"></i> View</a>
                            </td>
                        </tr>
                    </c:forEach>
                </tbody>
            </table>
            </div>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
