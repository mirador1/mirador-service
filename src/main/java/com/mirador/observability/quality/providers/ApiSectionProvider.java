package com.mirador.observability.quality.providers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Walks Spring MVC's {@link RequestMappingHandlerMapping} to enumerate
 * every registered REST endpoint, and assembles the {@code api} section.
 *
 * <p>Extracted 2026-04-22 from {@code QualityReportEndpoint.buildApiSection}
 * under Phase B-1b.
 *
 * <h3>Output</h3>
 * <pre>
 * { available: true,
 *   total,
 *   endpoints: [ { path, methods: [GET|POST|…], handler: "Controller.method" }
 *                  … sorted by path asc ] }
 * </pre>
 * Always {@code available: true} — if no handlers are registered the
 * endpoint list is empty but the section is still "reachable".
 */
@Component
public class ApiSectionProvider {

    private static final String K_AVAILABLE = "available";
    private static final String K_TOTAL     = "total";
    private static final String K_METHODS   = "methods";

    private final RequestMappingHandlerMapping requestMappingHandlerMapping;

    public ApiSectionProvider(RequestMappingHandlerMapping requestMappingHandlerMapping) {
        this.requestMappingHandlerMapping = requestMappingHandlerMapping;
    }

    public Map<String, Object> parse() {
        List<Map<String, Object>> endpoints = new ArrayList<>();
        requestMappingHandlerMapping.getHandlerMethods().forEach((info, method) -> {
            Set<String> patterns = info.getPatternValues();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                Map<String, Object> ep = new LinkedHashMap<>();
                ep.put("path", pattern);
                ep.put(K_METHODS, methods.isEmpty()
                        ? List.of("GET")
                        : methods.stream().map(Enum::name).sorted().toList());
                ep.put("handler",
                        method.getBeanType().getSimpleName() + "." + method.getMethod().getName());
                endpoints.add(ep);
            }
        });
        // Sort by path for a stable dashboard ordering (methods secondary
        // when paths match is not needed — Spring doesn't register two
        // HandlerMethods at the exact same path+method combo).
        endpoints.sort((a, b) -> ((String) a.get("path")).compareTo((String) b.get("path")));

        Map<String, Object> r = new LinkedHashMap<>();
        r.put(K_AVAILABLE, true);
        r.put(K_TOTAL,     endpoints.size());
        r.put("endpoints", endpoints);
        return r;
    }
}
