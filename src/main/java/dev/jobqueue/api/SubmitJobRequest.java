package dev.jobqueue.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public record SubmitJobRequest(
        @NotBlank @Size(max = 64) String type,
        @NotNull JsonNode payload,
        @Min(1) @Max(10) Integer maxAttempts) {
}
