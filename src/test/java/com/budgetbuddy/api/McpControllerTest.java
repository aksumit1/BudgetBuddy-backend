package com.budgetbuddy.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.budgetbuddy.mcp.McpProtocolHandler;
import com.budgetbuddy.mcp.McpPromptRegistry;
import com.budgetbuddy.mcp.McpResourceRegistry;
import com.budgetbuddy.mcp.McpSessionRegistry;
import com.budgetbuddy.mcp.McpToolRegistry;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.security.JwtTokenProvider;
import com.budgetbuddy.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Controller-layer tests for {@link McpController}. Verifies the
 * pieces added in the v1.1 expansion:
 *
 * <ul>
 *   <li>SSE upgrade on Accept: text/event-stream
 *   <li>GET /mcp/sessions returns the user's live sessions
 *   <li>DELETE /mcp/sessions/{id} revokes a session
 *   <li>GET /mcp/consent / PUT /mcp/consent round-trips persistent consent
 * </ul>
 *
 * <p>Uses MockMvc.standaloneSetup with a real protocol handler so the
 * SSE branch exercises the full envelope build path; the user lookup
 * is mocked so we don't need DynamoDB.
 */
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom — MockMvc methods declare Exception")
final class McpControllerTest {

    private MockMvc mockMvc;
    private UserService userService;
    private McpSessionRegistry sessionRegistry;
    private JwtTokenProvider jwtTokenProvider;
    private UserTable user;

    @BeforeEach
    void setUp() {
        final ObjectMapper mapper = new ObjectMapper();
        sessionRegistry = new McpSessionRegistry();
        final McpToolRegistry tools = new McpToolRegistry(List.of());
        final McpPromptRegistry prompts = new McpPromptRegistry(List.of());
        final McpResourceRegistry resources = new McpResourceRegistry(List.of());
        final McpProtocolHandler handler =
                new McpProtocolHandler(tools, sessionRegistry, mapper, prompts, resources);

        userService = mock(UserService.class);
        user = new UserTable();
        user.setUserId("u1");
        user.setEmail("test@example.com");
        when(userService.findByEmail(anyString())).thenReturn(Optional.of(user));
        when(userService.updateUser(any(UserTable.class))).thenAnswer(inv -> inv.getArgument(0));

        jwtTokenProvider = mock(JwtTokenProvider.class);
        when(jwtTokenProvider.generateMcpConnectionToken(anyString(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn("fake-mcp-jwt");

        final McpController controller =
                new McpController(userService, handler, sessionRegistry, mapper, jwtTokenProvider);

        // Boot 4's default message converter chain uses Jackson 3, which can't
        // deserialize Jackson 2's com.fasterxml.jackson.databind.JsonNode that
        // the MCP controller declares as @RequestBody. Pin a Jackson 2
        // converter onto the standalone MockMvc so test calls hit the same
        // path as production WebMvcConfig.configureMessageConverters.
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(
                        new org.springframework.http.converter.json
                                .MappingJackson2HttpMessageConverter(mapper),
                        // text/event-stream SSE responses are written as plain Strings,
                        // so register a String converter alongside Jackson.
                        new org.springframework.http.converter
                                .StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8))
                .setCustomArgumentResolvers(
                        new org.springframework.security.web.method.annotation
                                .AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate() {
        final UserDetails details =
                User.withUsername("test@example.com").password("n/a").authorities("USER").build();
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                details, "n/a", details.getAuthorities()));
    }

    @Test
    void initializeWithSseAcceptReturnsEventStream() throws Exception {
        authenticate();
        final String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}";
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept("text/event-stream")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                .andExpect(header().exists("Mcp-Session-Id"))
                .andExpect(content().string(Matchers.startsWith("event: message")));
    }

    @Test
    void initializeWithJsonAcceptReturnsJson() throws Exception {
        authenticate();
        final String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}";
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().exists("Mcp-Session-Id"))
                .andExpect(jsonPath("$.result.protocolVersion").value("2024-11-05"));
    }

    @Test
    void listSessionsReturnsActiveSessionsForUser() throws Exception {
        authenticate();
        sessionRegistry.create("u1");
        sessionRegistry.create("u1");
        sessionRegistry.create("other-user"); // must not appear
        mockMvc.perform(get("/mcp/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void revokeSessionTerminates() throws Exception {
        authenticate();
        final var session = sessionRegistry.create("u1");
        mockMvc.perform(delete("/mcp/sessions/" + session.sessionId()))
                .andExpect(status().isNoContent());
        org.junit.jupiter.api.Assertions.assertTrue(
                sessionRegistry.get(session.sessionId()).isEmpty(),
                "revoke must terminate the session");
    }

    @Test
    void revokeSessionRefusesCrossUser() throws Exception {
        authenticate();
        final var other = sessionRegistry.create("not-u1");
        mockMvc.perform(delete("/mcp/sessions/" + other.sessionId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void getConsentReadsPersistentFlag() throws Exception {
        authenticate();
        user.setMcpMoneyMovingConsent(Boolean.TRUE);
        user.setMcpConsentGrantedAt(Instant.parse("2026-05-01T00:00:00Z"));
        mockMvc.perform(get("/mcp/consent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persistent").value(true))
                .andExpect(jsonPath("$.grantedAt").value("2026-05-01T00:00:00Z"));
    }

    @Test
    void putConsentPersistsFlagAndTimestamp() throws Exception {
        authenticate();
        mockMvc.perform(put("/mcp/consent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"persistent\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persistent").value(true));
        org.junit.jupiter.api.Assertions.assertEquals(
                Boolean.TRUE, user.getMcpMoneyMovingConsent());
        org.junit.jupiter.api.Assertions.assertNotNull(user.getMcpConsentGrantedAt());
        verify(userService).updateUser(user);
    }

    @Test
    void issueConnectionTokenReturnsSignedJwt() throws Exception {
        authenticate();
        mockMvc.perform(post("/mcp/connection-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("fake-mcp-jwt"))
                .andExpect(jsonPath("$.expiresInSeconds").value(86400))
                .andExpect(jsonPath("$.userId").value("u1"));
    }

    @Test
    void putConsentClearsTimestampWhenSetToFalse() throws Exception {
        authenticate();
        user.setMcpMoneyMovingConsent(Boolean.TRUE);
        user.setMcpConsentGrantedAt(Instant.now());
        mockMvc.perform(put("/mcp/consent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"persistent\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persistent").value(false))
                .andExpect(jsonPath("$.grantedAt").doesNotExist());
        org.junit.jupiter.api.Assertions.assertEquals(
                Boolean.FALSE, user.getMcpMoneyMovingConsent());
        org.junit.jupiter.api.Assertions.assertNull(user.getMcpConsentGrantedAt());
    }

}
