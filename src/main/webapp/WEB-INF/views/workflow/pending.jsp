<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Pending Approvals" />
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
        <div class="card-header">Items Pending Approval (Maker-Checker)</div>
        <div class="card-body">
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr>
                        <th>Entity Type</th>
                        <th>Entity ID</th>
                        <th>Action</th>
                        <th>Maker</th>
                        <th>Submitted At</th>
                        <th>Remarks</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="item" items="${pendingItems}">
                        <tr>
                            <td><c:out value="${item.entityType}" /></td>
                            <td><c:out value="${item.entityId}" /></td>
                            <td><c:out value="${item.actionType}" /></td>
                            <td><c:out value="${item.makerUserId}" /></td>
                            <td><c:out value="${item.submittedAt}" /></td>
                            <td><c:out value="${item.makerRemarks}" /></td>
                            <td>
                                <form method="post" action="${pageContext.request.contextPath}/workflow/approve/${item.id}" class="d-inline">
                                    <input type="hidden" name="remarks" value="Approved" />
                                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                    <button type="submit" class="btn btn-sm btn-success">Approve</button>
                                </form>
                                <form method="post" action="${pageContext.request.contextPath}/workflow/reject/${item.id}" class="d-inline">
                                    <input type="hidden" name="remarks" value="Rejected" />
                                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                    <button type="submit" class="btn btn-sm btn-danger" data-confirm="Reject this item?">Reject</button>
                                </form>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty pendingItems}">
                        <tr><td colspan="7" class="text-center text-muted">No pending approvals</td></tr>
                    </c:if>
                </tbody>
            </table>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
