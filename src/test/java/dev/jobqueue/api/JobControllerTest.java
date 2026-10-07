package dev.jobqueue.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.jobqueue.handler.InvalidJobPayloadException;
import dev.jobqueue.handler.UnknownJobTypeException;
import dev.jobqueue.job.Job;
import dev.jobqueue.job.JobNotFoundException;
import dev.jobqueue.job.JobService;
import dev.jobqueue.job.JobStatus;
import dev.jobqueue.job.JobSubmissionService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(JobController.class)
class JobControllerTest {

    private static final String VALID_BODY = """
            {"type":"ECHO","payload":{"hello":"world"},"maxAttempts":4}""";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JobSubmissionService submissions;

    @MockitoBean
    JobService jobs;

    private static Job job() {
        return Job.create("ECHO", "{\"hello\":\"world\"}", 4, Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void submitReturns201WithLocationAndBody() throws Exception {
        Job job = job();
        when(submissions.submit(eq("ECHO"), any(), eq(4))).thenReturn(job);

        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/jobs/" + job.getId()))
                .andExpect(jsonPath("$.id").value(job.getId().toString()))
                .andExpect(jsonPath("$.type").value("ECHO"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.maxAttempts").value(4))
                .andExpect(jsonPath("$.payload.hello").value("world"))
                .andExpect(jsonPath("$.result").doesNotExist())
                .andExpect(jsonPath("$.lockedBy").doesNotExist())
                .andExpect(jsonPath("$.leaseExpiresAt").doesNotExist());
    }

    @Test
    void submitPassesNullMaxAttemptsWhenOmitted() throws Exception {
        when(submissions.submit(eq("ECHO"), any(), eq(null))).thenReturn(job());

        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ECHO\",\"payload\":{}}"))
                .andExpect(status().isCreated());

        verify(submissions).submit(eq("ECHO"), any(), eq(null));
    }

    @Test
    void blankTypeIsRejectedWithFieldError() throws Exception {
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\" \",\"payload\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.errors[0].field").value("type"));
        verifyNoInteractions(submissions);
    }

    @Test
    void typeLongerThan64IsRejected() throws Exception {
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"" + "A".repeat(65) + "\",\"payload\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("type"));
    }

    @Test
    void missingPayloadIsRejected() throws Exception {
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"ECHO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("payload"));
    }

    @Test
    void nonObjectPayloadIsRejected() throws Exception {
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ECHO\",\"payload\":[1,2]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("payload must be a JSON object"));
        verifyNoInteractions(submissions);
    }

    @Test
    void maxAttemptsOutOfRangeIsRejected() throws Exception {
        for (int bad : new int[] {0, 11}) {
            mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"type\":\"ECHO\",\"payload\":{},\"maxAttempts\":" + bad + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("maxAttempts"));
        }
    }

    @Test
    void malformedJsonIsBadRequestProblem() throws Exception {
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void unknownTypeIsBadRequest() throws Exception {
        when(submissions.submit(eq("NOPE"), any(), any())).thenThrow(new UnknownJobTypeException("NOPE"));

        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"NOPE\",\"payload\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Unknown job type: NOPE"));
    }

    @Test
    void invalidPayloadIsBadRequest() throws Exception {
        when(submissions.submit(eq("CHECKSUM"), any(), any()))
                .thenThrow(new InvalidJobPayloadException("CHECKSUM requires a string field 'text'"));

        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CHECKSUM\",\"payload\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("CHECKSUM requires a string field 'text'"));
    }

    @Test
    void unexpectedFailureIs500WithoutLeakingInternals() throws Exception {
        when(submissions.submit(any(), any(), any())).thenThrow(new IllegalStateException("db password is hunter2"));

        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.detail").value("Internal server error"))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }

    @Test
    void getReturnsJob() throws Exception {
        Job job = job();
        when(jobs.get(job.getId())).thenReturn(job);

        mvc.perform(get("/api/v1/jobs/{id}", job.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(job.getId().toString()))
                .andExpect(jsonPath("$.payload.hello").value("world"))
                .andExpect(jsonPath("$.createdAt").value("2026-01-01T00:00:00Z"));
    }

    @Test
    void getUnknownJobIs404Problem() throws Exception {
        UUID id = UUID.randomUUID();
        when(jobs.get(id)).thenThrow(new JobNotFoundException(id));

        mvc.perform(get("/api/v1/jobs/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Job not found: " + id));
    }

    @Test
    void malformedIdIs400() throws Exception {
        mvc.perform(get("/api/v1/jobs/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"));
    }

    @Test
    void statusReturnsCompactView() throws Exception {
        Job job = job();
        when(jobs.get(job.getId())).thenReturn(job);

        mvc.perform(get("/api/v1/jobs/{id}/status", job.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(job.getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.updatedAt").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.payload").doesNotExist());
    }

    @Test
    void listUsesDefaultsAndReturnsPage() throws Exception {
        Job job = job();
        when(jobs.list(eq(null), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(List.of(job), inv.getArgument(1), 1));

        mvc.perform(get("/api/v1/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void listCapsSizeAndFiltersByStatus() throws Exception {
        when(jobs.list(eq(JobStatus.FAILED), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<Job>(List.of(), inv.getArgument(1), 0));

        mvc.perform(get("/api/v1/jobs").param("status", "FAILED").param("size", "500").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.page").value(2));
    }

    @Test
    void listRejectsInvalidStatus() throws Exception {
        mvc.perform(get("/api/v1/jobs").param("status", "BOGUS"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"));
    }

    @Test
    void listRejectsNegativePageAndZeroSize() throws Exception {
        mvc.perform(get("/api/v1/jobs").param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/jobs").param("size", "0")).andExpect(status().isBadRequest());
    }
}
