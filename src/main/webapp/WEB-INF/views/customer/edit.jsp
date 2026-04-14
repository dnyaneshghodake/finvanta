<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Edit Customer" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <c:if test="${not empty error}">
        <div class="fv-alert alert alert-danger"><c:out value="${error}" /></div>
    </c:if>
    <div class="fv-card">
        <div class="card-header"><i class="bi bi-pencil-square"></i> Edit Customer &mdash; <c:out value="${customer.customerNumber}" /> <a href="${pageContext.request.contextPath}/customer/view/${customer.id}" class="btn btn-sm btn-outline-secondary float-end"><i class="bi bi-x-circle"></i> Cancel</a></div>
        <div class="card-body">
            <form method="post" action="${pageContext.request.contextPath}/customer/edit/${customer.id}" class="fv-form">
                <!-- Immutable fields (read-only per RBI KYC norms) — PII masked per RBI/UIDAI -->
                <div class="row mb-3">
                    <div class="col-md-4"><label class="form-label">Customer Number</label><input type="text" class="form-control" value="<c:out value='${customer.customerNumber}'/>" disabled /></div>
                    <div class="col-md-4"><label class="form-label">PAN Number</label><input type="text" class="form-control" value="<c:out value='${maskedPan}'/>" disabled /></div>
                    <div class="col-md-4"><label class="form-label">Aadhaar Number</label><input type="text" class="form-control" value="<c:out value='${maskedAadhaar}'/>" disabled /></div>
                </div>
                <hr />
                <!-- Mutable fields -->
                <div class="row mb-3">
                    <div class="col-md-3"><label class="form-label">Customer Type *</label><select name="customerType" class="form-select" required><option value="INDIVIDUAL" ${customer.customerType == 'INDIVIDUAL' ? 'selected' : ''}>Individual</option><option value="JOINT" ${customer.customerType == 'JOINT' ? 'selected' : ''}>Joint</option><option value="HUF" ${customer.customerType == 'HUF' ? 'selected' : ''}>HUF</option><option value="PARTNERSHIP" ${customer.customerType == 'PARTNERSHIP' ? 'selected' : ''}>Partnership</option><option value="COMPANY" ${customer.customerType == 'COMPANY' ? 'selected' : ''}>Company</option><option value="TRUST" ${customer.customerType == 'TRUST' ? 'selected' : ''}>Trust</option><option value="NRI" ${customer.customerType == 'NRI' ? 'selected' : ''}>NRI</option><option value="MINOR" ${customer.customerType == 'MINOR' ? 'selected' : ''}>Minor</option><option value="GOVERNMENT" ${customer.customerType == 'GOVERNMENT' ? 'selected' : ''}>Government</option></select></div>
                    <div class="col-md-3"><label class="form-label">Branch *</label>
                        <c:choose>
                            <c:when test="${pageContext.request.isUserInRole('ROLE_ADMIN')}">
                                <select name="branchId" class="form-select" required><c:forEach var="branch" items="${branches}"><option value="${branch.id}" ${branch.id == customer.branch.id ? 'selected' : ''}><c:out value="${branch.branchCode}" /> - <c:out value="${branch.branchName}" /></option></c:forEach></select>
                            </c:when>
                            <c:otherwise>
                                <input type="text" class="form-control" value="${customer.branch.branchCode} - ${customer.branch.branchName}" disabled />
                                <input type="hidden" name="branchId" value="${customer.branch.id}" />
                            </c:otherwise>
                        </c:choose>
                    </div>
                    <div class="col-md-3"><label class="form-label">KYC Risk Category</label><select name="kycRiskCategory" class="form-select"><option value="LOW" ${customer.kycRiskCategory == 'LOW' ? 'selected' : ''}>Low</option><option value="MEDIUM" ${customer.kycRiskCategory == 'MEDIUM' ? 'selected' : ''}>Medium</option><option value="HIGH" ${customer.kycRiskCategory == 'HIGH' ? 'selected' : ''}>High</option></select></div>
                    <div class="col-md-3"><label class="form-label">PEP</label><div class="form-check mt-2"><input type="hidden" name="_pep" value="on" /><input type="checkbox" name="pep" value="true" class="form-check-input" id="pepEdit" ${customer.pep ? 'checked' : ''} /><label class="form-check-label" for="pepEdit">Politically Exposed Person</label></div></div>
                </div>
                <h6 class="text-muted border-bottom pb-1 mb-3"><i class="bi bi-person"></i> Personal Details &amp; Demographics</h6>
                <div class="row mb-3">
                    <div class="col-md-3"><label class="form-label">First Name *</label><input type="text" name="firstName" class="form-control" value="<c:out value='${customer.firstName}'/>" required maxlength="100" /></div>
                    <div class="col-md-3"><label class="form-label">Last Name *</label><input type="text" name="lastName" class="form-control" value="<c:out value='${customer.lastName}'/>" required maxlength="100" /></div>
                    <div class="col-md-2"><label class="form-label">Gender *</label><select name="gender" class="form-select" required><option value="">--</option><option value="M" ${customer.gender == 'M' ? 'selected' : ''}>Male</option><option value="F" ${customer.gender == 'F' ? 'selected' : ''}>Female</option><option value="T" ${customer.gender == 'T' ? 'selected' : ''}>Transgender</option></select></div>
                    <div class="col-md-2"><label class="form-label">Date of Birth</label><input type="date" name="dateOfBirth" class="form-control" value="${customer.dateOfBirth}" /></div>
                    <div class="col-md-2"><label class="form-label">Marital Status</label><select name="maritalStatus" class="form-select"><option value="">--</option><option value="SINGLE" ${customer.maritalStatus == 'SINGLE' ? 'selected' : ''}>Single</option><option value="MARRIED" ${customer.maritalStatus == 'MARRIED' ? 'selected' : ''}>Married</option><option value="DIVORCED" ${customer.maritalStatus == 'DIVORCED' ? 'selected' : ''}>Divorced</option><option value="WIDOWED" ${customer.maritalStatus == 'WIDOWED' ? 'selected' : ''}>Widowed</option></select></div>
                </div>
                <div class="row mb-3">
                    <div class="col-md-4"><label class="form-label">Father's Name</label><input type="text" name="fatherName" class="form-control" value="<c:out value='${customer.fatherName}'/>" maxlength="200" /></div>
                    <div class="col-md-4"><label class="form-label">Mother's Name</label><input type="text" name="motherName" class="form-control" value="<c:out value='${customer.motherName}'/>" maxlength="200" /></div>
                    <div class="col-md-4"><label class="form-label">Spouse Name</label><input type="text" name="spouseName" class="form-control" value="<c:out value='${customer.spouseName}'/>" maxlength="200" /></div>
                </div>
                <div class="row mb-3">
                    <div class="col-md-3"><label class="form-label">Nationality</label><select name="nationality" class="form-select"><option value="INDIAN" ${customer.nationality == 'INDIAN' ? 'selected' : ''}>Indian</option><option value="NRI" ${customer.nationality == 'NRI' ? 'selected' : ''}>NRI</option><option value="PIO" ${customer.nationality == 'PIO' ? 'selected' : ''}>PIO</option><option value="OCI" ${customer.nationality == 'OCI' ? 'selected' : ''}>OCI</option><option value="FOREIGN" ${customer.nationality == 'FOREIGN' ? 'selected' : ''}>Foreign</option></select></div>
                    <div class="col-md-3"><label class="form-label">Occupation</label><select name="occupationCode" class="form-select"><option value="">--</option><option value="SALARIED_PRIVATE" ${customer.occupationCode == 'SALARIED_PRIVATE' ? 'selected' : ''}>Salaried (Pvt)</option><option value="SALARIED_GOVT" ${customer.occupationCode == 'SALARIED_GOVT' ? 'selected' : ''}>Salaried (Govt)</option><option value="BUSINESS" ${customer.occupationCode == 'BUSINESS' ? 'selected' : ''}>Business</option><option value="PROFESSIONAL" ${customer.occupationCode == 'PROFESSIONAL' ? 'selected' : ''}>Professional</option><option value="RETIRED" ${customer.occupationCode == 'RETIRED' ? 'selected' : ''}>Retired</option><option value="STUDENT" ${customer.occupationCode == 'STUDENT' ? 'selected' : ''}>Student</option><option value="OTHER" ${customer.occupationCode == 'OTHER' ? 'selected' : ''}>Other</option></select></div>
                    <div class="col-md-3"><label class="form-label">Annual Income Band</label><select name="annualIncomeBand" class="form-select"><option value="">--</option><option value="BELOW_1L" ${customer.annualIncomeBand == 'BELOW_1L' ? 'selected' : ''}>&lt;1L</option><option value="1L_TO_5L" ${customer.annualIncomeBand == '1L_TO_5L' ? 'selected' : ''}>1-5L</option><option value="5L_TO_10L" ${customer.annualIncomeBand == '5L_TO_10L' ? 'selected' : ''}>5-10L</option><option value="10L_TO_25L" ${customer.annualIncomeBand == '10L_TO_25L' ? 'selected' : ''}>10-25L</option><option value="25L_TO_1CR" ${customer.annualIncomeBand == '25L_TO_1CR' ? 'selected' : ''}>25L-1Cr</option><option value="ABOVE_1CR" ${customer.annualIncomeBand == 'ABOVE_1CR' ? 'selected' : ''}>&gt;1Cr</option></select></div>
                    <div class="col-md-3"><label class="form-label">CIBIL Score</label><input type="number" name="cibilScore" class="form-control" value="${customer.cibilScore}" min="300" max="900" /></div>
                </div>

                <h6 class="text-muted border-bottom pb-1 mb-3"><i class="bi bi-shield-check"></i> KYC Document Details</h6>
                <div class="row mb-3">
                    <div class="col-md-3"><label class="form-label">Photo ID Type</label><select name="photoIdType" class="form-select"><option value="">--</option><option value="PASSPORT" ${customer.photoIdType == 'PASSPORT' ? 'selected' : ''}>Passport</option><option value="VOTER_ID" ${customer.photoIdType == 'VOTER_ID' ? 'selected' : ''}>Voter ID</option><option value="DRIVING_LICENSE" ${customer.photoIdType == 'DRIVING_LICENSE' ? 'selected' : ''}>DL</option><option value="PAN_CARD" ${customer.photoIdType == 'PAN_CARD' ? 'selected' : ''}>PAN Card</option><option value="AADHAAR" ${customer.photoIdType == 'AADHAAR' ? 'selected' : ''}>Aadhaar</option></select></div>
                    <div class="col-md-3"><label class="form-label">Photo ID Number</label><input type="text" name="photoIdNumber" class="form-control" value="<c:out value='${customer.photoIdNumber}'/>" maxlength="30" /></div>
                    <div class="col-md-3"><label class="form-label">Address Proof Type</label><select name="addressProofType" class="form-select"><option value="">--</option><option value="PASSPORT" ${customer.addressProofType == 'PASSPORT' ? 'selected' : ''}>Passport</option><option value="VOTER_ID" ${customer.addressProofType == 'VOTER_ID' ? 'selected' : ''}>Voter ID</option><option value="UTILITY_BILL" ${customer.addressProofType == 'UTILITY_BILL' ? 'selected' : ''}>Utility Bill</option><option value="AADHAAR" ${customer.addressProofType == 'AADHAAR' ? 'selected' : ''}>Aadhaar</option></select></div>
                    <div class="col-md-3"><label class="form-label">Address Proof No.</label><input type="text" name="addressProofNumber" class="form-control" value="<c:out value='${customer.addressProofNumber}'/>" maxlength="30" /></div>
                </div>
                <div class="row mb-3">
                    <div class="col-md-3"><label class="form-label">KYC Mode</label><select name="kycMode" class="form-select"><option value="">--</option><option value="IN_PERSON" ${customer.kycMode == 'IN_PERSON' ? 'selected' : ''}>In-Person</option><option value="VIDEO_KYC" ${customer.kycMode == 'VIDEO_KYC' ? 'selected' : ''}>Video KYC</option><option value="DIGITAL_KYC" ${customer.kycMode == 'DIGITAL_KYC' ? 'selected' : ''}>Digital KYC</option><option value="CKYC_DOWNLOAD" ${customer.kycMode == 'CKYC_DOWNLOAD' ? 'selected' : ''}>CKYC Download</option></select></div>
                </div>

                <h6 class="text-muted border-bottom pb-1 mb-3"><i class="bi bi-telephone"></i> Contact &amp; Correspondence Address</h6>
                <div class="row mb-3">
                    <div class="col-md-4"><label class="form-label">Mobile Number *</label><input type="text" name="mobileNumber" class="form-control" value="<c:out value='${customer.mobileNumber}'/>" required maxlength="10" pattern="[6-9][0-9]{9}" title="10-digit mobile" inputmode="numeric" onkeypress="return event.charCode>=48&&event.charCode<=57" /></div>
                    <div class="col-md-4"><label class="form-label">Email</label><input type="email" name="email" class="form-control" value="<c:out value='${customer.email}'/>" maxlength="200" /></div>
                </div>
                <div class="mb-2"><label class="form-label">Correspondence Address</label><textarea name="address" class="form-control" rows="2" maxlength="500"><c:out value="${customer.address}"/></textarea></div>
                <div class="row mb-3">
                    <div class="col-md-4"><input type="text" name="city" class="form-control" value="<c:out value='${customer.city}'/>" maxlength="100" placeholder="City" /></div>
                    <div class="col-md-4"><input type="text" name="state" class="form-control" value="<c:out value='${customer.state}'/>" maxlength="100" placeholder="State" /></div>
                    <div class="col-md-4"><input type="text" name="pinCode" class="form-control" value="<c:out value='${customer.pinCode}'/>" maxlength="6" pattern="[0-9]{6}" title="6-digit PIN" inputmode="numeric" onkeypress="return event.charCode>=48&&event.charCode<=57" placeholder="PIN Code" /></div>
                </div>

                <h6 class="text-muted border-bottom pb-1 mb-3"><i class="bi bi-geo-alt"></i> Permanent Address (CKYC/CERSAI)</h6>
                <div class="row mb-2">
                    <div class="col-md-12"><div class="form-check"><input type="hidden" name="_addressSameAsPermanent" value="on" /><input type="checkbox" name="addressSameAsPermanent" value="true" class="form-check-input" id="addrSame" ${customer.addressSameAsPermanent ? 'checked' : ''} onchange="togglePermanentAddr();" /><label class="form-check-label" for="addrSame">Same as correspondence address</label></div></div>
                </div>
                <div id="permanentAddrBlock" style="${customer.addressSameAsPermanent ? 'display:none;' : ''}">
                    <div class="mb-2"><textarea name="permanentAddress" class="form-control" rows="2" maxlength="500" placeholder="Permanent address"><c:out value="${customer.permanentAddress}"/></textarea></div>
                    <div class="row mb-3">
                        <div class="col-md-3"><input type="text" name="permanentCity" class="form-control" value="<c:out value='${customer.permanentCity}'/>" maxlength="100" placeholder="City" /></div>
                        <div class="col-md-3"><input type="text" name="permanentState" class="form-control" value="<c:out value='${customer.permanentState}'/>" maxlength="100" placeholder="State" /></div>
                        <div class="col-md-3"><input type="text" name="permanentPinCode" class="form-control" value="<c:out value='${customer.permanentPinCode}'/>" maxlength="6" pattern="[0-9]{6}" title="6-digit PIN" inputmode="numeric" onkeypress="return event.charCode>=48&&event.charCode<=57" placeholder="PIN Code" /></div>
                        <div class="col-md-3"><select name="permanentCountry" class="form-select"><option value="INDIA" ${customer.permanentCountry == 'INDIA' ? 'selected' : ''}>India</option><option value="OTHER" ${customer.permanentCountry != 'INDIA' and not empty customer.permanentCountry ? 'selected' : ''}>Other</option></select></div>
                    </div>
                </div>

                <h6 class="text-muted border-bottom pb-1 mb-3"><i class="bi bi-currency-rupee"></i> Income &amp; Exposure (RBI Norms)</h6>
                <div class="row mb-3">
                    <div class="col-md-3"><label class="form-label">Monthly Income (INR)</label><input type="number" name="monthlyIncome" class="form-control" value="${customer.monthlyIncome}" step="0.01" min="0" /></div>
                    <div class="col-md-3"><label class="form-label">Max Borrowing Limit (INR)</label><input type="number" name="maxBorrowingLimit" class="form-control" value="${customer.maxBorrowingLimit}" step="0.01" min="0" /></div>
                    <div class="col-md-3"><label class="form-label">Employment Type</label><select name="employmentType" class="form-select"><option value="">-- Select --</option><option value="SALARIED" ${customer.employmentType == 'SALARIED' ? 'selected' : ''}>Salaried</option><option value="SELF_EMPLOYED" ${customer.employmentType == 'SELF_EMPLOYED' ? 'selected' : ''}>Self Employed</option><option value="BUSINESS" ${customer.employmentType == 'BUSINESS' ? 'selected' : ''}>Business</option><option value="RETIRED" ${customer.employmentType == 'RETIRED' ? 'selected' : ''}>Retired</option><option value="OTHER" ${customer.employmentType == 'OTHER' ? 'selected' : ''}>Other</option></select></div>
                    <div class="col-md-3"><label class="form-label">Employer Name</label><input type="text" name="employerName" class="form-control" value="<c:out value='${customer.employerName}'/>" /></div>
                </div>

                <h6 class="text-muted border-bottom pb-1 mb-3"><i class="bi bi-people"></i> Nominee Details (RBI Nomination Guidelines)</h6>
                <div class="row mb-3">
                    <div class="col-md-4"><label class="form-label">Nominee Date of Birth</label><input type="date" name="nomineeDob" class="form-control" value="${customer.nomineeDob}" /></div>
                    <div class="col-md-4"><label class="form-label">Nominee Guardian Name</label><input type="text" name="nomineeGuardianName" class="form-control" value="<c:out value='${customer.nomineeGuardianName}'/>" maxlength="200" /><small class="text-muted">Required if nominee is a minor</small></div>
                    <div class="col-md-4"><label class="form-label">Nominee Address</label><input type="text" name="nomineeAddress" class="form-control" value="<c:out value='${customer.nomineeAddress}'/>" maxlength="500" /></div>
                </div>
                <input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}" />
                <button type="submit" class="btn btn-sm btn-fv-primary mt-2"><i class="bi bi-check-circle"></i> Save Changes</button>
            </form>
        </div>
    </div>
</div>

<script>
function togglePermanentAddr() {
    var block = document.getElementById('permanentAddrBlock');
    var checked = document.getElementById('addrSame').checked;
    block.style.display = checked ? 'none' : '';
}
</script>

<%@ include file="../layout/footer.jsp" %>