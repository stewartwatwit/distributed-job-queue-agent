package dev.jobqueue.api;

import java.util.List;

public record JobPageResponse(List<JobResponse> items, int page, int size, long totalElements, int totalPages) {
}
