<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Customers" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="main-content">
    <div class="top-bar">
        <h2>Customer Management</h2>
        <div class="user-info">
            <a href="${pageContext.request.contextPath}/customer/add" class="btn btn-primary btn-sm">+ Add Customer</a>
        </div>
    </div>
    <div class="content-area">
        <c:if test="${not empty success}">
            <div class="alert alert-success"><c:out value="${success}" /></div>
        </c:if>
        <div class="card">
            <h3>Customer List</h3>
            <table>
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
                                    <c:when test="${cust.kycVerified}"><span class="badge badge-active">Verified</span></c:when>
                                    <c:otherwise><span class="badge badge-rejected">Pending</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td><c:out value="${cust.cibilScore}" /></td>
                            <td><c:out value="${cust.branch.branchCode}" /></td>
                            <td>
                                <a href="${pageContext.request.contextPath}/customer/view/${cust.id}" class="btn btn-primary btn-sm">View</a>
                            </td>
                        </tr>
                    </c:forEach>
                </tbody>
            </table>
        </div>
    </div>

<%@ include file="../layout/footer.jsp" %>
