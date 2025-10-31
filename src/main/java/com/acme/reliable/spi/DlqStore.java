package com.acme.reliable.spi;

import java.util.UUID;

public interface DlqStore {
    void park(UUID commandId, String commandName, String businessKey, String payload,
              String failedStatus, String errorClass, String errorMessage, int attempts, String parkedBy);
}
