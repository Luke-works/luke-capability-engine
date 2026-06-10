package com.luke.capability.form;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FormAuditEventRepository extends JpaRepository<FormAuditEvent, String> {

    /** Most-recent-first activity feed for a form. */
    List<FormAuditEvent> findByFormIdAndTenantIdOrderByAtDesc(String formId, String tenantId);

    void deleteByFormId(String formId);
}
