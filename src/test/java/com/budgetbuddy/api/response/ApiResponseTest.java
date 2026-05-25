package com.budgetbuddy.api.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

/**
 * Pins the wire envelope. iOS will deserialize against this exact
 * shape, so any change to field names or null-omission semantics
 * needs to fail loudly here first.
 */
class ApiResponseTest {

    private final ObjectMapper mapper = new ObjectMapper()
            // Spring auto-registers JavaTimeModule; the test creates a
            // bare ObjectMapper, so register it explicitly to mirror
            // the production serialization behavior.
            .registerModule(new JavaTimeModule());

    @Test
    void okFactory_populatesStatusAndData() {
        final ApiResponse<String> r = ApiResponse.ok("hello", "corr-1");
        assertEquals(ApiResponse.Status.ok, r.status());
        assertEquals("hello", r.data());
        assertNull(r.error());
        assertEquals("corr-1", r.correlationId());
        assertNotNull(r.timestamp());
    }

    @Test
    void errorFactory_populatesStatusAndError() {
        final ApiResponse<String> r = ApiResponse.error("CODE", "boom", "corr-2");
        assertEquals(ApiResponse.Status.error, r.status());
        assertNull(r.data());
        assertNotNull(r.error());
        assertEquals("CODE", r.error().code());
        assertEquals("boom", r.error().message());
    }

    @Test
    void serialisation_omitsNullFields() throws Exception {
        // The success path should NOT include a "error":null entry —
        // wire compactness + clean iOS Optional decoding.
        final ApiResponse<String> r = ApiResponse.ok("x", "c1");
        final String json = mapper.writeValueAsString(r);
        org.junit.jupiter.api.Assertions.assertEquals(
                false, json.contains("\"error\""),
                "success response should not serialize the null error field");
    }

    @Test
    void errorResponse_omitsDataField() throws Exception {
        final ApiResponse<String> r = ApiResponse.error("E", "m", "c1");
        final String json = mapper.writeValueAsString(r);
        org.junit.jupiter.api.Assertions.assertEquals(
                false, json.contains("\"data\""),
                "error response should not serialize the null data field");
    }

    @Test
    void roundTrip_preservesFields() throws Exception {
        final ApiResponse<TestPayload> original = ApiResponse.ok(
                new TestPayload("name", 42), "corr-3");
        final String json = mapper.writeValueAsString(original);
        @SuppressWarnings("unchecked")
        final ApiResponse<TestPayload> decoded =
                mapper.readerFor(ApiResponse.class).readValue(json);
        assertEquals(ApiResponse.Status.ok, decoded.status());
        assertEquals("corr-3", decoded.correlationId());
    }

    record TestPayload(String name, int value) {}
}
