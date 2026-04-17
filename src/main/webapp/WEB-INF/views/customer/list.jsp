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
                            <%-- CBS Tier-1 (Gap 6): PII masked via entity-level accessors per RBI IT Governance / UIDAI.
                                 Pre-masked values from Customer.getMaskedPan() / getMaskedMobile()
                                 — decrypted PAN/Mobile never reach the JSP template engine.
                                 Per Finacle CIF_LIST: PII is always masked in list views. --%>
                            <td><c:out value="${cust.maskedPan}" /></td>
                            <td><c:out value="${cust.maskedMobile}" /></td>
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
            <%-- CBS: Pagination controls per Finacle CIF_LIST / Temenos ENQUIRY.
                 Per RBI IT Governance / OWASP A7: searchQuery is URL-encoded via <c:url>/<c:param>
                 to prevent reflected XSS in pagination href attributes. --%>
            <c:if test="${not empty customerPage and customerPage.totalPages > 1}">
            <nav aria-label="Customer list pagination" class="mt-3">
                <div class="d-flex justify-content-between align-items-center">
                    <small class="text-muted">
                        Showing ${customerPage.number * customerPage.size + 1}–${customerPage.number * customerPage.size + customerPage.numberOfElements}
                        of ${customerPage.totalElements} customers (Page ${customerPage.number + 1} of ${customerPage.totalPages})
                    </small>
                    <ul class="pagination pagination-sm mb-0">
                        <li class="page-item ${customerPage.first ? 'disabled' : ''}">
                            <c:url var="firstPageUrl" value="${not empty searchQuery ? '/customer/search' : '/customer/list'}"><c:if test="${not empty searchQuery}"><c:param name="q" value="${searchQuery}" /></c:if><c:param name="page" value="0" /><c:param name="size" value="${customerPage.size}" /></c:url>
                            <a class="page-link" href="${firstPageUrl}"><i class="bi bi-chevron-double-left"></i></a>
                        </li>
                        <li class="page-item ${customerPage.first ? 'disabled' : ''}">
                            <c:url var="prevPageUrl" value="${not empty searchQuery ? '/customer/search' : '/customer/list'}"><c:if test="${not empty searchQuery}"><c:param name="q" value="${searchQuery}" /></c:if><c:param name="page" value="${customerPage.number - 1}" /><c:param name="size" value="${customerPage.size}" /></c:url>
                            <a class="page-link" href="${prevPageUrl}"><i class="bi bi-chevron-left"></i></a>
                        </li>
                        <c:forEach var="i" begin="${customerPage.number > 2 ? customerPage.number - 2 : 0}" end="${customerPage.number + 2 < customerPage.totalPages ? customerPage.number + 2 : customerPage.totalPages - 1}">
                            <li class="page-item ${i == customerPage.number ? 'active' : ''}">
                                <c:url var="pageUrl" value="${not empty searchQuery ? '/customer/search' : '/customer/list'}"><c:if test="${not empty searchQuery}"><c:param name="q" value="${searchQuery}" /></c:if><c:param name="page" value="${i}" /><c:param name="size" value="${customerPage.size}" /></c:url>
                                <a class="page-link" href="${pageUrl}">${i + 1}</a>
                            </li>
                        </c:forEach>
                        <li class="page-item ${customerPage.last ? 'disabled' : ''}">
                            <c:url var="nextPageUrl" value="${not empty searchQuery ? '/customer/search' : '/customer/list'}"><c:if test="${not empty searchQuery}"><c:param name="q" value="${searchQuery}" /></c:if><c:param name="page" value="${customerPage.number + 1}" /><c:param name="size" value="${customerPage.size}" /></c:url>
                            <a class="page-link" href="${nextPageUrl}"><i class="bi bi-chevron-right"></i></a>
                        </li>
                        <li class="page-item ${customerPage.last ? 'disabled' : ''}">
                            <c:url var="lastPageUrl" value="${not empty searchQuery ? '/customer/search' : '/customer/list'}"><c:if test="${not empty searchQuery}"><c:param name="q" value="${searchQuery}" /></c:if><c:param name="page" value="${customerPage.totalPages - 1}" /><c:param name="size" value="${customerPage.size}" /></c:url>
                            <a class="page-link" href="${lastPageUrl}"><i class="bi bi-chevron-double-right"></i></a>
                        </li>
                    </ul>
                </div>
            </nav>
            </c:if>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>
