<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Branches" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <c:if test="${not empty success}">
        <div class="fv-alert alert alert-success"><c:out value="${success}" /></div>
    </c:if>
    <div class="fv-card">
        <div class="card-header">Branch List <a href="${pageContext.request.contextPath}/branch/add" class="btn btn-sm btn-fv-primary float-end">+ Add Branch</a></div>
        <div class="card-body">
            <table class="table fv-table fv-datatable">
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
                            <td><span class="fv-badge fv-badge-active">Active</span></td>
                        </tr>
                    </c:forEach>
                </tbody>
            </table>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
