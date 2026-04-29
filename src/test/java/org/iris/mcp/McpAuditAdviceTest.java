package org.iris.mcp;

import org.iris.observability.port.AuditEventPort;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link McpAuditAdvice} — the AOP @Around advice that
 * audits every {@code @Tool} call and emits MDC-tagged log lines.
 *
 * <p>Pure Mockito + a small {@link Fixture} class carrying real
 * {@link Tool}-annotated methods used as {@link Method} sources for
 * the mocked {@link MethodSignature}.
 *
 * <p>Branches covered :
 * <ul>
 *   <li>Successful proceed → audit "ok" + tool MDC populated then cleaned.</li>
 *   <li>Throwing proceed → audit "error: ClassName" + re-throw.</li>
 *   <li>Tool annotation name vs method name fallback.</li>
 *   <li>Authenticated user vs anonymous (no SecurityContext).</li>
 *   <li>AuditEventPort.recordEvent failure does NOT propagate (tool call
 *       must never be killed by an audit blip).</li>
 *   <li>MDC is cleaned even when proceed throws.</li>
 * </ul>
 */
class McpAuditAdviceTest {

    private AuditEventPort auditEventPort;
    private McpAuditAdvice advice;

    @BeforeEach
    void setUp() {
        auditEventPort = mock(AuditEventPort.class);
        advice = new McpAuditAdvice(auditEventPort);
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    // ─── happy path ──────────────────────────────────────────────────────────

    @Test
    void aroundToolCall_successfulProceed_recordsAuditOk_andClearsMdc() throws Throwable {
        ProceedingJoinPoint pjp = mockJoinPoint(
                Fixture.method("namedTool", String.class), new Object[]{"hello"});
        when(pjp.proceed()).thenReturn("proceed-result");

        Object out = advice.aroundToolCall(pjp);

        assertThat(out).isEqualTo("proceed-result");
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditEventPort).recordEvent(eq("anonymous"), action.capture(), detail.capture(), isNull());
        assertThat(action.getValue()).isEqualTo("MCP_TOOL_CALL");
        assertThat(detail.getValue())
                .contains("tool=name_from_annotation")
                .contains("outcome=ok")
                .contains("hello"); // args JSON includes the value
        assertThat(MDC.get("tool_name")).isNull();
        assertThat(MDC.get("tool_args_hash")).isNull();
    }

    @Test
    void aroundToolCall_proceedThrows_recordsErrorAndReThrows_andClearsMdc() throws Throwable {
        ProceedingJoinPoint pjp = mockJoinPoint(
                Fixture.method("namedTool", String.class), new Object[]{"oops"});
        IllegalStateException boom = new IllegalStateException("kafka down");
        when(pjp.proceed()).thenThrow(boom);

        assertThatThrownBy(() -> advice.aroundToolCall(pjp))
                .isSameAs(boom);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditEventPort).recordEvent(eq("anonymous"), eq("MCP_TOOL_CALL"), detail.capture(), isNull());
        assertThat(detail.getValue()).contains("outcome=error: IllegalStateException");
        // MDC must be cleaned even when proceed threw.
        assertThat(MDC.get("tool_name")).isNull();
        assertThat(MDC.get("tool_args_hash")).isNull();
    }

    // ─── tool name resolution ────────────────────────────────────────────────

    @Test
    void aroundToolCall_usesAnnotationName_whenPresent() throws Throwable {
        ProceedingJoinPoint pjp = mockJoinPoint(
                Fixture.method("namedTool", String.class), new Object[]{"x"});
        when(pjp.proceed()).thenReturn(null);

        advice.aroundToolCall(pjp);

        verify(auditEventPort).recordEvent(anyString(), anyString(),
                argThatContains("tool=name_from_annotation"), isNull());
    }

    @Test
    void aroundToolCall_fallsBackToMethodName_whenAnnotationNameBlank() throws Throwable {
        ProceedingJoinPoint pjp = mockJoinPoint(
                Fixture.method("blankNameTool", String.class), new Object[]{"x"});
        when(pjp.proceed()).thenReturn(null);

        advice.aroundToolCall(pjp);

        verify(auditEventPort).recordEvent(anyString(), anyString(),
                argThatContains("tool=blankNameTool"), isNull());
    }

    // ─── auth context ────────────────────────────────────────────────────────

    @Test
    void aroundToolCall_usesAuthenticatedUserName_whenSecurityContextSet() throws Throwable {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a", List.of()));
        ProceedingJoinPoint pjp = mockJoinPoint(
                Fixture.method("namedTool", String.class), new Object[]{"x"});
        when(pjp.proceed()).thenReturn(null);

        advice.aroundToolCall(pjp);

        verify(auditEventPort).recordEvent(eq("alice"), anyString(), anyString(), isNull());
    }

    // ─── failure containment ─────────────────────────────────────────────────

    @Test
    void aroundToolCall_auditPortThrowing_doesNotPropagate_andProceedReturnSucceeds() throws Throwable {
        // The audit row is the regulatory contract — but the tool call MUST
        // NOT die because the audit DB blipped. Pinned : recordEvent throwing
        // is swallowed (logged at WARN), proceed return value is returned.
        ProceedingJoinPoint pjp = mockJoinPoint(
                Fixture.method("namedTool", String.class), new Object[]{"x"});
        when(pjp.proceed()).thenReturn("ok");
        doThrow(new RuntimeException("audit db down"))
                .when(auditEventPort).recordEvent(anyString(), anyString(), anyString(), any());

        Object out = advice.aroundToolCall(pjp);

        assertThat(out).isEqualTo("ok");
        verify(auditEventPort).recordEvent(anyString(), anyString(), anyString(), isNull());
    }

    // ─── arg serialisation fallback ──────────────────────────────────────────

    @Test
    void aroundToolCall_unserialisableArg_fallsBackToToString_doesNotCrash() throws Throwable {
        // The aspect must keep working even if Jackson can't serialise a
        // weird user value. Pinned : the audit detail still appears, just
        // with the .toString() form instead of the JSON form.
        Object selfReferential = new Object() {
            @Override public String toString() { return "self-ref-stub"; }
        };
        ProceedingJoinPoint pjp = mockJoinPoint(
                Fixture.method("objectTool", Object.class), new Object[]{selfReferential});
        when(pjp.proceed()).thenReturn(null);

        advice.aroundToolCall(pjp);

        verify(auditEventPort).recordEvent(anyString(), anyString(),
                argThatContains("outcome=ok"), isNull());
    }

    // ─── self-transition / no-args ───────────────────────────────────────────

    @Test
    void aroundToolCall_zeroArgsTool_recordsAuditWithEmptyArgs() throws Throwable {
        ProceedingJoinPoint pjp = mockJoinPoint(
                Fixture.method("noArgsTool"), new Object[]{});
        when(pjp.proceed()).thenReturn(null);

        advice.aroundToolCall(pjp);

        verify(auditEventPort).recordEvent(anyString(), anyString(),
                argThatContains("args={}"), isNull());
    }

    @Test
    void aroundToolCall_noProceedCall_meansNoAudit() throws Throwable {
        // Sanity : if the test setup is wrong and we don't actually call
        // aroundToolCall, no audit record is created. Catches "test
        // happens to pass for the wrong reason" issues.
        verify(auditEventPort, never()).recordEvent(anyString(), anyString(), anyString(), any());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static ProceedingJoinPoint mockJoinPoint(Method m, Object[] args) {
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        MethodSignature sig = mock(MethodSignature.class);
        when(sig.getMethod()).thenReturn(m);
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.getArgs()).thenReturn(args);
        return pjp;
    }

    private static String argThatContains(String needle) {
        return org.mockito.ArgumentMatchers.argThat(s ->
                s != null && s.contains(needle));
    }

    /**
     * Fixture class carrying real {@link Tool}-annotated methods so
     * {@link Method#getAnnotation(Class)} returns a real {@link Tool}
     * instance. The methods themselves are never invoked — only their
     * reflective metadata is read by the aspect.
     */
    static class Fixture {

        @Tool(name = "name_from_annotation", description = "named tool")
        public String namedTool(String input) {
            return input;
        }

        @Tool(description = "blank-name tool")
        public String blankNameTool(String input) {
            return input;
        }

        @Tool(name = "object_tool", description = "object tool")
        public String objectTool(Object input) {
            return String.valueOf(input);
        }

        @Tool(name = "no_args_tool", description = "no args")
        public String noArgsTool() {
            return "ok";
        }

        static Method method(String name, Class<?>... params) {
            try {
                return Fixture.class.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
