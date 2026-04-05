<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Validation Error" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="main-content">
    <div class="top-bar">
        <h2>Validation Error</h2>
        <div class="user-info"></div>
    </div>
    <div class="content-area">
        <div class="card" style="border-left: 4px solid #ef6c00;">
            <h3 style="color: #ef6c00;">Validation Failed</h3>
            <ul style="margin-top: 12px;">
                <c:forEach var="err" items="${errors}">
                    <li style="margin-bottom: 4px;"><c:out value="${err}" /></li>
                </c:forEach>
            </ul>
            <a href="javascript:history.back()" class="btn btn-primary" style="margin-top: 16px;">Go Back</a>
        </div>
    </div>

<%@ include file="../layout/footer.jsp" %>
