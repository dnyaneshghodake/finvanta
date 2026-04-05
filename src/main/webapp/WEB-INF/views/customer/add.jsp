<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Add Customer" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="main-content">
    <div class="top-bar">
        <h2>Add Customer</h2>
        <div class="user-info">
            <a href="${pageContext.request.contextPath}/customer/list">Back</a>
        </div>
    </div>
    <div class="content-area">
        <div class="card">
            <h3>Customer Registration</h3>
            <form method="post" action="${pageContext.request.contextPath}/customer/add">
                <div class="form-row">
                    <div class="form-group">
                        <label>Customer Number *</label>
                        <input type="text" name="customerNumber" required />
                    </div>
                    <div class="form-group">
                        <label>Customer Type</label>
                        <select name="customerType">
                            <option value="INDIVIDUAL">Individual</option>
                            <option value="CORPORATE">Corporate</option>
                        </select>
                    </div>
                </div>
                <div class="form-row">
                    <div class="form-group">
                        <label>First Name *</label>
                        <input type="text" name="firstName" required />
                    </div>
                    <div class="form-group">
                        <label>Last Name *</label>
                        <input type="text" name="lastName" required />
                    </div>
                </div>
                <div class="form-row">
                    <div class="form-group">
                        <label>Date of Birth</label>
                        <input type="date" name="dateOfBirth" />
                    </div>
                    <div class="form-group">
                        <label>PAN Number</label>
                        <input type="text" name="panNumber" maxlength="10" pattern="[A-Z]{5}[0-9]{4}[A-Z]{1}" placeholder="ABCDE1234F" />
                    </div>
                </div>
                <div class="form-row">
                    <div class="form-group">
                        <label>Aadhaar Number</label>
                        <input type="text" name="aadhaarNumber" maxlength="12" pattern="[0-9]{12}" />
                    </div>
                    <div class="form-group">
                        <label>Mobile Number *</label>
                        <input type="text" name="mobileNumber" required maxlength="15" />
                    </div>
                </div>
                <div class="form-group">
                    <label>Email</label>
                    <input type="email" name="email" />
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
                <div class="form-row">
                    <div class="form-group">
                        <label>Branch *</label>
                        <select name="branchId" required>
                            <c:forEach var="branch" items="${branches}">
                                <option value="${branch.id}"><c:out value="${branch.branchCode}" /> - <c:out value="${branch.branchName}" /></option>
                            </c:forEach>
                        </select>
                    </div>
                    <div class="form-group">
                        <label>CIBIL Score</label>
                        <input type="number" name="cibilScore" min="300" max="900" />
                    </div>
                </div>
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                <button type="submit" class="btn btn-primary" style="margin-top: 12px;">Add Customer</button>
            </form>
        </div>
    </div>

<%@ include file="../layout/footer.jsp" %>
