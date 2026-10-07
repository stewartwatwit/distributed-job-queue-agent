# Distributed Job Queue — Project Specification

## Purpose

Build a distributed job queue as an advanced Java/Spring Boot project.

The system should allow clients to submit jobs through a REST API. Jobs should be persisted, placed into a queue, and processed asynchronously by worker processes.

The project should demonstrate practical backend engineering, concurrency, distributed processing, infrastructure integration, testing, and CI/CD.

---

# Technology Requirements

The implementation must use:

* Java
* Spring Boot
* Maven
* PostgreSQL
* Redis
* Docker
* JUnit 5
* Testcontainers
* GitHub Actions or an equivalent CI system

Do not substitute another primary technology without a strong technical reason.

---

# Core Concept

The system consists conceptually of:

1. A REST API
2. Persistent job storage
3. A job queue
4. One or more workers
5. Infrastructure services

A simplified flow is:

```text
Client
   |
   v
REST API
   |
   v
Job Persistence
   |
   v
Job Queue
   |
   v
Worker
   |
   v
Job Execution
   |
   v
Updated Job Status
```

The exact implementation of this architecture is intentionally left open.

---

# Jobs

A job should contain enough information to:

* identify it uniquely
* determine its type
* store relevant input data
* track its current state
* record useful timestamps
* determine whether processing succeeded or failed

The implementation should define an appropriate job model.

---

# Job Lifecycle

Jobs should have a clearly defined lifecycle.

At minimum, the system should distinguish between:

* newly submitted jobs
* queued jobs
* currently processing jobs
* successfully completed jobs
* failed jobs

Additional states may be introduced if they improve the design.

State transitions should be intentional and should prevent invalid transitions where practical.

---

# Job Submission

Clients should be able to submit jobs through a REST endpoint.

The endpoint should:

1. Validate the request.
2. Create a job.
3. Persist the job.
4. Make the job available for processing.
5. Return an appropriate response to the client.

The API should return enough information for a client to identify and track the submitted job.

---

# Job Retrieval

Clients should be able to retrieve information about a job.

The response should expose useful job information without unnecessarily exposing internal persistence details.

Consider what information an API consumer would realistically need.

---

# Worker Processing

Workers should asynchronously consume jobs from the queue.

The system should support multiple workers processing jobs concurrently.

Workers should:

1. Obtain a job.
2. Ensure the job can legitimately be processed.
3. Update its processing state.
4. Execute the job.
5. Record success or failure.
6. Handle unexpected errors appropriately.

The exact worker architecture is intentionally unspecified.

---

# Concurrency

The system must account for concurrent processing.

The implementation should prevent or appropriately handle situations such as:

* two workers processing the same job
* conflicting state updates
* race conditions
* inconsistent database state
* queue/database synchronization problems

The implementation should include tests that exercise important concurrency behavior.

---

# Job Types

The system should support more than one type of job or provide a reasonable mechanism for adding additional job types.

Job execution should be designed so that new job types can be introduced without rewriting the entire worker system.

The exact initial job types are an implementation decision.

---

# Failure Handling

Jobs can fail.

The system should:

* record failures
* preserve useful information about the failure
* leave the job in a meaningful state
* prevent worker failure from unnecessarily bringing down the entire application

Consider how transient failures should behave.

---

# Retry Behavior

The system should provide a reasonable retry strategy for jobs that fail.

The implementation should define:

* whether all failures are retryable
* maximum retry attempts
* how retry attempts are tracked
* what happens after the final failure

Do not implement unlimited retries.

---

# PostgreSQL

PostgreSQL should store durable job information.

The database should support:

* job persistence
* job status
* job metadata/input
* retry information
* timestamps
* querying jobs by identifier

The schema should be designed intentionally.

Use transactions where they are necessary to preserve consistency.

---

# Redis

Redis should provide queue-related functionality.

The implementation should determine the appropriate Redis data structure and interaction model.

Consider:

* FIFO behavior
* concurrent workers
* failed jobs
* serialization
* duplicate processing
* queue/database consistency

The Redis implementation should not be a superficial addition; it should meaningfully participate in job processing.

---

# REST API

At minimum, provide functionality to:

* submit a job
* retrieve a job
* inspect job status

Additional endpoints may be added when justified.

Use appropriate:

* HTTP methods
* HTTP status codes
* request validation
* response DTOs
* error handling

---

# Testing

Testing should be a major part of the project.

## Unit Tests

Test core business logic independently.

Examples include:

* job state transitions
* validation
* retry behavior
* job execution logic
* service behavior

## Integration Tests

Use Testcontainers to test actual infrastructure integrations.

At minimum, integration testing should include:

* PostgreSQL
* Redis

Tests should verify that the application actually communicates correctly with these services.

## API Tests

Test important REST API behavior, including successful requests and failure cases.

## Concurrency Tests

Include tests that provide confidence that multiple workers can operate safely.

---

# Docker

Provide a reproducible local development environment.

The environment should make it easy to run PostgreSQL and Redis.

The application should be configurable through environment variables.

Do not commit credentials.

---

# CI/CD

Create automated CI that:

* builds the application
* runs tests
* runs integration tests
* uses the required infrastructure
* fails when tests fail

The CI configuration should be committed to the repository.

---

# Documentation

Document:

* how to run the application
* how to start infrastructure
* how to run tests
* API endpoints
* configuration
* architecture
* important engineering decisions

Include enough information that another developer could clone the repository and understand the project.

---

# Non-Goals

Do not attempt to build:

* a production-scale cloud deployment
* a Kubernetes cluster
* a sophisticated web frontend
* authentication/authorization unless needed
* a complex distributed consensus system
* advanced observability infrastructure
* a UI dashboard

These could be future enhancements but are outside the core exercise.

---

# Engineering Expectations

The finished project should be something a junior software engineer could reasonably discuss during a technical interview.

It should demonstrate more than CRUD.

The implementation should show understanding of:

* asynchronous processing
* concurrency
* persistence
* queues
* failure handling
* integration testing
* containerization
* CI/CD

The project should remain understandable rather than becoming unnecessarily elaborate.
