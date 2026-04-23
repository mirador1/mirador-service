package com.mirador.chaos;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link ChaosConfig#kubernetesClient()} — pin the
 * Fabric8 Kubernetes client wiring.
 *
 * <p>Pinned contract : the bean returns a {@link KubernetesClient} ;
 * construction is LAZY (no API call) so this works off-cluster without
 * a kubeconfig — a regression that eagerly opened the connection at
 * bean-init would prevent local dev / unit tests from booting the
 * Spring context.
 */
class ChaosConfigTest {

    @Test
    void kubernetesClient_returnsNonNullClientWithoutOpeningConnection() {
        // Pinned: lazy connection. The bean MUST construct without
        // requiring a kubeconfig OR a reachable cluster — this is the
        // explicit contract documented in ChaosConfig's class-level
        // Javadoc ("does NOT open a connection at startup"). A
        // regression that eagerly connected would crash every IDE-run
        // boot ("connection refused: localhost:6443").
        ChaosConfig config = new ChaosConfig();

        KubernetesClient client = config.kubernetesClient();

        assertThat(client).isNotNull();
    }

    @Test
    void kubernetesClient_canBeClosedWithoutHavingMadeAnyCall() {
        // Defensive : the lazy client should support being closed even
        // when no API call was made (no socket to tear down). KubernetesClient
        // implements AutoCloseable ; a regression that errored on close()
        // without a prior call would leak handles in test cleanups.
        ChaosConfig config = new ChaosConfig();
        KubernetesClient client = config.kubernetesClient();

        // No exception expected
        client.close();
    }
}
