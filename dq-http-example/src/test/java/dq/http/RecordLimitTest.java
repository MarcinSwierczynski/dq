package dq.http;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "dq.http.max-records=2")
@AutoConfigureMockMvc
class RecordLimitTest {

    @Autowired
    MockMvc mvc;

    @Test
    void bufferedResponseOverTheLimitIs413() throws Exception {
        mvc.perform(post("/validations").contentType(MediaType.APPLICATION_JSON)
                        .content(ValidationControllerTest.fixture()))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.title").value("Too many records"))
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("at most 2 records")));
    }

    @Test
    void atTheLimitIsAccepted() throws Exception {
        mvc.perform(post("/validations").contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":\"a\"},{\"id\":\"b\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.recordsRead").value(2));
    }
}
