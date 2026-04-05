<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<c:set var="pageTitle" value="Business Calendar" />
<%@ include file="../layout/header.jsp" %>
<%@ include file="../layout/sidebar.jsp" %>

<div class="fv-main">
    <div class="fv-card">
        <div class="card-header">Business Calendar — EOD Status</div>
        <div class="card-body">
            <table class="table fv-table fv-datatable">
                <thead>
                    <tr>
                        <th>Business Date</th>
                        <th>Holiday</th>
                        <th>EOD Complete</th>
                        <th>Locked</th>
                    </tr>
                </thead>
                <tbody>
                    <c:forEach var="cal" items="${calendarDates}">
                        <tr>
                            <td><c:out value="${cal.businessDate}" /></td>
                            <td>
                                <c:choose>
                                    <c:when test="${cal.holiday}"><span class="fv-badge fv-badge-rejected">Holiday</span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-active">Working</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td>
                                <c:choose>
                                    <c:when test="${cal.eodComplete}"><span class="fv-badge fv-badge-active">Complete</span></c:when>
                                    <c:otherwise><span class="fv-badge fv-badge-pending">Pending</span></c:otherwise>
                                </c:choose>
                            </td>
                            <td>
                                <c:choose>
                                    <c:when test="${cal.locked}"><span class="fv-badge fv-badge-rejected">Locked</span></c:when>
                                    <c:otherwise>—</c:otherwise>
                                </c:choose>
                            </td>
                        </tr>
                    </c:forEach>
                    <c:if test="${empty calendarDates}">
                        <tr><td colspan="4" class="text-center text-muted">No calendar dates configured</td></tr>
                    </c:if>
                </tbody>
            </table>
        </div>
    </div>
</div>

<%@ include file="../layout/footer.jsp" %>