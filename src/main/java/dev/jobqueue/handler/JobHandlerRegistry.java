package dev.jobqueue.handler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/** Looks up handlers by job type. Built once at startup; duplicate types fail fast. */
@Component
public class JobHandlerRegistry {

    private final Map<String, JobHandler> handlers = new HashMap<>();

    public JobHandlerRegistry(List<JobHandler> allHandlers) {
        for (JobHandler handler : allHandlers) {
            JobHandler previous = handlers.put(handler.type(), handler);
            if (previous != null) {
                throw new IllegalStateException("Duplicate job handler for type '" + handler.type() + "': "
                        + previous.getClass().getName() + " and " + handler.getClass().getName());
            }
        }
    }

    public boolean supports(String type) {
        return handlers.containsKey(type);
    }

    /** @throws UnknownJobTypeException if no handler is registered for the type */
    public JobHandler get(String type) {
        JobHandler handler = handlers.get(type);
        if (handler == null) {
            throw new UnknownJobTypeException(type);
        }
        return handler;
    }

    public Set<String> types() {
        return new TreeSet<>(handlers.keySet());
    }
}
