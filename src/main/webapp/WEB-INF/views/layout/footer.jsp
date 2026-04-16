</div><!-- end fv-main -->
<footer class="fv-footer">
    Finvanta Systems Pvt Ltd &copy; 2026 | Tier-1 CBS Platform | RBI Compliant
</footer>
<!-- Offline Vendor JS -->
<script src="${pageContext.request.contextPath}/js/jquery.min.js"></script>
<script src="${pageContext.request.contextPath}/js/bootstrap.bundle.min.js"></script>
<script src="${pageContext.request.contextPath}/js/datatables.min.js"></script>
<!-- Finvanta App JS -->
<script src="${pageContext.request.contextPath}/js/finvanta-app.js"></script>
<!-- CBS: Centralized Input Validation per Finacle FIELD_TYPE_MASTER / Temenos EB.VALIDATION.
     Auto-discovers data-fv-type attributes on all inputs and applies type-specific
     validation (keystroke filtering, pattern matching, cross-field min/max checks).
     Must load AFTER finvanta-app.js and Bootstrap (uses Bootstrap alert classes). -->
<script src="${pageContext.request.contextPath}/js/finvanta-validation.js"></script>
</body>
</html>
