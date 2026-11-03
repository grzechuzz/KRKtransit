package pl.krktransit.publicapi.application.schedule;

import java.util.List;

public record LineRoutes(List<LineDirection> directions) {
    public LineRoutes { directions = List.copyOf(directions); }
}
