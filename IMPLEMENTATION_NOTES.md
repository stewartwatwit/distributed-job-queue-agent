# Implementation Notes

## Purpose of This File

This project is being built as an independent implementation exercise.

The developer has previously implemented a distributed job queue with similar requirements.

The purpose of this repository is to see how a different implementation develops when starting from the same high-level problem.

---

# Important Constraint

Do not attempt to reproduce an existing implementation.

The implementation should be independently designed from the requirements.

There is no predetermined:

* package structure
* class structure
* database schema
* Redis data structure
* worker implementation
* concurrency mechanism
* API structure beyond the required functionality
* testing structure

Make engineering decisions based on the requirements.

---

# Learning Goals

This project should provide practical experience with:

* Spring Boot
* REST APIs
* dependency injection
* JPA/Hibernate
* PostgreSQL
* Redis
* asynchronous processing
* Java concurrency
* thread safety
* transaction boundaries
* integration testing
* Testcontainers
* Docker
* CI/CD
* error handling
* clean architecture

When implementation decisions involve an important concept, explain it rather than hiding the complexity.

---

# Avoid Overengineering

The project should be advanced because of the engineering problems it solves, not because it contains unnecessary abstractions.

Prefer:

```text
simple + correct + testable
```

over:

```text
complex + abstract + impressive-looking
```

If a simpler implementation satisfies the requirement, prefer the simpler implementation.

---

# Comparison Preparation

At completion, the developer intends to compare this project against another implementation.

Useful comparison categories include:

## Architecture

* package structure
* layers
* responsibilities
* dependencies

## Domain Model

* job representation
* statuses
* state transitions
* persistence model

## Queue

* Redis data structures
* enqueue behavior
* dequeue behavior
* failure behavior

## Workers

* worker lifecycle
* thread model
* concurrency strategy
* shutdown behavior

## Database

* schema
* transactions
* indexes
* persistence strategy

## Testing

* unit test organization
* integration test strategy
* Testcontainers configuration
* concurrency testing

## API

* endpoint design
* DTO structure
* validation
* error handling

## Operations

* Docker
* configuration
* CI/CD

---

# Do Not Optimize for Similarity

A successful project is not one that resembles another implementation.

A successful project is one that:

* meets the specification
* is technically sound
* is well tested
* is understandable
* has defensible engineering decisions

Differences between implementations are expected and valuable.
