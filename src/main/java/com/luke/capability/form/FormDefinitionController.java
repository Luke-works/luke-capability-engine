package com.luke.capability.form;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Form definitions: create / read / edit, versioning (check-in → publish), and
 * retire. Tenant-scoped via the {@code X-Tenant-Id} header (set by the
 * core-engine proxy), matching {@link com.luke.capability.capability.SubscriptionController}.
 *
 * <p>Addressed by internal {@code id} for authoring routes and by stable
 * {@code code} for resolve routes (what a renderer / process formKey uses).
 */
@RestController
@RequestMapping("/api/form-definitions")
public class FormDefinitionController {

    private final FormDefinitionRepository forms;
    private final FormVersionRepository versions;

    public FormDefinitionController(FormDefinitionRepository forms, FormVersionRepository versions) {
        this.forms = forms;
        this.versions = versions;
    }

    /* ── request bodies ─────────────────────────────────────── */
    public record CreateForm(String name, String description) {}
    public record MetaPatch(String name, String description) {}
    public record DraftBody(String schema) {}
    public record CheckInBody(String schema, Boolean publish) {}

    /* ── CRUD ───────────────────────────────────────────────── */

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FormDefinition create(@RequestHeader("X-Tenant-Id") String tenantId,
                                 @RequestHeader(value = "X-User-Id", required = false) String userId,
                                 @RequestBody CreateForm body) {
        requireTenant(tenantId);
        if (body.name() == null || body.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        FormDefinition form = new FormDefinition();
        form.setTenantId(tenantId);
        form.setCode(uniqueCode(tenantId));
        form.setName(body.name().trim());
        form.setDescription(body.description());
        form.setStatus("DRAFT");
        form.setCreatedBy(userId);
        form.setUpdatedBy(userId);
        return forms.save(form);
    }

    @GetMapping
    public List<FormDefinition> list(@RequestHeader("X-Tenant-Id") String tenantId,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(defaultValue = "false") boolean deleted) {
        requireTenant(tenantId);
        if (deleted) {
            return forms.findByTenantIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(tenantId);
        }
        return status == null
                ? forms.findByTenantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(tenantId)
                : forms.findByTenantIdAndStatusAndDeletedAtIsNullOrderByUpdatedAtDesc(tenantId, status);
    }

    @GetMapping("/{id}")
    public FormDefinition get(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        return load(tenantId, id);
    }

    @GetMapping("/by-code/{code}")
    public FormDefinition getByCode(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String code) {
        return loadByCode(tenantId, code);
    }

    @PatchMapping("/{id}")
    public FormDefinition patchMeta(@RequestHeader("X-Tenant-Id") String tenantId,
                                    @RequestHeader(value = "X-User-Id", required = false) String userId,
                                    @PathVariable String id, @RequestBody MetaPatch body) {
        FormDefinition form = load(tenantId, id);
        if (body.name() != null && !body.name().isBlank()) form.setName(body.name().trim());
        if (body.description() != null) form.setDescription(body.description());
        form.setUpdatedBy(userId);
        return forms.save(form);
    }

    @PutMapping("/{id}/draft")
    public FormDefinition saveDraft(@RequestHeader("X-Tenant-Id") String tenantId,
                                    @RequestHeader(value = "X-User-Id", required = false) String userId,
                                    @PathVariable String id, @RequestBody DraftBody body) {
        FormDefinition form = load(tenantId, id);
        form.setDraftSchema(body.schema());
        form.setUpdatedBy(userId);
        return forms.save(form);
    }

    /* ── resolve (by code) ──────────────────────────────────── */

    /** Resolve a schema for a renderer / process. pin = published (default) | latest | draft | v{n}. */
    @GetMapping("/by-code/{code}/schema")
    public Map<String, Object> resolveSchema(@RequestHeader("X-Tenant-Id") String tenantId,
                                             @PathVariable String code,
                                             @RequestParam(defaultValue = "published") String pin) {
        FormDefinition form = loadByCode(tenantId, code);
        int resolved;
        String schema;
        switch (pin) {
            case "draft" -> { return Map.of("code", code, "version", 0, "schema", nullToEmpty(form.getDraftSchema())); }
            case "latest" -> {
                FormVersion v = versions.findTopByFormIdOrderByVersionDesc(form.getId())
                        .orElseThrow(() -> notFound("No versions for " + code));
                resolved = v.getVersion(); schema = v.getSchema();
            }
            case "published" -> {
                if (form.getPublishedVersion() == null) throw notFound("No published version for " + code);
                resolved = form.getPublishedVersion();
                schema = version(form, resolved).getSchema();
            }
            default -> {
                int n = parsePin(pin);
                resolved = n; schema = version(form, n).getSchema();
            }
        }
        return Map.of("code", code, "version", resolved, "schema", schema);
    }

    /** Field→variable contract derived from the resolved schema. */
    @GetMapping("/by-code/{code}/fields")
    public Map<String, Object> fields(@RequestHeader("X-Tenant-Id") String tenantId,
                                      @PathVariable String code,
                                      @RequestParam(defaultValue = "published") String pin) {
        Map<String, Object> resolved = resolveSchema(tenantId, code, pin);
        return Map.of(
                "code", code,
                "version", resolved.get("version"),
                "fields", FormSupport.extractFields((String) resolved.get("schema")));
    }

    /* ── versioning ─────────────────────────────────────────── */

    /** Check in (compile) the draft as a new immutable version. First check-in auto-publishes; pass publish=true to publish a later one. */
    @PostMapping("/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public FormVersion checkIn(@RequestHeader("X-Tenant-Id") String tenantId,
                               @RequestHeader(value = "X-User-Id", required = false) String userId,
                               @PathVariable String id, @RequestBody(required = false) CheckInBody body) {
        FormDefinition form = load(tenantId, id);
        String schema = (body != null && body.schema() != null) ? body.schema() : form.getDraftSchema();
        if (schema == null || schema.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Form has no schema to check in");
        }
        int next = versions.findTopByFormIdOrderByVersionDesc(id).map(v -> v.getVersion() + 1).orElse(1);
        FormVersion artifact = versions.save(new FormVersion(id, next, schema, userId));

        boolean firstPublish = form.getPublishedVersion() == null;
        boolean publish = firstPublish || Boolean.TRUE.equals(body != null ? body.publish() : null);
        form.setDraftSchema(schema);
        if (publish) {
            form.setPublishedVersion(next);
            if (!"RETIRED".equals(form.getStatus())) form.setStatus("PUBLISHED");
        }
        form.setUpdatedBy(userId);
        forms.save(form);
        return artifact;
    }

    @GetMapping("/{id}/versions")
    public List<FormVersion> listVersions(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        load(tenantId, id);
        return versions.findByFormIdOrderByVersionAsc(id);
    }

    @GetMapping("/{id}/versions/{v}")
    public FormVersion getVersion(@RequestHeader("X-Tenant-Id") String tenantId,
                                  @PathVariable String id, @PathVariable int v) {
        FormDefinition form = load(tenantId, id);
        return version(form, v);
    }

    @PostMapping("/{id}/versions/{v}/publish")
    public FormDefinition publish(@RequestHeader("X-Tenant-Id") String tenantId,
                                  @RequestHeader(value = "X-User-Id", required = false) String userId,
                                  @PathVariable String id, @PathVariable int v) {
        FormDefinition form = load(tenantId, id);
        version(form, v); // 404 if missing
        form.setPublishedVersion(v);
        if (!"RETIRED".equals(form.getStatus())) form.setStatus("PUBLISHED");
        form.setUpdatedBy(userId);
        return forms.save(form);
    }

    /** Load an older version back into the editable draft. */
    @PostMapping("/{id}/versions/{v}/restore")
    public FormDefinition restoreVersion(@RequestHeader("X-Tenant-Id") String tenantId,
                                         @RequestHeader(value = "X-User-Id", required = false) String userId,
                                         @PathVariable String id, @PathVariable int v) {
        FormDefinition form = load(tenantId, id);
        form.setDraftSchema(version(form, v).getSchema());
        form.setUpdatedBy(userId);
        return forms.save(form);
    }

    /* ── retire & remove ────────────────────────────────────── */

    @PostMapping("/{id}/retire")
    public FormDefinition retire(@RequestHeader("X-Tenant-Id") String tenantId,
                                 @RequestHeader(value = "X-User-Id", required = false) String userId,
                                 @PathVariable String id) {
        FormDefinition form = load(tenantId, id);
        form.setStatus("RETIRED");
        form.setUpdatedBy(userId);
        return forms.save(form);
    }

    @PostMapping("/{id}/unretire")
    public FormDefinition unretire(@RequestHeader("X-Tenant-Id") String tenantId,
                                   @RequestHeader(value = "X-User-Id", required = false) String userId,
                                   @PathVariable String id) {
        FormDefinition form = load(tenantId, id);
        form.setStatus(form.getPublishedVersion() != null ? "PUBLISHED" : "DRAFT");
        form.setUpdatedBy(userId);
        return forms.save(form);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void softDelete(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        FormDefinition form = load(tenantId, id);
        form.setDeletedAt(java.time.LocalDateTime.now());
        forms.save(form);
    }

    @PostMapping("/{id}/restore")
    public FormDefinition restore(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        FormDefinition form = forms.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> notFound("Unknown form: " + id));
        form.setDeletedAt(null);
        return forms.save(form);
    }

    @DeleteMapping("/{id}/purge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void purge(@RequestHeader("X-Tenant-Id") String tenantId, @PathVariable String id) {
        FormDefinition form = forms.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> notFound("Unknown form: " + id));
        versions.deleteAll(versions.findByFormIdOrderByVersionAsc(id));
        forms.delete(form);
    }

    /* ── helpers ────────────────────────────────────────────── */

    private FormDefinition load(String tenantId, String id) {
        requireTenant(tenantId);
        FormDefinition form = forms.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> notFound("Unknown form: " + id));
        if (form.getDeletedAt() != null) throw notFound("Form is in trash: " + id);
        return form;
    }

    private FormDefinition loadByCode(String tenantId, String code) {
        requireTenant(tenantId);
        FormDefinition form = forms.findByTenantIdAndCode(tenantId, code)
                .orElseThrow(() -> notFound("Unknown form code: " + code));
        if (form.getDeletedAt() != null) throw notFound("Form is in trash: " + code);
        return form;
    }

    private FormVersion version(FormDefinition form, int v) {
        return versions.findByFormIdAndVersion(form.getId(), v)
                .orElseThrow(() -> notFound("Unknown version v" + v + " for " + form.getCode()));
    }

    private String uniqueCode(String tenantId) {
        String code = FormSupport.generateCode();
        while (forms.existsByTenantIdAndCode(tenantId, code)) code = FormSupport.generateCode();
        return code;
    }

    private int parsePin(String pin) {
        try {
            return Integer.parseInt(pin.startsWith("v") ? pin.substring(1) : pin);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pin: " + pin + " (use published|latest|draft|v{n})");
        }
    }

    private static void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Tenant-Id is required");
        }
    }

    private static ResponseStatusException notFound(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
