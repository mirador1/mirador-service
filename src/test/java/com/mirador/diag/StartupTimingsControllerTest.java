package com.mirador.diag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StartupTimingsController} — the diagnostic
 * endpoint exposing JVM startup timings via {@code /diag/startup-timings}.
 *
 * <p>Pinned contracts: response shape (3 specific keys in insertion
 * order), null-safe rendering of {@code readyAt} before
 * ApplicationReadyEvent fires, and the bean-timings map being passed
 * through unchanged from the underlying {@link StartupTimings}.
 */
class StartupTimingsControllerTest {

    private StartupTimings timings;
    private StartupTimingsController controller;

    @BeforeEach
    void setUp() {
        timings = mock(StartupTimings.class);
        controller = new StartupTimingsController(timings);
    }

    @Test
    void startupTimings_returnsOkWithThreeKeys() {
        when(timings.totalBootMs()).thenReturn(2500L);
        when(timings.readyAt()).thenReturn(Instant.parse("2026-04-23T00:30:00Z"));
        when(timings.beanTimings()).thenReturn(Map.of("dataSource", 150L, "kafkaListener", 80L));

        ResponseEntity<Map<String, Object>> response = controller.startupTimings();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull().containsOnlyKeys("totalBootMs", "readyAt", "beans");
    }

    @Test
    void startupTimings_passesBootMsAndReadyAtVerbatim() {
        when(timings.totalBootMs()).thenReturn(1234L);
        when(timings.readyAt()).thenReturn(Instant.parse("2026-04-23T00:30:00Z"));
        when(timings.beanTimings()).thenReturn(Map.of());

        Map<String, Object> body = controller.startupTimings().getBody();

        assertThat(body).isNotNull();
        assertThat(body.get("totalBootMs")).isEqualTo(1234L);
        assertThat(body.get("readyAt")).isEqualTo("2026-04-23T00:30:00Z");
    }

    @Test
    void startupTimings_beforeReady_readyAtIsNullNotEmptyString() {
        // The controller exposes `readyAt` as null when ApplicationReadyEvent
        // hasn't fired yet (vs. an empty string or the epoch). Pinned because
        // the UI relies on `=== null` to show "still booting" badge.
        when(timings.totalBootMs()).thenReturn(-1L);
        when(timings.readyAt()).thenReturn(null);
        when(timings.beanTimings()).thenReturn(Map.of());

        Map<String, Object> body = controller.startupTimings().getBody();

        assertThat(body).isNotNull();
        assertThat(body.get("readyAt")).isNull();
        assertThat(body.get("totalBootMs")).isEqualTo(-1L);
    }

    @Test
    void startupTimings_passesBeansMapVerbatim() {
        // Insertion order matters — UI ranks beans by init time and the map
        // arrives pre-sorted from StartupTimings.beanTimings(). Test pins the
        // controller is a thin proxy (no re-sort, no filter).
        Map<String, Long> beans = new LinkedHashMap<>();
        beans.put("flyway", 800L);
        beans.put("openTelemetryAgent", 300L);
        beans.put("dataSource", 150L);
        when(timings.totalBootMs()).thenReturn(2500L);
        when(timings.readyAt()).thenReturn(Instant.now());
        when(timings.beanTimings()).thenReturn(beans);

        Map<String, Object> body = controller.startupTimings().getBody();

        assertThat(body).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Long> actualBeans = (Map<String, Long>) body.get("beans");
        assertThat(actualBeans).containsExactlyEntriesOf(beans);
    }

    @Test
    void startupTimings_emptyBeansMapStillIncludesKey() {
        // Even when no bean took ≥ 5 ms, the `beans` key must be present
        // (UI renders an empty list gracefully, but a missing key would
        // throw on `body.beans.entries()`).
        when(timings.totalBootMs()).thenReturn(100L);
        when(timings.readyAt()).thenReturn(Instant.now());
        when(timings.beanTimings()).thenReturn(Map.of());

        Map<String, Object> body = controller.startupTimings().getBody();

        assertThat(body).isNotNull().containsKey("beans");
        assertThat((Map<?, ?>) body.get("beans")).isEmpty();
    }
}
