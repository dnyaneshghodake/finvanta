<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="robots" content="noindex, nofollow">
    <title>Finvanta CBS - <c:out value="${pageTitle}" default="Banking System" /></title>
    <!-- Offline Vendor CSS -->
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/bootstrap.min.css">
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/bootstrap-icons.css">
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/datatables.min.css">
    <!-- Finvanta Theme (must load after Bootstrap to override) -->
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/finvanta-theme.css">
</head>
<body>
