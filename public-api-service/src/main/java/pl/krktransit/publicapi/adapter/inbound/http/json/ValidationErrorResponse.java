package pl.krktransit.publicapi.adapter.inbound.http.json;

import java.util.List;

public record ValidationErrorResponse(List<ValidationIssue> detail) {
    public ValidationErrorResponse { detail = List.copyOf(detail); }
}
