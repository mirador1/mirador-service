package com.mirador.mcp.domain;

import com.mirador.chaos.ChaosExperiment;
import com.mirador.chaos.ChaosService;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChaosToolService} — covers the structured-error
 * shape returned in the three failure paths (unknown slug, Chaos Mesh
 * unavailable, generic Kubernetes API error).
 */
class ChaosToolServiceTest {

    private ChaosService chaosService;
    private ChaosToolService tool;

    @BeforeEach
    void setUp() {
        chaosService = mock(ChaosService.class);
        tool = new ChaosToolService(chaosService);
    }

    @Test
    void successPathReturnsTriggeredStatus() {
        when(chaosService.trigger(ChaosExperiment.POD_KILL))
                .thenReturn("mirador-pod-kill-1730000000000");

        Map<String, Object> result = tool.triggerChaosExperiment("pod-kill");
        assertThat(result.get("status")).isEqualTo("triggered");
        assertThat(result.get("experiment")).isEqualTo("pod-kill");
        assertThat(result.get("kind")).isEqualTo("PodChaos");
        assertThat(result.get("customResourceName")).isEqualTo("mirador-pod-kill-1730000000000");
    }

    @Test
    void unknownSlugReturnsErrorWithList() {
        Map<String, Object> result = tool.triggerChaosExperiment("not-a-thing");
        assertThat(result.get("status")).isEqualTo("error");
        assertThat(result.get("error_code")).isEqualTo("unknown_scenario");
        assertThat(result.get("message")).asString()
                .contains("Unknown chaos experiment");
    }

    @Test
    void chaosMeshUnavailableMaps404() {
        when(chaosService.trigger(ChaosExperiment.POD_KILL))
                .thenThrow(new IllegalStateException("Chaos Mesh CRDs not installed"));

        Map<String, Object> result = tool.triggerChaosExperiment("pod-kill");
        assertThat(result.get("status")).isEqualTo("error");
        assertThat(result.get("error_code")).isEqualTo("chaos_mesh_unavailable");
    }

    @Test
    void kubernetesApiErrorCarriesHttpCode() {
        KubernetesClientException ex = new KubernetesClientException("Forbidden", 403, null);
        when(chaosService.trigger(ChaosExperiment.NETWORK_DELAY)).thenThrow(ex);

        Map<String, Object> result = tool.triggerChaosExperiment("network-delay");
        assertThat(result.get("status")).isEqualTo("error");
        assertThat(result.get("error_code")).isEqualTo("kubernetes_api_error_403");
    }
}
