package com.hrpayroll.backend.leave;

import jakarta.validation.constraints.Size;

/**
 * {@code reason} is optional -- the controller accepts an omitted body, an
 * empty JSON object, or a body with {@code reason} (via
 * {@code @RequestBody(required = false)}). Blank/whitespace-only reasons
 * are normalized to null by the service, not here -- this record stays a
 * plain validated data holder, matching every other request DTO in the
 * project.
 */
public record RejectLeaveRequestRequest(@Size(max = 500) String reason) {
}
