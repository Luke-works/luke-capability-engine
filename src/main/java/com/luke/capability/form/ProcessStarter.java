package com.luke.capability.form;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Starts the generic intake process in core-engine after a form submission.
 * Server-to-server (the first cap→core call) into core-engine's internal
 * endpoint, authenticated with a shared secret. BEST-EFFORT: any failure is
 * logged and swallowed — a submission is never lost because the process couldn't
 * start. Returns the process instance id on success, else {@code null}.
 */
@Component
public class ProcessStarter {

    private static final Logger log = LoggerFactory.getLogger(ProcessStarter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate rest = new RestTemplate();

    @Value("${luke.core-engine.base-url:http://localhost:8080}")
    private String coreBaseUrl;

    @Value("${luke.internal.shared-secret:}")
    private String sharedSecret;

    /** Start the intake process for a submitted instance. Returns its id, or null. */
    public String startForInstance(FormInstance inst) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("tenantId", inst.getTenantId());
        vars.put("formCode", inst.getDefinitionCode());
        vars.put("version", inst.getVersion());
        vars.put("instanceId", inst.getId());
        try {
            vars.put("formData", MAPPER.writeValueAsString(inst.getData() != null ? inst.getData() : Map.of()));
        } catch (Exception e) {
            vars.put("formData", "{}");
        }
        return start(inst.getTenantId(), inst.getId(), vars);
    }

    private String start(String tenantId, String businessKey, Map<String, Object> variables) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (sharedSecret != null && !sharedSecret.isBlank()) headers.set("X-Internal-Key", sharedSecret);

            Map<String, Object> body = new HashMap<>();
            body.put("tenantId", tenantId);
            body.put("businessKey", businessKey);
            body.put("variables", variables);

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = rest.postForObject(
                    coreBaseUrl + "/api/internal/process-start", new HttpEntity<>(body, headers), Map.class);
            Object pid = resp != null ? resp.get("processInstanceId") : null;
            return pid != null ? pid.toString() : null;
        } catch (Exception e) {
            log.warn("Intake process start failed for tenant {} (instance {}): {}", tenantId, businessKey, e.getMessage());
            return null; // best-effort
        }
    }
}
