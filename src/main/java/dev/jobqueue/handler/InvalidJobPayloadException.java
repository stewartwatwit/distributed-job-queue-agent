package dev.jobqueue.handler;

/** The payload can never be processed by the handler; retrying will not help. */
public class InvalidJobPayloadException extends NonRetryableJobException {

    public InvalidJobPayloadException(String message) {
        super(message);
    }
}
