<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Error" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <ul class="fv-breadcrumb">
        <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
        <li class="active">Error</li>
    </ul>
    <div class="fv-card">
        <div class="card-header text-danger"><i class="bi bi-exclamation-octagon"></i> System Error</div>
        <div class="card-body">
            <p><c:out value="${errorMessage}" /></p>
            <a href="${pageContext.request.contextPath}/dashboard" class="btn btn-fv-primary mt-3">Go to Dashboard</a>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
