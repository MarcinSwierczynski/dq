package dq.http;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ValidationControllerTest {

    @Autowired
    MockMvc mvc;

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
}
