package dev.jobqueue.handler;

import tools.jackson.databind.JsonNode;

/**
 * Executes one type of job. Adding a job type means adding one {@code @Component} implementing
 * this interface; the worker and API discover it through {@link JobHandlerRegistry}.
 *
 * Delivery is at-least-once, so handlers should be idempotent where practical.
 */
public interface JobHandler {

    /** Unique, upper-case job type name that clients submit, e.g. {@code ECHO}. */
    String type();

    /**
     * Rejects payloads this handler can never process. Called at submit time (so clients get a
     * 400 instead of a doomed job) and again before execution.
     *
     * @throws InvalidJobPayloadException if the payload is unusable
     */
    default void validate(JsonNode payload) {
    }

    /**
     * Runs the job and returns a JSON result. Throw {@link NonRetryableJobException} for failures
     * that retrying cannot fix; any other exception is treated as retryable.
     */
    JsonNode handle(JsonNode payload) throws Exception;
}
