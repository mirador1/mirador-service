package com.example.customerservice.customer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Dev/demo controller for managing Docker containers from the UI.
 *
 * <p>Uses {@link ProcessBuilder} to execute {@code docker} CLI commands.
 * <b>Not for production use</b> — this exposes host-level operations behind auth.
 *
 * <p>All endpoints require {@code ROLE_ADMIN}.
 */
@RestController
@RequestMapping("/docker")
@PreAuthorize("hasRole('ADMIN')")
public class DockerController {

    private static final Logger log = LoggerFactory.getLogger(DockerController.class);

    private static final List<String> ALLOWED_ACTIONS = List.of("stop", "start", "restart");

    @GetMapping("/containers")
    public List<Map<String, String>> listContainers() {
        String output = exec("docker", "ps", "-a",
                "--format", "{{.Names}}\t{{.Status}}\t{{.Image}}\t{{.State}}");
        return Arrays.stream(output.split("\n"))
                .filter(line -> !line.isBlank())
                .map(line -> {
                    String[] parts = line.split("\t", 4);
                    return Map.of(
                            "name", parts.length > 0 ? parts[0] : "",
                            "status", parts.length > 1 ? parts[1] : "",
                            "image", parts.length > 2 ? parts[2] : "",
                            "state", parts.length > 3 ? parts[3] : "");
                })
                .toList();
    }

    @PostMapping("/containers/{name}/stop")
    public Map<String, String> stopContainer(@PathVariable String name) {
        return dockerAction("stop", name);
    }

    @PostMapping("/containers/{name}/start")
    public Map<String, String> startContainer(@PathVariable String name) {
        return dockerAction("start", name);
    }

    @PostMapping("/containers/{name}/restart")
    public Map<String, String> restartContainer(@PathVariable String name) {
        return dockerAction("restart", name);
    }

    private Map<String, String> dockerAction(String action, String name) {
        if (!ALLOWED_ACTIONS.contains(action)) {
            throw new IllegalArgumentException("Invalid action: " + action);
        }
        // Sanitize container name — only allow alphanumeric, dash, underscore
        if (!name.matches("[a-zA-Z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid container name: " + name);
        }
        log.info("docker_action action={} container={}", action, name);
        String output = exec("docker", action, name);
        return Map.of("action", action, "container", name, "result", output.trim());
    }

    private String exec(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                process.waitFor();
                return sb.toString();
            }
        } catch (Exception e) {
            log.error("docker_exec_failed command={} error={}", String.join(" ", command), e.getMessage());
            return "ERROR: " + e.getMessage();
        }
    }
}
