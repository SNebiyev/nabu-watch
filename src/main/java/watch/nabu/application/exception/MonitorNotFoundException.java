package watch.nabu.application.exception;

import java.util.UUID;

public class MonitorNotFoundException extends RuntimeException {

    public MonitorNotFoundException(UUID monitorId) {
        super("Monitor not found: " + monitorId);
    }
}
