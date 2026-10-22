package com.mahitha.tps.controller;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.http.MediaType;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:api-tests;DB_CLOSE_DELAY=-1", "processing.enabled=false"})
@AutoConfigureMockMvc
@AutoConfigureMetrics
class TransactionApiTests {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    private static final String VALID = """
            {"transactionId":"T1","requestId":"R1","accountId":"A1","transactionType":"CREDIT","amount":100,"currency":"USD","sequenceNumber":1}
            """;
    @BeforeEach void clean() {
        jdbc.update("DELETE FROM processing_attempts");
        jdbc.update("DELETE FROM ledger"); jdbc.update("DELETE FROM request_keys");
        jdbc.update("DELETE FROM transactions"); jdbc.update("DELETE FROM accounts");
        jdbc.update("INSERT INTO accounts(account_id,currency) VALUES ('A1','USD')");
    }
    @Test void acceptsAndReturnsExistingTransactionForReplay() throws Exception {
        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isAccepted()).andExpect(header().string("Location", "/api/v1/transactions/T1"))
                .andExpect(jsonPath("$.transaction.status").value("RECEIVED"));
        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        mvc.perform(get("/api/transactions/T1")).andExpect(status().isOk());
        mvc.perform(get("/api/transactions/summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.countsByStatus.RECEIVED").value(1));
    }
    @Test void rejectsMalformedAndConflictingRequests() throws Exception {
        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(VALID.replace("100", "-10")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(VALID.replace("CREDIT", "UNKNOWN")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(VALID)).andExpect(status().isAccepted());
        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(VALID.replace("100", "200")))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/transactions/missing")).andExpect(status().isNotFound());
        mvc.perform(get("/api/accounts/A1/USD/ledger?limit=0")).andExpect(status().isBadRequest());
    }
    @Test void malformedBatchRowsDoNotPreventGoodRows() throws Exception {
        String batch = "[" + VALID.replace("CREDIT", "UNKNOWN") + ",null," + VALID + "]";
        mvc.perform(post("/api/transactions/batch").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].outcome").value("INVALID"))
                .andExpect(jsonPath("$[1].outcome").value("INVALID"))
                .andExpect(jsonPath("$[2].outcome").value("ACCEPTED"));
    }
    @Test void versionedAccountAndRequestApis() throws Exception {
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(
                "{\"accountId\":\"NEW\",\"currency\":\"INR\",\"openingBalance\":50,\"startingSequence\":4}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.nextSequence").value(4));
        mvc.perform(get("/api/v1/accounts/NEW")).andExpect(status().isOk()).andExpect(jsonPath("$.balance").value(50));
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(
                "{\"accountId\":\"BAD\",\"currency\":\"INR\",\"openingBalance\":-1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/transactions").contentType(MediaType.APPLICATION_JSON).content(VALID.replace("A1", "UNKNOWN")))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/transactions").contentType(MediaType.APPLICATION_JSON).content(VALID)).andExpect(status().isAccepted());
        mvc.perform(get("/api/v1/requests/R1")).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("ACCEPTED"));
        mvc.perform(get("/api/v1/processing/summary")).andExpect(status().isOk()).andExpect(jsonPath("$.requestCounts[0].TOTAL").value(1));
        mvc.perform(get("/api/v1/transactions/T1/attempts")).andExpect(status().isOk());
    }
    @Test void exposesHealthAndPrometheus() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }
}
