package com.mirador.mcp.domain;

import com.mirador.chaos.ChaosExperiment;
import com.mirador.chaos.ChaosService;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * MCP tool surface for on-demand Chaos Mesh experiments.
 *
 * <p>Wraps the existing {@link ChaosService} so the LLM can trigger a
 * pod-kill / network-delay / cpu-stress experiment by name. Admin-gated
 * because every experiment is destructive.
 */
@Service
public class ChaosToolService {

    private final ChaosService chaosService;

    public ChaosToolService(ChaosService chaosService) {
        this.chaosService = chaosService;
    }

    /**
     * Triggers the named chaos experiment.
     *
     * <p>Returns a structured response in all error cases — never throws
     * past the MCP boundary. The structured shape lets the LLM reason
     * about the failure ({@code error_code} + {@code message}) instead
     * of seeing an opaque exception.
     *
     * @param scenario one of {@code pod-kill / network-delay / cpu-stress}
     * @return either {@code {status:'triggered', kind, customResourceName, duration}}
     *         or {@code {status:'error', error_code, message}}
     */
    @Tool(name = "trigger_chaos_experiment",
            description = "Triggers a Chaos Mesh experiment by slug : pod-kill, "
                    + "network-delay, or cpu-stress. Each spawns a Chaos Mesh CR that "
                    + "auto-deletes after its built-in duration. Admin-only. Returns "
                    + "{status:'triggered', kind, customResourceName, duration} on "
                    + "success or {status:'error', error_code, message} when Chaos "
                    + "Mesh is not installed / RBAC denies the call.")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> triggerChaosExperiment(
            @ToolParam(description = "Experiment slug : 'pod-kill', 'network-delay', or "
                    + "'cpu-stress'.")
            String scenario
    ) {
        ChaosExperiment exp;
        try {
            exp = ChaosExperiment.fromSlug(scenario);
        } catch (IllegalArgumentException ex) {
            // Unknown slug — surface the valid list back so the LLM can self-correct.
            return Map.of(
                    "status", "error",
                    "error_code", "unknown_scenario",
                    "message", ex.getMessage()
            );
        }
        try {
            String crName = chaosService.trigger(exp);
            return Map.of(
                    "status", "triggered",
                    "experiment", exp.slug(),
                    "kind", exp.kind(),
                    "customResourceName", crName,
                    "duration", exp.duration()
            );
        } catch (IllegalStateException ex) {
            // Chaos Mesh CRDs not installed (404 from the API).
            return Map.of(
                    "status", "error",
                    "error_code", "chaos_mesh_unavailable",
                    "message", ex.getMessage()
            );
        } catch (KubernetesClientException ex) {
            // RBAC denied, conflict, generic API error — surface the code so
            // the LLM can suggest the right remediation (RBAC for 403,
            // re-trigger for 409 conflict, …).
            return Map.of(
                    "status", "error",
                    "error_code", "kubernetes_api_error_" + ex.getCode(),
                    "message", ex.getMessage()
            );
        }
    }
}
