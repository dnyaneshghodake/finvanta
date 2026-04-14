<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Customers" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <c:if test="${not empty success}">
        <div class="fv-alert alert alert-success"><c:out value="${success}" /></div>
    </c:if>
    <c:if test="${not empty error}">
        <div class="fv-alert alert alert-danger"><c:out value="${error}" /></div>
    </c:if>
    <div class="fv-card">
        <div class="card-header">Customer List <a href="${pageContext.request.contextPath}/customer/add" class="btn btn-sm btn-fv-primary float-end"><i class="bi bi-plus-circle"></i> Add Customer</a></div>
        <div class="card-body">
            <!-- CBS: Customer search per Finacle CIF_SEARCH -->
            <form method="get" action="${pageContext.request.contextPath}/customer/search" class="row g-2 mb-3">
                <div class="col-auto">
                    <input type="text" name="q" class="form-control form-control-sm" placeholder="Search by name, CIF, mobile, PAN..." value="<c:out value='${searchQuery}'/>" minlength="2" style="width:320px;" />
                </div>
                <div class="col-auto">
                    <button type="submit" class="btn btn-sm btn-fv-primary"><i class="bi bi-search"></i> Search</button>
                </div>
                <c:if test="${not empty searchQuery}">
                <div class="col-auto">
                    <a href="${pageContext.request.contextPath}/customer/list" class="btn btn-sm btn-outline-secondary">Clear</a>
                </div>
                </c:if>
            </form>
            <div class="table-responsive">
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr>
                        <th>Customer No.</th>
                        <th>Name</th>
                        <th>PAN</th>
                        <th>Mobile</th>
                        <th>KYC</th>
                        <th>CIBIL</th>
                        <th>Branch</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="cust" items="${customers}">
                        <tr>
                            <td><c:out value="${cust.customerNumber}" /></td>
                            <td><c:out value="${cust.fullName}" /></td>
                            <%-- CBS: PII masked in list view per RBI IT Governance / UIDAI.
                                 Full PAN/Mobile are decrypted but only last 4 chars shown.
                                 Per Finacle CIF_LIST: PII is always masked in list views. --%>
                            <td><c:if test="${not empty cust.panNumber}"><c:choose><c:when test="${cust.panNumber.length() > 4}">XXXXXX<c:out value="${cust.panNumber.substring(cust.panNumber.length() - 4)}" /></c:when><c:otherwise>****</c:otherwise></c:choose></c:if></td>
                            <td><c:if test="${not empty cust.mobileNumber}"><c:choose><c:when test="${cust.mobileNumber.length() > 4}">XXXXXX<c:out value="${cust.mobileNumber.substring(cust.mobileNumber.length() - 4)}" /></c:when><c:otherwise>****</c:otherwise></c:choose></c:if></td>
                            <td>
                                <c:choose>
                                    <c:when test="${cust.kycVerified}"><span class="fv-badge fv-badge-active">Verified</span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-rejected">Pending</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td><c:out value="${cust.cibilScore}" /></td>
                            <td><c:out value="${cust.branch.branchCode}" /></td>
                            <td>
                                <a href="${pageContext.request.contextPath}/customer/view/${cust.id}" class="btn btn-sm btn-fv-primary"><i class="bi bi-eye"></i> View</a>
                            </td>
                        </tr>
                    </c:forEach>
                </tbody>
            </table>
            </div>
            <%-- CBS: Pagination controls per Finacle CIF_LIST / Temenos ENQUIRY --%>
            <c:if test="${not empty customerPage and customerPage.totalPages > 1}">
            <nav aria-label="Customer list pagination" class="mt-3">
                <div class="d-flex justify-content-between align-items-center">
                    <small class="text-muted">
                        Showing ${customerPage.number * customerPage.size + 1}–${customerPage.number * customerPage.size + customerPage.numberOfElements}
                        of ${customerPage.totalElements} customers (Page ${customerPage.number + 1} of ${customerPage.totalPages})
                    </small>
                    <ul class="pagination pagination-sm mb-0">
                        <c:set var="baseUrl" value="${not empty searchQuery ? '/customer/search?q='.concat(searchQuery).concat('&') : '/customer/list?'}" />
                        <li class="page-item ${customerPage.first ? 'disabled' : ''}">
                            <a class="page-link" href="${pageContext.request.contextPath}${baseUrl}page=0&size=${customerPage.size}"><i class="bi bi-chevron-double-left"></i></a>
                        </li>
                        <li class="page-item ${customerPage.first ? 'disabled' : ''}">
                            <a class="page-link" href="${pageContext.request.contextPath}${baseUrl}page=${customerPage.number - 1}&size=${customerPage.size}"><i class="bi bi-chevron-left"></i></a>
                        </li>
                        <c:forEach var="i" begin="${customerPage.number > 2 ? customerPage.number - 2 : 0}" end="${customerPage.number + 2 < customerPage.totalPages ? customerPage.number + 2 : customerPage.totalPages - 1}">
                            <li class="page-item ${i == customerPage.number ? 'active' : ''}">
                                <a class="page-link" href="${pageContext.request.contextPath}${baseUrl}page=${i}&size=${customerPage.size}">${i + 1}</a>
                            </li>
                        </c:forEach>
                        <li class="page-item ${customerPage.last ? 'disabled' : ''}">
                            <a class="page-link" href="${pageContext.request.contextPath}${baseUrl}page=${customerPage.number + 1}&size=${customerPage.size}"><i class="bi bi-chevron-right"></i></a>
                        </li>
                        <li class="page-item ${customerPage.last ? 'disabled' : ''}">
                            <a class="page-link" href="${pageContext.request.contextPath}${baseUrl}page=${customerPage.totalPages - 1}&size=${customerPage.size}"><i class="bi bi-chevron-double-right"></i></a>
                        </li>
                    </ul>
                </div>
            </nav>
            </c:if>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
