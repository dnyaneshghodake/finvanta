<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Add Branch" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="main-content">
    <div class="top-bar">
        <h2>Add Branch</h2>
        <div class="user-info">
            <a href="${pageContext.request.contextPath}/branch/list">Back</a>
        </div>
    </div>
    <div class="content-area">
        <div class="card">
            <h3>Branch Details</h3>
            <form method="post" action="${pageContext.request.contextPath}/branch/add">
                <div class="form-row">
                    <div class="form-group">
                        <label>Branch Code *</label>
                        <input type="text" name="branchCode" required maxlength="20" />
                    </div>
                    <div class="form-group">
                        <label>Branch Name *</label>
                        <input type="text" name="branchName" required />
                    </div>
                </div>
                <div class="form-row">
                    <div class="form-group">
                        <label>IFSC Code</label>
                        <input type="text" name="ifscCode" maxlength="11" />
                    </div>
                    <div class="form-group">
                        <label>Region</label>
                        <input type="text" name="region" />
                    </div>
                </div>
                <div class="form-group">
                    <label>Address</label>
                    <textarea name="address" rows="2"></textarea>
                </div>
                <div class="form-row">
                    <div class="form-group">
                        <label>City</label>
                        <input type="text" name="city" />
                    </div>
                    <div class="form-group">
                        <label>State</label>
                        <input type="text" name="state" />
                    </div>
                    <div class="form-group">
                        <label>PIN Code</label>
                        <input type="text" name="pinCode" maxlength="6" />
                    </div>
                </div>
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                <button type="submit" class="btn btn-primary" style="margin-top: 12px;">Add Branch</button>
            </form>
        </div>
    </div>

<%@ include file="../layout/footer.jsp" %>
