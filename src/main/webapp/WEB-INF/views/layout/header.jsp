<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="robots" content="noindex, nofollow">
    <meta name="ctx" content="${pageContext.request.contextPath}" />
    <title>Finvanta CBS - <c:out value="${pageTitle}" default="Banking System" /></title>
    <%-- CBS Tier-1: Favicon per bank branding standards. SVG for all modern browsers.
         ICO fallback removed — the .ico file format requires binary generation tooling
         that cannot be committed as a text file. SVG is supported by Chrome 80+,
         Firefox 41+, Edge 80+, Safari 15.4+ — covers all CBS branch workstations.
         Browsers that don't support SVG favicons will show the default browser icon. --%>
    <link rel="icon" type="image/svg+xml" href="${pageContext.request.contextPath}/img/favicon.svg">
    <!-- Offline Vendor CSS -->
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/bootstrap.min.css">
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/bootstrap-icons.css">
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/datatables.min.css">
    <!-- Finvanta Theme (must load after Bootstrap to override) -->
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/finvanta-theme.css">
</head>
<%-- CBS Tier-1: data-fv-session-timeout syncs the JS session countdown with
     the server-side session timeout (server.servlet.session.timeout). Without this,
     the JS uses a hardcoded 1800s fallback which diverges from prod (900s/15m). --%>
<body data-fv-session-timeout="${sessionTimeoutSeconds}">
<%-- CBS Tier-1: Print-only header for branch file maintenance printouts.
     Hidden on screen, visible only in @media print. Shows bank name + timestamp. --%>
<div class="fv-print-header">
    <h2>FINVANTA — Core Banking System</h2>
    <small><c:out value="${pageTitle}" default="" /> | Branch: <c:out value="${userBranchCode}" default="--" /> | Printed by: <c:out value="${pageContext.request.userPrincipal.name}" default="" /> | <span id="fvPrintTimestamp"></span></small>
    <script>document.getElementById('fvPrintTimestamp').textContent='Printed: '+new Date().toLocaleString('en-IN',{day:'2-digit',month:'short',year:'numeric',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false});</script>
</div>
