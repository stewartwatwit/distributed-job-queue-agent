package dev.jobqueue.handler;

public class UnknownJobTypeException extends NonRetryableJobException {

    public UnknownJobTypeException(String type) {
        super("Unknown job type: " + type);
    }
}
