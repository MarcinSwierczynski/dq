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
                .andExpect(jsonPath("$.summary.resultCount").doesNotExist())
                .andExpect(jsonPath("$.summary.errorCount").value(0))
                .andExpect(jsonPath("$.summary.byDecision.VALID").value(4))
                .andExpect(jsonPath("$.summary.byDecision.INVALID").value(4))
                .andExpect(jsonPath("$.summary.byDecision.NOT_APPLICABLE").value(4))
                // Findings only: r2/vatFormat, r3/countryBlocked, r3/vatFormat (ERROR) and r3/ibanFormat (WARNING).
                .andExpect(jsonPath("$.summary.bySeverity.ERROR").value(3))
                .andExpect(jsonPath("$.summary.bySeverity.WARNING").value(1))
                .andExpect(jsonPath("$.summary.bySeverity.INFO").value(0))
                .andExpect(jsonPath("$.records[0].recordIndex").doesNotExist())
                .andExpect(jsonPath("$.summary.status").value("COMPLETED"))
                .andExpect(jsonPath("$.records", hasSize(3)))
                // The §5 table, one entry per record, one column per rule.
                .andExpect(jsonPath("$.records[0].recordId").value("r1"))
                .andExpect(jsonPath("$.records[0].results.countryBlocked.decision").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.records[0].results.vatFormat.decision").value("VALID"))
                .andExpect(jsonPath("$.records[0].results.ibanFormat.decision").value("VALID"))
                .andExpect(jsonPath("$.records[0].results.deVatPrefix.decision").value("VALID"))
                .andExpect(jsonPath("$.records[1].recordId").value("r2"))
                .andExpect(jsonPath("$.records[1].results.countryBlocked.decision").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.records[1].results.vatFormat.decision").value("INVALID"))
                .andExpect(jsonPath("$.records[1].results.ibanFormat.decision").value("VALID"))
                // deVatPrefix is scoped to DE: an FR record is out of scope and its logic never runs.
                .andExpect(jsonPath("$.records[1].results.deVatPrefix.decision").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.records[1].results.deVatPrefix.provenance")
                        .value("applies to DE only; record country is FR"))
                .andExpect(jsonPath("$.records[1].results.deVatPrefix.severity").doesNotExist())
                .andExpect(jsonPath("$.records[2].recordId").value("r3"))
                .andExpect(jsonPath("$.records[2].results.countryBlocked.decision").value("INVALID"))
                .andExpect(jsonPath("$.records[2].results.vatFormat.decision").value("INVALID"))
                .andExpect(jsonPath("$.records[2].results.ibanFormat.decision").value("INVALID"))
                // Provenance says why, in plain words.
                .andExpect(jsonPath("$.records[1].results.vatFormat.provenance").value("len 4 instead of 9"))
                .andExpect(jsonPath("$.records[2].results.vatFormat.provenance").value("no vatId"))
                .andExpect(jsonPath("$.records[2].results.ibanFormat.provenance").value("empty iban"))
                .andExpect(jsonPath("$.records[2].results.countryBlocked.provenance").value("country ZZ is blocked"))
                .andExpect(jsonPath("$.records[0].results.countryBlocked.provenance").value("country DE is not blocked"))
                .andExpect(jsonPath("$.records[0].results.vatFormat.provenance").value("len 11"))
                // Severity only accompanies a finding: absent on VALID and NOT_APPLICABLE.
                .andExpect(jsonPath("$.records[0].results.vatFormat.severity").doesNotExist())
                .andExpect(jsonPath("$.records[0].results.ibanFormat.severity").doesNotExist())
                .andExpect(jsonPath("$.records[0].results.countryBlocked.severity").doesNotExist())
                .andExpect(jsonPath("$.records[2].results.countryBlocked.severity").value("ERROR"))
                .andExpect(jsonPath("$.records[1].results.vatFormat.severity").value("ERROR"))
                .andExpect(jsonPath("$.records[2].results.ibanFormat.severity").value("WARNING"))
                .andExpect(jsonPath("$.records[0].errors", hasSize(0)));
    }

    @Test
    void rulesAreListedInCatalogOrderWithinARecord() throws Exception {
        String body = mvc.perform(post("/validations").contentType(MediaType.APPLICATION_JSON).content(fixture()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode results = json.readTree(body).get("records").get(0).get("results");

        assertThat(results.propertyNames()).containsExactly("countryBlocked", "vatFormat", "ibanFormat", "deVatPrefix");
    }

    @Test
    void faultDemoReportsErrorsAndStillValidatesTheRest() throws Exception {
        mvc.perform(post("/validations").contentType(MediaType.APPLICATION_JSON).content(faultDemo()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.recordsRead").value(5))
                .andExpect(jsonPath("$.summary.resultCount").doesNotExist())
                .andExpect(jsonPath("$.summary.byDecision.VALID").value(4))
                .andExpect(jsonPath("$.summary.byDecision.INVALID").value(5))
                .andExpect(jsonPath("$.summary.byDecision.NOT_APPLICABLE").value(6))
                .andExpect(jsonPath("$.summary.errorCount").value(2))
                .andExpect(jsonPath("$.records", hasSize(5)))
                .andExpect(jsonPath("$.records[3].recordId").value("r4"))
                .andExpect(jsonPath("$.records[3].results.countryBlocked.decision").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.records[3].results.vatFormat.decision").value("INVALID"))
                .andExpect(jsonPath("$.records[3].results.ibanFormat").doesNotExist())
                .andExpect(jsonPath("$.records[3].results.deVatPrefix.provenance")
                        .value("applies to DE only; record has no country"))
                .andExpect(jsonPath("$.records[3].errors", hasSize(1)))
                .andExpect(jsonPath("$.records[3].errors[0].ruleId").value("ibanFormat"))
                .andExpect(jsonPath("$.records[3].errors[0].kind").value("MISSING_REQUIRED_FIELD"))
                .andExpect(jsonPath("$.records[3].errors[0].fieldsRead.country").value("<absent>"))
                .andExpect(jsonPath("$.records[3].errors[0].cause").doesNotExist())
                .andExpect(jsonPath("$.records[4].recordId").value("#4"))
                .andExpect(jsonPath("$.records[4].errors[0].recordIndex").doesNotExist())
                .andExpect(jsonPath("$.records[4].results").isEmpty())
                .andExpect(jsonPath("$.records[4].errors[0].kind").value("UNREADABLE_RECORD"))
                .andExpect(jsonPath("$.records[4].errors[0].ruleId").doesNotExist());
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
                .andExpect(jsonPath("$.summary.rulesSelected").value(2))
                .andExpect(jsonPath("$.records", hasSize(3)))
                .andExpect(jsonPath("$.records[0].results.vatFormat.decision").value("VALID"))
                .andExpect(jsonPath("$.records[0].results.countryBlocked").doesNotExist());
        mvc.perform(post("/validations?category=tax&category=banking&country=DE")
                        .contentType(MediaType.APPLICATION_JSON).content(fixture()))
                .andExpect(jsonPath("$.summary.rulesSelected").value(3));
        mvc.perform(post("/validations?category=tax&country=FR")
                        .contentType(MediaType.APPLICATION_JSON).content(fixture()))
                .andExpect(jsonPath("$.summary.rulesSelected").value(1));
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
    void streamsOneLinePerRecordThenTheSummary() throws Exception {
        List<JsonNode> lines = ndjson("/validations", faultDemo());

        assertThat(lines).hasSize(5 + 1);
        assertThat(lines.subList(0, 5)).extracting(l -> l.get("type").asString()).containsOnly("record");
        assertThat(lines.subList(0, 5)).extracting(l -> l.get("recordId").asString())
                .containsExactly("r1", "r2", "r3", "r4", "#4");
        JsonNode r2 = lines.get(1);
        assertThat(r2.get("results").get("vatFormat").get("decision").asString()).isEqualTo("INVALID");
        assertThat(r2.get("results").get("vatFormat").get("severity").asString()).isEqualTo("ERROR");
        assertThat(r2.get("results").get("ibanFormat").has("severity")).as("VALID has no severity").isFalse();
        assertThat(r2.get("results").get("countryBlocked").has("severity")).as("N/A has no severity").isFalse();
        assertThat(r2.get("results").get("vatFormat").get("provenance").asString()).isEqualTo("len 4 instead of 9");
        JsonNode r4 = lines.get(3);
        assertThat(r4.get("results").propertyNames()).containsExactly("countryBlocked", "vatFormat", "deVatPrefix");
        assertThat(r4.get("errors").get(0).get("kind").asString()).isEqualTo("MISSING_REQUIRED_FIELD");
        assertThat(lines.get(4).get("errors").get(0).get("kind").asString()).isEqualTo("UNREADABLE_RECORD");
        JsonNode last = lines.getLast();
        assertThat(last.get("type").asString()).isEqualTo("summary");
        assertThat(last.has("resultCount")).isFalse();
        assertThat(last.get("byDecision").get("INVALID").asInt()).isEqualTo(5);
        assertThat(last.get("errorCount").asInt()).isEqualTo(2);
    }

    @Test
    void streamingHonoursFiltersAndBatchSize() throws Exception {
        List<JsonNode> lines = ndjson("/validations?category=tax&batchSize=1", fixture());

        assertThat(lines).hasSize(3 + 1);
        assertThat(lines.getFirst().get("results").propertyNames()).containsExactly("vatFormat", "deVatPrefix");
        assertThat(lines.getLast().get("rulesSelected").asInt()).isEqualTo(2);
    }

    @Test
    void truncatedStreamingInputEndsWithAnAbortedLine() throws Exception {
        String truncated = "[{\"id\":\"r1\",\"country\":\"DE\",\"vatId\":\"DE111111111\",\"iban\":\"DE1\"}, {\"id\":";
        List<JsonNode> lines = ndjson("/validations", truncated);

        assertThat(lines).hasSize(1 + 1);
        assertThat(lines.getFirst().get("type").asString()).isEqualTo("record");
        assertThat(lines.getFirst().get("recordId").asString()).isEqualTo("r1");
        assertThat(lines.getFirst().get("results").size()).isEqualTo(4);
        JsonNode last = lines.getLast();
        assertThat(last.get("type").asString()).isEqualTo("aborted");
        assertThat(last.get("reason").asString()).contains("malformed JSON");
        assertThat(last.get("summary").get("status").asString()).isEqualTo("ABORTED");
        assertThat(last.get("summary").has("resultCount")).isFalse();
        assertThat(last.get("summary").get("byDecision").get("VALID").asInt()
                + last.get("summary").get("byDecision").get("INVALID").asInt()
                + last.get("summary").get("byDecision").get("NOT_APPLICABLE").asInt()).isEqualTo(4);
    }
}
