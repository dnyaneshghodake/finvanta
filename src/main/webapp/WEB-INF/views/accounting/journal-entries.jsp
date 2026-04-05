<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<c:set var="pageTitle" value="Journal Entries" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="main-content">
    <div class="top-bar">
        <h2>Journal Entries</h2>
        <div class="user-info"></div>
    </div>
    <div class="content-area">
        <div class="card">
            <h3>Filter</h3>
            <form method="get" action="${pageContext.request.contextPath}/accounting/journal-entries" style="display: flex; gap: 12px; align-items: flex-end;">
                <div class="form-group" style="margin-bottom: 0;">
                    <label>From Date</label>
                    <input type="date" name="fromDate" value="${fromDate}" />
                </div>
                <div class="form-group" style="margin-bottom: 0;">
                    <label>To Date</label>
                    <input type="date" name="toDate" value="${toDate}" />
                </div>
                <button type="submit" class="btn btn-primary">Filter</button>
            </form>
        </div>

        <div class="card">
            <h3>Journal Entries</h3>
            <table>
                <thead>
                    <tr>
                        <th>Journal Ref</th>
                        <th>Value Date</th>
                        <th>Narration</th>
                        <th>Source</th>
                        <th class="text-right">Total Debit</th>
                        <th class="text-right">Total Credit</th>
                        <th>Posted</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="entry" items="${entries}">
                        <tr>
                            <td><c:out value="${entry.journalRef}" /></td>
                            <td><c:out value="${entry.valueDate}" /></td>
                            <td><c:out value="${entry.narration}" /></td>
                            <td><c:out value="${entry.sourceModule}" /> / <c:out value="${entry.sourceRef}" /></td>
                            <td class="text-right amount"><fmt:formatNumber value="${entry.totalDebit}" type="number" maxFractionDigits="2" /></td>
                            <td class="text-right amount"><fmt:formatNumber value="${entry.totalCredit}" type="number" maxFractionDigits="2" /></td>
                            <td>
                                <c:choose>
                                    <c:when test="${entry.posted}"><span class="badge badge-active">Yes</span></c:when>
                                    <c:otherwise><span class="badge badge-pending">No</span></c:otherwise>
                                </c:choose>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty entries}">
                        <tr><td colspan="7" style="text-align: center; color: #999;">No journal entries found</td></tr>
                    </c:if>
                </tbody>
            </table>
        </div>
    </div>

<%@ include file="../layout/footer.jsp" %>
