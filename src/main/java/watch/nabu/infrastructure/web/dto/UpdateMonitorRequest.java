package watch.nabu.infrastructure.web.dto;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PATCH /api/v1/monitors/{id}}.
 *
 * <p>Set {@code targetUrl} to a valid HTTP/HTTPS URL to pin the scan target
 * manually.  Send {@code null} or omit the field to reset the monitor back
 * to auto-discovery (the worker will use the website's primary URL).
 */
public record UpdateMonitorRequest(

    @Nullable
    @Size(max = 512, message = "targetUrl must not exceed 512 characters")
    @Pattern(regexp = "https?://.+|", message = "targetUrl must start with http:// or https://")
    String targetUrl

) {}
