<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Error" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="main-content">
    <div class="top-bar">
        <h2>System Error</h2>
        <div class="user-info"></div>
    </div>
    <div class="content-area">
        <div class="card" style="border-left: 4px solid #c62828;">
            <h3 style="color: #c62828;">Unexpected Error</h3>
            <p><c:out value="${errorMessage}" /></p>
            <a href="${pageContext.request.contextPath}/dashboard" class="btn btn-primary" style="margin-top: 16px;">Go to Dashboard</a>
        </div>
    </div>

<%@ include file="../layout/footer.jsp" %>
