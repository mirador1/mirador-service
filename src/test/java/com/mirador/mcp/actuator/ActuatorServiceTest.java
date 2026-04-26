package com.mirador.mcp.actuator;

import com.mirador.mcp.dto.EnvSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ActuatorService} — focus on the env-redaction
 * contract + the info passthrough. Health-tree mapping is covered with
 * a minimal fake descriptor so the test does not need the full SB4
 * actuator wiring.
 */
class ActuatorServiceTest {

    private HealthEndpoint healthEndpoint;
    private InfoEndpoint infoEndpoint;
    private EnvironmentSnapshotProvider envProvider;
    private ActuatorService service;

    @BeforeEach
    void setUp() {
        healthEndpoint = mock(HealthEndpoint.class);
        infoEndpoint = mock(InfoEndpoint.class);
        envProvider = mock(EnvironmentSnapshotProvider.class);
        service = new ActuatorService(healthEndpoint, infoEndpoint, envProvider);
    }

    @Test
    void envRedactsSecretsByPropertyName() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("spring.datasource.url", "jdbc:postgresql://localhost/demo");
        raw.put("spring.datasource.password", "shouldNotLeak");
        raw.put("auth0.client.secret", "topSecret");
        raw.put("custom.api.token", "abc123");
        raw.put("private.key.pem", "-----BEGIN-----");
        raw.put("any.credential.expiry", "1d");
        when(envProvider.snapshot(null)).thenReturn(raw);

        EnvSnapshot snap = service.getEnv(null);
        assertThat(snap.properties().get("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://localhost/demo");
        assertThat(snap.properties().get("spring.datasource.password")).isEqualTo("***");
        assertThat(snap.properties().get("auth0.client.secret")).isEqualTo("***");
        assertThat(snap.properties().get("custom.api.token")).isEqualTo("***");
        assertThat(snap.properties().get("private.key.pem")).isEqualTo("***");
        assertThat(snap.properties().get("any.credential.expiry")).isEqualTo("***");
    }

    @Test
    void envCapsAt200Entries() {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (int i = 0; i < 250; i++) {
            raw.put("prop." + i, "v" + i);
        }
        when(envProvider.snapshot("prop.")).thenReturn(raw);

        EnvSnapshot snap = service.getEnv("prop.");
        assertThat(snap.properties()).hasSize(ActuatorService.MAX_ENV_PROPERTIES);
    }

    @Test
    void infoReturnsImmutableCopyOfContributors() {
        Map<String, Object> raw = Map.of("git", Map.of("branch", "main"), "build", Map.of("version", "0.1.0"));
        when(infoEndpoint.info()).thenReturn(raw);

        Map<String, Object> info = service.getInfo();
        assertThat(info).hasSize(2);
        assertThat(info).containsKeys("git", "build");
    }

    @Test
    void infoNullReturnsEmptyMap() {
        when(infoEndpoint.info()).thenReturn(null);
        assertThat(service.getInfo()).isEmpty();
    }

    @Test
    void caseInsensitiveSecretMatch() {
        // Verify the regex pattern is genuinely case-insensitive.
        assertThat(ActuatorService.SECRET_NAME_PATTERN.matcher("MY.PASSWORD").matches()).isTrue();
        assertThat(ActuatorService.SECRET_NAME_PATTERN.matcher("My.Token").matches()).isTrue();
        assertThat(ActuatorService.SECRET_NAME_PATTERN.matcher("API_KEY_FOO").matches()).isTrue();
        assertThat(ActuatorService.SECRET_NAME_PATTERN.matcher("any.credential").matches()).isTrue();
        assertThat(ActuatorService.SECRET_NAME_PATTERN.matcher("benign.url").matches()).isFalse();
    }
}
