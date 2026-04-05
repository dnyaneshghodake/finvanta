<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Pending Approvals" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="main-content">
    <div class="top-bar">
        <h2>Pending Approvals (Maker-Checker)</h2>
        <div class="user-info"></div>
    </div>
    <div class="content-area">
        <c:if test="${not empty success}">
            <div class="alert alert-success"><c:out value="${success}" /></div>
        </c:if>
        <c:if test="${not empty error}">
            <div class="alert alert-error"><c:out value="${error}" /></div>
        </c:if>

        <div class="card">
            <h3>Items Pending Approval</h3>
            <table>
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
                                <form method="post" action="${pageContext.request.contextPath}/workflow/approve/${item.id}" style="display: inline;">
                                    <input type="hidden" name="remarks" value="Approved" />
                                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                    <button type="submit" class="btn btn-success btn-sm">Approve</button>
                                </form>
                                <form method="post" action="${pageContext.request.contextPath}/workflow/reject/${item.id}" style="display: inline;">
                                    <input type="hidden" name="remarks" value="Rejected" />
                                    <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                                    <button type="submit" class="btn btn-danger btn-sm" onclick="return confirm('Reject this item?')">Reject</button>
                                </form>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty pendingItems}">
                        <tr><td colspan="7" style="text-align: center; color: #999;">No pending approvals</td></tr>
                    </c:if>
                </tbody>
            </table>
        </div>
    </div>

<%@ include file="../layout/footer.jsp" %>
