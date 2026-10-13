package dev.jobqueue.api;

import dev.jobqueue.handler.InvalidJobPayloadException;
import dev.jobqueue.job.Job;
import dev.jobqueue.job.JobService;
import dev.jobqueue.job.JobStatus;
import dev.jobqueue.job.JobSubmissionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final JobSubmissionService submissions;
    private final JobService jobs;
    private final JsonMapper mapper;

    public JobController(JobSubmissionService submissions, JobService jobs, JsonMapper mapper) {
        this.submissions = submissions;
        this.jobs = jobs;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<JobResponse> submit(@Valid @RequestBody SubmitJobRequest request) {
        if (!request.payload().isObject()) {
            throw new InvalidJobPayloadException("payload must be a JSON object");
        }
        Job job = submissions.submit(request.type(), request.payload(), request.maxAttempts());
        return ResponseEntity.created(URI.create("/api/v1/jobs/" + job.getId()))
                .body(JobResponse.from(job, mapper));
    }

    @GetMapping("/{id}")
    public JobResponse get(@PathVariable UUID id) {
        return JobResponse.from(jobs.get(id), mapper);
    }

    @GetMapping("/{id}/status")
    public JobStatusResponse status(@PathVariable UUID id) {
        return JobStatusResponse.from(jobs.get(id));
    }

    /** Newest first. Sizes above {@link #MAX_PAGE_SIZE} are capped rather than rejected. */
    @GetMapping
    public JobPageResponse list(@RequestParam(required = false) JobStatus status,
                                @RequestParam(defaultValue = "0") @Min(0) int page,
                                @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) @Min(1) int size) {
        PageRequest pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        Page<Job> result = jobs.list(status, pageable);
        return new JobPageResponse(result.map(job -> JobResponse.from(job, mapper)).getContent(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }
}
