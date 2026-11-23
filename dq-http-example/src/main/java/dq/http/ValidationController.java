package dq.http;

import dq.engine.api.CollectingListener;
import dq.engine.api.DqEngine;
import dq.engine.api.RecordSourceException;
import dq.engine.api.RecordSources;
import dq.engine.api.RuleFilter;
import dq.engine.api.RuleStatus;
import dq.engine.api.RunOptions;
import dq.engine.api.RunSummary;
import dq.engine.api.ValidationRunException;
import dq.http.Views.ErrorView;
import dq.http.Views.SummaryView;
import dq.http.Views.ValidationResponse;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /validations} with a JSON array body.
 * <ul>
 *   <li>{@code Accept: application/json} — buffered {@code {summary, results, errors}}; capped at
 *       {@code dq.http.max-records} (413 beyond).</li>
 *   <li>{@code Accept: application/x-ndjson} — streamed, one line per outcome, then a {@code summary}
 *       line (or an {@code aborted} line); no record limit, bounded memory.</li>
 * </ul>
 * Optional query parameters {@code status}, {@code country}, {@code category} (repeatable; OR within a
 * parameter, AND across parameters) and {@code batchSize}.
 */
@RestController
class ValidationController {
    static final String NDJSON = "application/x-ndjson";

    private final DqEngine engine;
    private final JsonMapper json;
    private final int maxRecords;

    ValidationController(DqEngine engine, JsonMapper json, @Value("${dq.http.max-records:10000}") int maxRecords) {
        this.engine = engine;
        this.json = json;
        this.maxRecords = maxRecords;
    }

    @PostMapping(path = "/validations", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ValidationResponse validate(InputStream body,
            @RequestParam(required = false) List<RuleStatus> status,
            @RequestParam(required = false) List<String> country,
            @RequestParam(required = false) List<String> category,
            @RequestParam(required = false) Integer batchSize) {
        RunOptions options = options(status, country, category, batchSize);
        var listener = new CollectingListener();
        RunSummary summary;
        try {
            summary = engine.validate(new RecordLimit(RecordSources.jsonArray(body), maxRecords), listener, options);
        } catch (ValidationRunException e) {
            throw switch (e.getCause()) {
                case RecordLimit.Exceeded tooMany -> problem(HttpStatus.valueOf(413), "Too many records",
                        "This endpoint buffers results and accepts at most " + maxRecords
                                + " records. Send 'Accept: " + NDJSON + "' to stream larger batches.");
                case RecordSourceException invalid -> problem(HttpStatus.BAD_REQUEST, "Invalid JSON input",
                        invalid.getMessage());
                case null, default -> e;
            };
        }
        return new ValidationResponse(SummaryView.of(summary), listener.results(),
                listener.errors().stream().map(ErrorView::of).toList());
    }

    @PostMapping(path = "/validations", consumes = MediaType.APPLICATION_JSON_VALUE, produces = NDJSON)
    ResponseEntity<StreamingResponseBody> stream(InputStream body,
            @RequestParam(required = false) List<RuleStatus> status,
            @RequestParam(required = false) List<String> country,
            @RequestParam(required = false) List<String> category,
            @RequestParam(required = false) Integer batchSize) {
        RunOptions options = options(status, country, category, batchSize);
        StreamingResponseBody stream = out -> {
            var writer = new NdjsonListener(out, json);
            try {
                RunSummary summary = engine.validate(RecordSources.jsonArray(body), writer, options);
                writer.line("summary", SummaryView.of(summary));
            } catch (ValidationRunException e) {
                // The 200 status is already on the wire; the trailer line tells the client the run failed.
                Map<String, Object> aborted = new LinkedHashMap<>();
                aborted.put("reason", e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
                aborted.put("summary", SummaryView.of(e.partialSummary()));
                try {
                    writer.line("aborted", aborted);
                    writer.flush();
                } catch (java.io.UncheckedIOException clientGone) {
                    // The client disconnected; there is nobody left to tell.
                }
                return;
            }
            writer.flush();
        };
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(NDJSON)).body(stream);
    }

    private static RunOptions options(List<RuleStatus> status, List<String> country, List<String> category,
            Integer batchSize) {
        RuleFilter filter = RuleFilter.all();
        try {
            if (status != null && !status.isEmpty()) {
                filter = filter.and(status.stream().map(s -> RuleFilter.status(s)).reduce(RuleFilter::or).orElseThrow());
            }
            if (country != null && !country.isEmpty()) {
                filter = filter.and(RuleFilter.countryScope(country.toArray(String[]::new)));
            }
            if (category != null && !category.isEmpty()) {
                filter = filter.and(RuleFilter.category(category.toArray(String[]::new)));
            }
            var builder = RunOptions.builder().filter(filter);
            if (batchSize != null) {
                builder.batchSize(batchSize);
            }
            return builder.build();
        } catch (IllegalArgumentException e) {
            throw problem(HttpStatus.BAD_REQUEST, "Invalid run options", e.getMessage());
        }
    }

    private static ErrorResponseException problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return new ErrorResponseException(status, problem, null);
    }
}
