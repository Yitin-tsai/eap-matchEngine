package com.eap.eap_matchengine.configuration.recovery;

import com.eap.common.recovery.RecoveryCaseDetail;
import com.eap.common.recovery.RecoveryCaseSummary;
import com.eap.common.recovery.RecoveryDryRunRequest;
import com.eap.common.recovery.RecoveryDryRunResult;
import com.eap.common.recovery.RecoveryExecuteRequest;
import com.eap.common.recovery.RecoveryExecuteResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/internal/recovery/v1")
@ConditionalOnProperty(name = "eap.recovery-source.enabled", havingValue = "true")
public class MatchRecoverySourceController {

    public static final String TOKEN_HEADER = "X-EAP-Recovery-Source-Token";

    private final MatchRecoveryCaseService service;
    private final byte[] expectedToken;

    public MatchRecoverySourceController(
            MatchRecoveryCaseService service,
            @Value("${eap.recovery-source.token}") String expectedToken) {
        if (expectedToken == null || expectedToken.isBlank()) {
            throw new IllegalArgumentException("eap.recovery-source.token must be configured when enabled");
        }
        this.service = service;
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/cases")
    public List<RecoveryCaseSummary> list(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestParam(defaultValue = "50") int limit) {
        authorize(token);
        return service.list(limit);
    }

    @GetMapping("/cases/{caseId}")
    public RecoveryCaseDetail detail(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @PathVariable String caseId) {
        authorize(token);
        return service.detail(caseId);
    }

    @PostMapping("/cases/{caseId}/dry-run")
    public RecoveryDryRunResult dryRun(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @PathVariable String caseId,
            @RequestBody RecoveryDryRunRequest request) {
        authorize(token);
        return service.dryRun(caseId, request);
    }

    @PostMapping("/cases/{caseId}/execute")
    public RecoveryExecuteResult execute(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @PathVariable String caseId,
            @RequestBody RecoveryExecuteRequest request) {
        authorize(token);
        return service.execute(caseId, request);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> notFound(NoSuchElementException failure) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", failure.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException failure) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", failure.getMessage()));
    }

    @ExceptionHandler(RecoverySourceForbiddenException.class)
    ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
    }

    private void authorize(String token) {
        byte[] provided = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, provided)) {
            throw new RecoverySourceForbiddenException();
        }
    }

    private static final class RecoverySourceForbiddenException extends RuntimeException {
    }
}
