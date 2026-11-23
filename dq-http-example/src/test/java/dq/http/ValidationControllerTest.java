package dq.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
class ValidationControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    static String fixture() throws IOException {
        try (InputStream in = ValidationControllerTest.class.getResourceAsStream("/fixture.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The fixture plus a record without country/vatId and a non-object element. */
    static String faultDemo() throws IOException {
        String fixture = fixture().strip();
        return fixture.substring(0, fixture.length() - 1) + ", {\"id\":\"r4\",\"iban\":\"DE1\"}, 42]";
    }

    @Test
    void validatesTheFixture() throws Exception {
        mvc.perform(post("/validations").contentType(MediaType.APPLICATION_JSON).content(fixture()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.resultCount").value(9))
                .andExpect(jsonPath("$.summary.errorCount").value(0))
                .andExpect(jsonPath("$.summary.byDecision.VALID").value(3))
                .andExpect(jsonPath("$.summary.byDecision.INVALID").value(4))
                .andExpect(jsonPath("$.summary.byDecision.NOT_APPLICABLE").value(2))
                .andExpect(jsonPath("$.summary.bySeverity.ERROR").value(6))
                .andExpect(jsonPath("$.summary.bySeverity.WARNING").value(3))
                .andExpect(jsonPath("$.summary.status").value("COMPLETED"))
                .andExpect(jsonPath("$.results", hasSize(9)))
                .andExpect(jsonPath("$.results[4].ruleId").value("vatFormat"))
                .andExpect(jsonPath("$.results[4].recordId").value("r2"))
                .andExpect(jsonPath("$.results[4].decision").value("INVALID"))
                .andExpect(jsonPath("$.results[4].provenance.fieldsRead.vatId").value("FR22"))
                .andExpect(jsonPath("$.results[4].provenance.computedValue").value("bad"))
                .andExpect(jsonPath("$.errors", hasSize(0)));
    }

    @Test
    void faultDemoReportsErrorsAndStillValidatesTheRest() throws Exception {
        mvc.perform(post("/validations").contentType(MediaType.APPLICATION_JSON).content(faultDemo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.recordsRead").value(5))
                .andExpect(jsonPath("$.summary.resultCount").value(11))
                .andExpect(jsonPath("$.summary.errorCount").value(2))
                .andExpect(jsonPath("$.results[9].recordId").value("r4"))
                .andExpect(jsonPath("$.results[9].ruleId").value("countryBlocked"))
                .andExpect(jsonPath("$.results[9].decision").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.results[10].ruleId").value("vatFormat"))
                .andExpect(jsonPath("$.results[10].decision").value("INVALID"))
                .andExpect(jsonPath("$.errors[0].ruleId").value("ibanFormat"))
                .andExpect(jsonPath("$.errors[0].recordId").value("r4"))
                .andExpect(jsonPath("$.errors[0].kind").value("MISSING_REQUIRED_FIELD"))
                .andExpect(jsonPath("$.errors[0].fieldsRead.country").value("<absent>"))
                .andExpect(jsonPath("$.errors[0].cause").doesNotExist())
                .andExpect(jsonPath("$.errors[1].kind").value("UNREADABLE_RECORD"))
                .andExpect(jsonPath("$.errors[1].recordIndex").value(4))
                .andExpect(jsonPath("$.errors[1].ruleId").doesNotExist());
    }

    @Test
    void invalidJsonIsAProblemDetail400() throws Exception {
        mvc.perform(post("/validations").contentType(MediaType.APPLICATION_JSON).content("[{\"id\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid JSON input"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void invalidRunOptionsAre400() throws Exception {
        mvc.perform(post("/validations?batchSize=0").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("batchSize")));
        mvc.perform(post("/validations?status=NOPE").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aBatchSizeAboveTheCapIs400OnBothEndpoints() throws Exception {
        mvc.perform(post("/validations?batchSize=10001").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("at most 10000")));
        mvc.perform(post("/validations?batchSize=2147483647").contentType(MediaType.APPLICATION_JSON)
                        .accept(ValidationController.NDJSON).content("[]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid run options"));
        mvc.perform(post("/validations?batchSize=10000").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isOk());
    }

    @Test
    void categoryFilterLowersRulesSelected() throws Exception {
        mvc.perform(post("/validations?category=tax").contentType(MediaType.APPLICATION_JSON).content(fixture()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.rulesSelected").value(1))
                .andExpect(jsonPath("$.results", hasSize(3)));
        mvc.perform(post("/validations?category=tax&category=banking&country=DE")
                        .contentType(MediaType.APPLICATION_JSON).content(fixture()))
                .andExpect(jsonPath("$.summary.rulesSelected").value(2));
        mvc.perform(post("/validations?status=DRAFT").contentType(MediaType.APPLICATION_JSON).content(fixture()))
                .andExpect(jsonPath("$.summary.rulesSelected").value(0))
                .andExpect(jsonPath("$.summary.status").value("COMPLETED"));
    }

    private List<JsonNode> ndjson(String path, String body) throws Exception {
        MvcResult started = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                        .accept(ValidationController.NDJSON).content(body))
                .andExpect(request().asyncStarted())
                .andReturn();
        String text = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(ValidationController.NDJSON))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return text.lines().map(json::readTree).toList();
    }

    @Test
    void streamsOneLinePerOutcomeThenTheSummary() throws Exception {
        List<JsonNode> lines = ndjson("/validations", faultDemo());

        assertThat(lines).hasSize(11 + 2 + 1);
        assertThat(lines.getFirst().get("type").asString()).isEqualTo("result");
        assertThat(lines.getFirst().get("ruleId").asString()).isEqualTo("countryBlocked");
        assertThat(lines.getFirst().get("recordId").asString()).isEqualTo("r1");
        assertThat(lines).extracting(l -> l.get("type").asString())
                .containsSubsequence("result", "error", "error", "summary")
                .filteredOn("error"::equals).hasSize(2);
        JsonNode last = lines.getLast();
        assertThat(last.get("type").asString()).isEqualTo("summary");
        assertThat(last.get("resultCount").asInt()).isEqualTo(11);
        assertThat(last.get("errorCount").asInt()).isEqualTo(2);
    }

    @Test
    void streamingHonoursFiltersAndBatchSize() throws Exception {
        List<JsonNode> lines = ndjson("/validations?category=tax&batchSize=1", fixture());

        assertThat(lines).hasSize(3 + 1);
        assertThat(lines.getLast().get("rulesSelected").asInt()).isEqualTo(1);
    }

    @Test
    void truncatedStreamingInputEndsWithAnAbortedLine() throws Exception {
        String truncated = "[{\"id\":\"r1\",\"country\":\"DE\",\"vatId\":\"DE111111111\",\"iban\":\"DE1\"}, {\"id\":";
        List<JsonNode> lines = ndjson("/validations", truncated);

        assertThat(lines).hasSize(3 + 1);
        JsonNode last = lines.getLast();
        assertThat(last.get("type").asString()).isEqualTo("aborted");
        assertThat(last.get("reason").asString()).contains("malformed JSON");
        assertThat(last.get("summary").get("status").asString()).isEqualTo("ABORTED");
        assertThat(last.get("summary").get("resultCount").asInt()).isEqualTo(3);
    }
}
