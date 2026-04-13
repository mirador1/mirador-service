package com.example.customerservice.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * CORS-friendly proxy for observability backends that don't support CORS natively.
 *
 * <p>The Angular UI on {@code localhost:4200} needs to query Loki directly for log data.
 * Loki (inside the LGTM container) does not support CORS headers. Instead of adding
 * a reverse proxy, the Spring Boot app proxies the requests — CORS is already configured
 * on this app via {@code SecurityConfig}.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /obs/loki/query_range} — proxies to Loki's {@code /loki/api/v1/query_range}</li>
 * </ul>
 */
@RestController
@RequestMapping("/obs")
public class ObsProxyController {

    private final String lokiUrl;

    public ObsProxyController(
            @Value("${obs.loki.url:http://localhost:3100}") String lokiUrl) {
        this.lokiUrl = lokiUrl;
    }

    @GetMapping("/loki/query_range")
    public ResponseEntity<String> lokiQueryRange(
            @RequestParam String query,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {

        var uriBuilder = new StringBuilder(lokiUrl + "/loki/api/v1/query_range?query=" + query + "&limit=" + limit);
        if (start != null) uriBuilder.append("&start=").append(start);
        if (end != null) uriBuilder.append("&end=").append(end);

        String response = RestClient.create()
                .get()
                .uri(uriBuilder.toString())
                .retrieve()
                .body(String.class);

        return ResponseEntity.ok()
                .header("Content-Type", "application/json")
                .body(response);
    }
}
