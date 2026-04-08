<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Edit Customer" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <div class="fv-card">
        <div class="card-header">Edit Customer &mdash; <c:out value="${customer.customerNumber}" /> <a href="${pageContext.request.contextPath}/customer/view/${customer.id}" class="btn btn-sm btn-outline-secondary float-end"><i class="bi bi-x-circle"></i> Cancel</a></div>
        <div class="card-body">
            <form method="post" action="${pageContext.request.contextPath}/customer/edit/${customer.id}" class="fv-form">
                <!-- Immutable fields (read-only per RBI KYC norms) -->
                <div class="row mb-3">
                    <div class="col-md-4"><label class="form-label">Customer Number</label><input type="text" class="form-control" value="<c:out value='${customer.customerNumber}'/>" disabled /></div>
                    <div class="col-md-4"><label class="form-label">PAN Number</label><input type="text" class="form-control" value="<c:out value='${customer.panNumber}'/>" disabled /></div>
                    <div class="col-md-4"><label class="form-label">Aadhaar Number</label><input type="text" class="form-control" value="<c:out value='${customer.aadhaarNumber}'/>" disabled /></div>
                </div>
                <hr />
                <!-- Mutable fields -->
                <div class="row mb-3">
                    <div class="col-md-6"><label class="form-label">Customer Type *</label><select name="customerType" class="form-select" required><option value="INDIVIDUAL" ${customer.customerType == 'INDIVIDUAL' ? 'selected' : ''}>Individual</option><option value="CORPORATE" ${customer.customerType == 'CORPORATE' ? 'selected' : ''}>Corporate</option></select></div>
                    <div class="col-md-6"><label class="form-label">Branch *</label><select name="branchId" class="form-select" required><c:forEach var="branch" items="${branches}"><option value="${branch.id}" ${branch.id == customer.branch.id ? 'selected' : ''}><c:out value="${branch.branchCode}" /> - <c:out value="${branch.branchName}" /></option></c:forEach></select></div>
                </div>
                <div class="row mb-3">
                    <div class="col-md-6"><label class="form-label">First Name *</label><input type="text" name="firstName" class="form-control" value="<c:out value='${customer.firstName}'/>" required /></div>
                    <div class="col-md-6"><label class="form-label">Last Name *</label><input type="text" name="lastName" class="form-control" value="<c:out value='${customer.lastName}'/>" required /></div>
                </div>
                <div class="row mb-3">
                    <div class="col-md-6"><label class="form-label">Date of Birth</label><input type="date" name="dateOfBirth" class="form-control" value="${customer.dateOfBirth}" /></div>
                    <div class="col-md-6"><label class="form-label">Mobile Number *</label><input type="text" name="mobileNumber" class="form-control" value="<c:out value='${customer.mobileNumber}'/>" required maxlength="15" /></div>
                </div>
                <div class="mb-3"><label class="form-label">Email</label><input type="email" name="email" class="form-control" value="<c:out value='${customer.email}'/>" /></div>
                <div class="mb-3"><label class="form-label">Address</label><textarea name="address" class="form-control" rows="2"><c:out value="${customer.address}"/></textarea></div>
                <div class="row mb-3">
                    <div class="col-md-4"><label class="form-label">City</label><input type="text" name="city" class="form-control" value="<c:out value='${customer.city}'/>" /></div>
                    <div class="col-md-4"><label class="form-label">State</label><input type="text" name="state" class="form-control" value="<c:out value='${customer.state}'/>" /></div>
                    <div class="col-md-4"><label class="form-label">PIN Code</label><input type="text" name="pinCode" class="form-control" value="<c:out value='${customer.pinCode}'/>" maxlength="6" /></div>
                </div>
                <div class="row mb-3">
                    <div class="col-md-6"><label class="form-label">CIBIL Score</label><input type="number" name="cibilScore" class="form-control" value="${customer.cibilScore}" min="300" max="900" /></div>
                </div>
                <hr />
                <h6 class="mb-3">Income &amp; Exposure (RBI Exposure Norms)</h6>
                <div class="row mb-3">
                    <div class="col-md-3"><label class="form-label">Monthly Income (INR)</label><input type="number" name="monthlyIncome" class="form-control" value="${customer.monthlyIncome}" step="0.01" min="0" /></div>
                    <div class="col-md-3"><label class="form-label">Max Borrowing Limit (INR)</label><input type="number" name="maxBorrowingLimit" class="form-control" value="${customer.maxBorrowingLimit}" step="0.01" min="0" /></div>
                    <div class="col-md-3"><label class="form-label">Employment Type</label><select name="employmentType" class="form-select"><option value="">-- Select --</option><option value="SALARIED" ${customer.employmentType == 'SALARIED' ? 'selected' : ''}>Salaried</option><option value="SELF_EMPLOYED" ${customer.employmentType == 'SELF_EMPLOYED' ? 'selected' : ''}>Self Employed</option><option value="BUSINESS" ${customer.employmentType == 'BUSINESS' ? 'selected' : ''}>Business</option><option value="RETIRED" ${customer.employmentType == 'RETIRED' ? 'selected' : ''}>Retired</option><option value="OTHER" ${customer.employmentType == 'OTHER' ? 'selected' : ''}>Other</option></select></div>
                    <div class="col-md-3"><label class="form-label">Employer Name</label><input type="text" name="employerName" class="form-control" value="<c:out value='${customer.employerName}'/>" /></div>
                </div>
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                <button type="submit" class="btn btn-sm btn-fv-primary mt-2"><i class="bi bi-check-circle"></i> Save Changes</button>
            </form>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>