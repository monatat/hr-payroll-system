package com.hrpayroll.backend.audit;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByActionAndEntityId(String action, Long entityId);

    List<AuditLog> findByAction(String action);
}
