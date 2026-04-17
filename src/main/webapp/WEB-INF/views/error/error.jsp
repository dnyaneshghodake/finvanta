<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Error ${errorCode}" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <ul class="fv-breadcrumb">
        <li><a href="${pageContext.request.contextPath}/dashboard"><i class="bi bi-speedometer2"></i> Home</a></li>
        <li class="active">Error</li>
    </ul>
    <div class="fv-card">
        <div class="card-body text-center py-5">
            <div style="font-size:4rem;color:var(--fv-danger);margin-bottom:12px;">
                <c:choose>
                    <c:when test="${errorCode == '403'}"><i class="bi bi-shield-lock"></i></c:when>
                    <c:when test="${errorCode == '404'}"><i class="bi bi-question-circle"></i></c:when>
                    <c:otherwise><i class="bi bi-exclamation-triangle"></i></c:otherwise>
                </c:choose>
            </div>
            <h2 style="color:var(--fv-primary);font-size:20px;margin-bottom:8px;">
                <c:out value="${errorCode}" /> &mdash; <c:out value="${errorTitle}" default="Error" />
            </h2>
            <p class="text-muted" style="max-width:500px;margin:0 auto 24px;">
                <c:out value="${errorMessage}" default="An unexpected error occurred." />
            </p>
            <c:if test="${errorCode == '403'}">
                <p class="text-muted small">
                    Your current role: <span class="fv-badge fv-badge-pending"><c:out value="${pageContext.request.userPrincipal.name}" default="Unknown" /></span>
                    &mdash; Contact your administrator if you need access to this function.
                </p>
            </c:if>
            <a href="${pageContext.request.contextPath}/dashboard" class="btn btn-sm btn-fv-primary me-2">
                <i class="bi bi-house"></i> Dashboard
            </a>
            <button onclick="history.back()" class="btn btn-sm btn-outline-secondary">
                <i class="bi bi-arrow-left"></i> Go Back
            </button>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
