<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Branches" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="main-content">
    <div class="top-bar">
        <h2>Branch Management</h2>
        <div class="user-info">
            <a href="${pageContext.request.contextPath}/branch/add" class="btn btn-primary btn-sm">+ Add Branch</a>
        </div>
    </div>
    <div class="content-area">
        <c:if test="${not empty success}">
            <div class="alert alert-success"><c:out value="${success}" /></div>
        </c:if>
        <div class="card">
            <h3>Branch List</h3>
            <table>
                <thead>
                    <tr>
                        <th>Branch Code</th>
                        <th>Branch Name</th>
                        <th>IFSC</th>
                        <th>City</th>
                        <th>State</th>
                        <th>Region</th>
                        <th>Status</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="branch" items="${branches}">
                        <tr>
                            <td><c:out value="${branch.branchCode}" /></td>
                            <td><c:out value="${branch.branchName}" /></td>
                            <td><c:out value="${branch.ifscCode}" /></td>
                            <td><c:out value="${branch.city}" /></td>
                            <td><c:out value="${branch.state}" /></td>
                            <td><c:out value="${branch.region}" /></td>
                            <td><span class="badge badge-active">Active</span></td>
                        </tr>
                    </c:forEach>
                </tbody>
            </table>
        </div>
    </div>

<%@ include file="../layout/footer.jsp" %>
