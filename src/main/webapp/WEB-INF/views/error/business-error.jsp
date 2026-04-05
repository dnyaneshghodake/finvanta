<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Error" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <div class="fv-card">
        <div class="card-header text-danger"><i class="bi bi-exclamation-triangle"></i> Business Error</div>
        <div class="card-body">
            <p><strong>Error Code:</strong> <c:out value="${errorCode}" /></p>
            <p class="mt-2"><c:out value="${errorMessage}" /></p>
            <a href="javascript:history.back()" class="btn btn-fv-primary mt-3">Go Back</a>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
