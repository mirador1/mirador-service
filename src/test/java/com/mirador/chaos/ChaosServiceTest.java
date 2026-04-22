package com.mirador.chaos;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ChaosService} using Fabric8's mock Kubernetes API
 * server ({@link KubernetesMockServer}).
 *
 * <p>The mock server records every request without hitting a real cluster;
 * for the error paths we stub specific responses (e.g. {@code 404} when
 * the CRD isn't registered). This gives us real coverage of the Fabric8
 * wiring without the cost of a full Testcontainers-backed integration
 * test.
 */
@EnableKubernetesMockClient(crud = true)
class ChaosServiceTest {

    // Injected by @EnableKubernetesMockClient — static + non-final, see
    // https://github.com/fabric8io/kubernetes-client/blob/main/doc/MOCK.md
    @SuppressWarnings("java:S1444") // mock fields must be non-final, non-private per Fabric8 pattern
    static KubernetesClient client;
    @SuppressWarnings("java:S1444")
    static KubernetesMockServer server;

    @Test
    void trigger_podKill_postsGenericResourceToPodChaosEndpoint() {
        ChaosService service = new ChaosService(client);

        String crName = service.trigger(ChaosExperiment.POD_KILL);

        // CR name carries slug + an epoch-second timestamp suffix so concurrent
        // clicks don't collide; we just assert the prefix here.
        assertThat(crName).startsWith("mirador-pod-kill-");

        // The mock server's CRUD mode persists the resource — we can read it
        // back via the same GenericKubernetesResource endpoint to verify the
        // spec that was written.
        GenericKubernetesResource created = client
                .genericKubernetesResources(rdcFor("PodChaos"))
                .inNamespace("app")
                .withName(crName)
                .get();

        assertThat(created).as("PodChaos CR should exist after trigger()").isNotNull();
        assertThat(created.getKind()).isEqualTo("PodChaos");
        assertThat(created.getMetadata().getNamespace()).isEqualTo("app");
        assertThat(created.getMetadata().getLabels())
                .containsEntry("app.mirador.demo/chaos", "pod-kill")
                .containsEntry("app.mirador.demo/triggered-from", "ui");

        // spec is stored as additional properties because GenericKubernetesResource
        // doesn't model chaos-mesh.org types natively.
        Map<String, Object> spec = castSpec(created);
        assertThat(spec)
                .containsEntry("action", "pod-kill")
                .containsEntry("mode", "one")
                .containsEntry("duration", "30s");
    }

    @Test
    void trigger_networkDelay_buildsSpecWithPostgresTarget() {
        ChaosService service = new ChaosService(client);

        String crName = service.trigger(ChaosExperiment.NETWORK_DELAY);

        GenericKubernetesResource created = client
                .genericKubernetesResources(rdcFor("NetworkChaos"))
                .inNamespace("app")
                .withName(crName)
                .get();

        Map<String, Object> spec = castSpec(created);
        assertThat(spec)
                .containsEntry("action", "delay")
                .containsEntry("mode", "all")
                .containsEntry("duration", "1m")
                .containsEntry("direction", "to");

        Map<String, Object> delay = castMap(spec.get("delay"));
        assertThat(delay)
                .containsEntry("latency", "200ms")
                .containsEntry("correlation", "50")
                .containsEntry("jitter", "50ms");

        // The target selector points at postgresql in the infra namespace —
        // this is the whole point of this experiment (latency to DB, not
        // latency between mirador pods or to an external API).
        Map<String, Object> target = castMap(spec.get("target"));
        Map<String, Object> targetSelector = castMap(target.get("selector"));
        assertThat(castList(targetSelector.get("namespaces"))).containsExactly("infra");
        assertThat(castMap(targetSelector.get("labelSelectors")))
                .containsEntry("app.kubernetes.io/name", "postgresql");
    }

    @Test
    void trigger_cpuStress_buildsSpecWithStressors() {
        ChaosService service = new ChaosService(client);

        String crName = service.trigger(ChaosExperiment.CPU_STRESS);

        GenericKubernetesResource created = client
                .genericKubernetesResources(rdcFor("StressChaos"))
                .inNamespace("app")
                .withName(crName)
                .get();

        Map<String, Object> spec = castSpec(created);
        assertThat(spec)
                .containsEntry("mode", "one")
                .containsEntry("duration", "2m");

        Map<String, Object> stressors = castMap(spec.get("stressors"));
        Map<String, Object> cpu = castMap(stressors.get("cpu"));
        assertThat(cpu).containsEntry("workers", 1)
                       .containsEntry("load", 70);
    }

    @Test
    void trigger_crdNotInstalled_translatesTo404ToIllegalStateException() {
        // Override CRUD mode for this single path — stub a 404 on POST.
        Status notFound = new StatusBuilder().withCode(HttpURLConnection.HTTP_NOT_FOUND)
                .withMessage("the server could not find the requested resource")
                .build();
        server.expect().post()
                .withPath("/apis/chaos-mesh.org/v1alpha1/namespaces/app/podchaos")
                .andReturn(HttpURLConnection.HTTP_NOT_FOUND, notFound)
                .once();

        ChaosService service = new ChaosService(client);

        assertThatThrownBy(() -> service.trigger(ChaosExperiment.POD_KILL))
                .isInstanceOf(IllegalStateException.class)
                // The message must point the caller at the demo up-script —
                // that's the actionable fix. A generic "not found" would make
                // the error look like a bug in Mirador.
                .hasMessageContaining("Chaos Mesh CRDs not installed")
                .hasMessageContaining("bin/cluster/demo/up.sh")
                .hasCauseInstanceOf(KubernetesClientException.class);
    }

    /** Helper — matches the RDC ChaosService uses internally. */
    private static ResourceDefinitionContext rdcFor(String kind) {
        return new ResourceDefinitionContext.Builder()
                .withGroup("chaos-mesh.org")
                .withVersion("v1alpha1")
                .withKind(kind)
                .withNamespaced(true)
                .withPlural(kind.toLowerCase(Locale.ROOT))
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castSpec(GenericKubernetesResource cr) {
        return (Map<String, Object>) cr.getAdditionalProperties().get("spec");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object o) {
        return (List<String>) o;
    }
}
