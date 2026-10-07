# CLAUDE.md

## Project Overview

This project is an independent reimplementation of a distributed job queue built for software engineering practice.

The goal is to implement the system from the requirements in `PROJECT_SPEC.md` rather than reproduce an existing implementation.

This is intentionally a learning exercise. The developer has an existing implementation of a similar project elsewhere and will later compare the two implementations.

## Primary Objective

Build a production-style distributed job queue using:

* Java
* Spring Boot
* PostgreSQL
* Redis
* Docker
* JUnit 5
* Testcontainers
* Maven
* CI/CD

The implementation should demonstrate:

* REST API development
* persistence with PostgreSQL
* queue management with Redis
* concurrent job processing
* thread-safe application behavior
* worker processes
* job lifecycle/state management
* integration testing
* unit testing
* containerized development
* CI/CD automation

Do not optimize for matching another implementation.

Optimize for:

1. Correctness
2. Clear design
3. Maintainability
4. Testability
5. Appropriate use of concurrency
6. Realistic production practices
7. A design that can be explained in an interview

---

# Critical Rule: Independent Implementation

This project is intended to be compared against another implementation.

Therefore:

* Do NOT attempt to reproduce another implementation.
* Do NOT invent that you have seen another implementation.
* Do NOT assume specific classes, packages, APIs, database schemas, or Redis structures from another implementation.
* Make your own reasonable engineering decisions.
* If multiple approaches are valid, choose one and document why.
* Prefer straightforward solutions over unnecessary complexity.

The requirements describe WHAT the system should do.

You are responsible for determining HOW it should do it.

---

# Source of Truth

Use the following files as the authoritative project requirements:

* `PROJECT_SPEC.md`
* `IMPLEMENTATION_NOTES.md`

Do not invent requirements that are not present in those files unless a technical decision is necessary to implement an existing requirement.

When a requirement is ambiguous:

1. Identify the ambiguity.
2. Choose a reasonable implementation.
3. Document the decision.
4. Continue unless clarification is genuinely necessary.

---

# Development Philosophy

Work incrementally.

For each meaningful feature:

1. Understand the requirement.
2. Propose an implementation approach.
3. Implement it.
4. Add or update tests.
5. Run the relevant tests.
6. Inspect failures.
7. Fix problems.
8. Update documentation when appropriate.

Do not build the entire application in one enormous change.

---

# Architecture Guidelines

Use conventional Spring Boot architecture where appropriate.

Potential layers may include:

* Controller
* Service
* Repository
* Domain/Entity
* DTO
* Configuration
* Worker
* Infrastructure/integration components

These are suggestions, not mandatory requirements.

Do not create layers or abstractions solely because they are common patterns.

Every abstraction should have a purpose.

---

# Concurrency

Concurrency is a central part of this project.

Pay particular attention to:

* multiple workers processing jobs
* thread safety
* race conditions
* duplicate job processing
* job state transitions
* queue behavior
* graceful shutdown
* error handling
* retries

Do not assume that code is thread-safe simply because it compiles.

Where concurrency mechanisms are used, document why they are necessary.

Tests should attempt to expose concurrency-related bugs where practical.

---

# Persistence

PostgreSQL should be treated as persistent application state.

Consider:

* database schema design
* transactions
* indexes
* entity relationships
* job state persistence
* failure scenarios
* consistency between persistent state and queue state

Avoid putting business logic directly inside controllers.

---

# Redis

Redis should be used for queue-related functionality where appropriate.

Consider:

* queue semantics
* ordering
* worker consumption
* serialization
* failure behavior
* duplicate processing
* retry behavior

Do not introduce Redis data structures without a clear reason.

Document important Redis design decisions.

---

# Testing Requirements

Testing is a major objective of this project.

Use:

### JUnit

For unit-level behavior such as:

* service logic
* validation
* state transitions
* retry logic
* business rules

### Testcontainers

Use real containerized infrastructure for integration testing where appropriate.

At minimum, integration tests should exercise the application's interaction with:

* PostgreSQL
* Redis

Do not replace infrastructure integration tests with mocks when the purpose of the test is to verify actual infrastructure behavior.

### Test Categories

Maintain a sensible separation between:

* unit tests
* integration tests
* API/controller tests
* concurrency-related tests

Tests should verify behavior, not merely increase coverage numbers.

---

# API

The application should expose a REST API for interacting with jobs.

Use DTOs rather than exposing persistence entities directly when appropriate.

Validate request data.

Return appropriate HTTP status codes.

Handle errors consistently.

API behavior should be documented.

---

# Docker

The application should be runnable with containerized dependencies.

Use Docker Compose or an equivalent approach for local infrastructure.

The local development environment should make it reasonably easy to start:

* PostgreSQL
* Redis
* the application

Do not unnecessarily containerize every component if doing so makes development harder without providing meaningful value.

---

# CI/CD

Create a CI workflow that:

1. Checks out the repository.
2. Sets up the appropriate Java environment.
3. Builds the project.
4. Runs tests.
5. Fails when tests fail.

Because Testcontainers is part of the project, ensure the CI environment can run the required containers.

Keep CI configuration understandable.

---

# Code Quality

Prioritize:

* readable code
* meaningful names
* small cohesive classes
* appropriate encapsulation
* sensible exception handling
* minimal duplication
* clear documentation
* maintainable tests

Avoid:

* premature abstraction
* unnecessary design patterns
* speculative features
* excessive comments that merely restate code
* giant classes
* giant methods
* hard-coded infrastructure credentials
* committing secrets

---

# Configuration and Secrets

Never commit:

* passwords
* API keys
* credentials
* local secret files
* environment-specific secrets

Use environment variables or appropriate configuration mechanisms.

Provide safe development defaults where possible.

---

# Documentation

Keep documentation updated as the project evolves.

Document meaningful architectural decisions in `docs/` when appropriate.

If an implementation choice is non-obvious, explain:

* what was chosen
* why it was chosen
* alternatives considered
* important tradeoffs

Do not document every trivial implementation decision.

---

# Git

Use focused commits.

Prefer commits such as:

* `feat: add job submission endpoint`
* `feat: add redis queue integration`
* `test: add postgres integration tests`
* `test: add concurrent worker tests`
* `ci: add github actions workflow`

Avoid giant commits containing unrelated changes.

Do not rewrite Git history unless explicitly requested.

Do not push changes or create pull requests unless explicitly requested.

---

# How to Work With the Developer

The developer is using this project as a learning exercise.

Therefore, do not silently make major architectural decisions.

For meaningful decisions, briefly explain:

* what you recommend
* why
* what alternatives exist

The developer may ask for explanations before implementation.

When asked to explain something, prioritize teaching over simply providing the final code.

---

# Comparison Exercise

Do not attempt to make this implementation match another project.

The developer will compare the two projects after implementation.

Important comparison areas include:

* architecture
* package structure
* domain model
* database schema
* Redis design
* worker design
* concurrency strategy
* error handling
* retry behavior
* testing strategy
* Testcontainers usage
* API design
* configuration
* Docker setup
* CI/CD
* code organization

The goal is to understand that multiple valid implementations can solve the same engineering problem.

---

# Definition of Done

The project is considered complete when:

* the application builds successfully
* PostgreSQL integration works
* Redis integration works
* jobs can be submitted
* jobs can be queued
* workers can process jobs
* job states are persisted appropriately
* failures are handled appropriately
* concurrency behavior is tested
* unit tests exist for core business logic
* integration tests use Testcontainers
* Docker development environment works
* CI builds and tests the project
* API behavior is documented
* README documentation is usable
* no secrets are committed
* the implementation is understandable enough to discuss in an interview

Do not add unrelated features simply to make the project larger.

## GitHub Workflow

This project is hosted on GitHub.

GitHub CLI (`gh`) is available for repository operations.

Claude may use Git and GitHub CLI to:

- create branches
- inspect branches
- commit changes
- push branches
- create pull requests
- inspect pull requests
- inspect GitHub Actions
- review CI results

### Branching

Do not develop directly on `main` for feature work.

Create a descriptive branch for each meaningful feature or change.

Examples:

- `feature/job-domain`
- `feature/redis-queue`
- `feature/worker-processing`
- `test/concurrency`
- `ci/github-actions`

### Pull Requests

When a meaningful feature is complete:

1. Run relevant tests.
2. Review the diff.
3. Commit the changes.
4. Push the branch.
5. Create a pull request.
6. Provide a useful PR title and description.
7. Wait for CI.
8. Review CI results.
9. Do not merge the PR unless explicitly instructed.

### Commits

Use focused commits.

Do not combine unrelated changes into a single commit.

### Main Branch

Never force-push to `main`.

Do not merge pull requests without explicit approval.

### CI

After creating a PR, inspect the GitHub Actions results.

If CI fails:

1. Determine the cause.
2. Fix the issue.
3. Run the relevant tests locally.
4. Commit and push the fix.
5. Re-check CI.

Do not simply ignore failing CI.
