package com.finvanta.repository;

import com.finvanta.domain.entity.NotificationTemplate;
import com.finvanta.domain.enums.NotificationChannel;
import com.finvanta.domain.enums.NotificationEventType;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * CBS Notification Template Repository per Finacle ALERT_TEMPLATE.
 *
 * Resolution order for template lookup:
 * 1. Product-specific + event type + channel (most specific)
 * 2. Global (null productCode) + event type + channel (fallback)
 * Per Finacle ALERT_TEMPLATE: product-level overrides take precedence.
 */
@Repository
public interface NotificationTemplateRepository
        extends JpaRepository<NotificationTemplate, Long> {

    /**
     * Find active template for a specific event, channel, and product.
     * Returns product-specific first, then global fallback.
     * ORDER BY: non-null productCode first (product-specific wins).
     */
    @Query("SELECT t FROM NotificationTemplate t "
            + "WHERE t.tenantId = :tenantId "
            + "AND t.eventType = :eventType "
            + "AND t.channel = :channel "
            + "AND t.active = true "
            + "AND t.languageCode = :lang "
            + "AND (t.productCode = :productCode "
            + "     OR t.productCode IS NULL) "
            + "ORDER BY t.productCode DESC NULLS LAST")
    List<NotificationTemplate> findApplicableTemplates(
            @Param("tenantId") String tenantId,
            @Param("eventType") NotificationEventType eventType,
            @Param("channel") NotificationChannel channel,
            @Param("productCode") String productCode,
            @Param("lang") String lang);

    /** Find all active templates for a tenant (admin UI) */
    List<NotificationTemplate>
            findByTenantIdAndActiveOrderByEventTypeAscChannelAsc(
                    String tenantId, boolean active);

    /** Find templates by event type (for reporting) */
    List<NotificationTemplate>
            findByTenantIdAndEventTypeAndActive(
                    String tenantId,
                    NotificationEventType eventType,
                    boolean active);

    /** Check if a template exists for event + channel + product */
    boolean existsByTenantIdAndEventTypeAndChannelAndProductCode(
            String tenantId,
            NotificationEventType eventType,
            NotificationChannel channel,
            String productCode);
}
