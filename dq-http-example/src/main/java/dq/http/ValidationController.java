package dq.http;

import dq.engine.api.CollectingListener;
import dq.engine.api.DqEngine;
import dq.engine.api.RecordSourceException;
import dq.engine.api.RecordSources;
import dq.engine.api.RunSummary;
import dq.engine.api.ValidationRunException;
import dq.http.Views.ErrorView;
import dq.http.Views.SummaryView;
import dq.http.Views.ValidationResponse;
import java.io.InputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /validations} with a JSON array body; returns {@code {summary, results, errors}}. Results
 * are buffered, so the request is capped at {@code dq.http.max-records} (413 beyond).
 */
@RestController
class ValidationController {
    private final DqEngine engine;
    private final int maxRecords;

    ValidationController(DqEngine engine, @Value("${dq.http.max-records:10000}") int maxRecords) {
        this.engine = engine;
        this.maxRecords = maxRecords;
    }

    @PostMapping(path = "/validations", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ValidationResponse validate(InputStream body) {
        var listener = new CollectingListener();
        RunSummary summary;
        try {
            summary = engine.validate(new RecordLimit(RecordSources.jsonArray(body), maxRecords), listener);
        } catch (ValidationRunException e) {
            throw switch (e.getCause()) {
                case RecordLimit.Exceeded tooMany -> problem(HttpStatus.valueOf(413), "Too many records",
                        "This endpoint buffers results and accepts at most " + maxRecords
                                + " records. Split the input into smaller requests.");
                case RecordSourceException invalid -> problem(HttpStatus.BAD_REQUEST, "Invalid JSON input",
                        invalid.getMessage());
                case null, default -> e;
            };
        }
        return new ValidationResponse(SummaryView.of(summary), listener.results(),
                listener.errors().stream().map(ErrorView::of).toList());
    }

    private static ErrorResponseException problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return new ErrorResponseException(status, problem, null);
    }
}
