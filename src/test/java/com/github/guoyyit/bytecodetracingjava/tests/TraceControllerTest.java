package com.github.guoyyit.bytecodetracingjava.tests;

import com.github.guoyyit.bytecodetracingjava.fixtures.HighVolumeFixture;
import com.github.guoyyit.bytecodetracingjava.service.TraceService;
import com.github.guoyyit.bytecodetracingjava.tracing.TraceHooks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end REST API behaviour over the tracing service. */
@SpringBootTest
@AutoConfigureMockMvc
class TraceControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TraceService service;

    @BeforeEach
    void ensureHooksInstalled() {
        // Other tests may have uninstalled the shared static hooks.
        TraceHooks.install(service.aggregator(), service.registry(), service.config());
    }

    @Test
    void configEndpointsReadAndUpdateSampling() throws Exception {
        mvc.perform(get("/api/trace/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled", is(true)))
                .andExpect(jsonPath("$.samplingRate", is(1.0)));

        mvc.perform(post("/api/trace/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"samplingRate\": 0.25, \"maxDepth\": 8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.samplingRate", is(0.25)))
                .andExpect(jsonPath("$.maxDepth", is(8)));

        // restore defaults for other tests
        mvc.perform(post("/api/trace/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"samplingRate\": 1.0, \"maxDepth\": 64}"))
                .andExpect(status().isOk());
    }

    @Test
    void invalidSamplingRateIsRejected() throws Exception {
        mvc.perform(post("/api/trace/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"samplingRate\": 1.5}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void instrumentThenSnapshotThenDetach() throws Exception {
        String className = "com.github.guoyyit.bytecodetracingjava.fixtures.HighVolumeFixture";
        try {
            mvc.perform(post("/api/trace/instrument")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"className\": \"" + className + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.instrumented", is(true)));

            HighVolumeFixture.loop(7);

            MvcResult snapshot = mvc.perform(get("/api/trace/snapshot")
                            .param("method", "HighVolumeFixture"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.methods.length()", greaterThanOrEqualTo(2)))
                    .andReturn();
            String body = snapshot.getResponse().getContentAsString();
            long sequence = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.sequence")).longValue();
            org.junit.jupiter.api.Assertions.assertTrue(sequence >= 8);

            mvc.perform(get("/api/trace/delta").param("since", "-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.events.length()", greaterThanOrEqualTo(8)))
                    .andExpect(jsonPath("$.gap", is(false)));

            mvc.perform(get("/api/trace/events")
                            .param("from", "0")
                            .param("limit", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()", is(5)));
        } finally {
            mvc.perform(delete("/api/trace/instrument").param("className", className))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.instrumented", is(false)));
        }

        mvc.perform(post("/api/trace/reset")).andExpect(status().isOk());
        HighVolumeFixture.loop(3);
        MvcResult after = mvc.perform(get("/api/trace/snapshot")).andReturn();
        long total = 0;
        java.util.List<java.util.Map<String, Object>> methods = com.jayway.jsonpath.JsonPath
                .read(after.getResponse().getContentAsString(), "$.methods");
        for (java.util.Map<String, Object> method : methods) {
            total += ((Number) method.get("count")).longValue();
        }
        assertEquals(0, total, "no events after detach + reset");
    }
}
