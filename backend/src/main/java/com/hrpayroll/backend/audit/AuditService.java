package com.hrpayroll.backend.audit;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Thin, synchronous audit writer. Authentication/authorization correctness
 * must never depend on audit logging succeeding, so failures here are
 * caught and logged rather than propagated.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public void record(Long actorId, String action, String entityName, Long entityId, Map<String, Object> details) {
        try {
            auditLogRepository.save(new AuditLog(actorId, action, entityName, entityId, details));
        } catch (Exception ex) {
            log.warn("Failed to write audit log entry for action '{}': {}", action, ex.getMessage());
        }
    }
}
