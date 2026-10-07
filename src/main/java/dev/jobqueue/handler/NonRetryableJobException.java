package dev.jobqueue.handler;

/** Thrown by a handler when a failure is permanent: the job goes straight to FAILED. */
public class NonRetryableJobException extends RuntimeException {

    public NonRetryableJobException(String message) {
        super(message);
    }

    public NonRetryableJobException(String message, Throwable cause) {
        super(message, cause);
    }
}
