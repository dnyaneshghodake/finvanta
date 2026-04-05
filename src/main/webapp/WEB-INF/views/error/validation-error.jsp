<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Validation Error" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <div class="fv-card">
        <div class="card-header text-warning"><i class="bi bi-exclamation-circle"></i> Validation Failed</div>
        <div class="card-body">
            <ul class="mt-2">
                <c:forEach var="err" items="${errors}">
                    <li class="mb-1"><c:out value="${err}" /></li>
                </c:forEach>
            </ul>
            <a href="javascript:history.back()" class="btn btn-fv-primary mt-3">Go Back</a>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
