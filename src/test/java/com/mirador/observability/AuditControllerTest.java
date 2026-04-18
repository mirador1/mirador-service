package com.mirador.observability;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Unit tests for {@link AuditController}. The controller is a thin pass-through
 * to {@link AuditService} but the page-size cap at 100 is its only non-trivial
 * logic and was previously untested (the class sat at 50 % method coverage).
 */
class AuditControllerTest {

    private final AuditService service = mock(AuditService.class);
    private final AuditController controller = new AuditController(service);

    @Test
    void delegatesAllFiltersToService() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-12-31T23:59:59Z");
        AuditPage expected = new AuditPage(List.of(), 0, 20, 0, 0);
        when(service.findAll(0, 20, "LOGIN_FAILED", "alice", from, to)).thenReturn(expected);

        AuditPage result = controller.getAuditEvents(0, 20, "LOGIN_FAILED", "alice", from, to);

        assertThat(result).isSameAs(expected);
        verify(service).findAll(0, 20, "LOGIN_FAILED", "alice", from, to);
    }

    @Test
    void capsPageSizeAt100() {
        // Anything above 100 must be clamped — the cap is the controller's only
        // real business logic on top of the pass-through.
        when(service.findAll(anyInt(), anyInt(), anyString(), isNull(), isNull(), isNull()))
                .thenReturn(new AuditPage(List.of(), 0, 100, 0, 0));

        controller.getAuditEvents(0, 5000, "X", null, null, null);

        verify(service).findAll(eq(0), eq(100), eq("X"), isNull(), isNull(), isNull());
    }

    @Test
    void passesSizeThrough_whenBelowCap() {
        when(service.findAll(anyInt(), anyInt(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new AuditPage(List.of(), 2, 50, 0, 0));

        controller.getAuditEvents(2, 50, null, null, null, null);

        verify(service).findAll(2, 50, null, null, null, null);
    }
}
