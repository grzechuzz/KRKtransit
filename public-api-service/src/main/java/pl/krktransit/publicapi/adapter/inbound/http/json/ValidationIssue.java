package pl.krktransit.publicapi.adapter.inbound.http.json;

import java.util.List;

public record ValidationIssue(String type, List<String> loc, String msg, Object input) {
    public ValidationIssue { loc = List.copyOf(loc); }
}
