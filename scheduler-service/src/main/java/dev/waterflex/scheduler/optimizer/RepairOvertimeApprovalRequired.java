package dev.waterflex.scheduler.optimizer;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A valid repair stays ready for dispatcher review instead of being requeued. */
public final class RepairOvertimeApprovalRequired extends ResponseStatusException {
    private static final long serialVersionUID = 1L;
    public RepairOvertimeApprovalRequired(long additionalMinutes) {
        super(HttpStatus.CONFLICT, "Dispatcher approval required for " + additionalMinutes + " additional overtime minutes");
    }
}
