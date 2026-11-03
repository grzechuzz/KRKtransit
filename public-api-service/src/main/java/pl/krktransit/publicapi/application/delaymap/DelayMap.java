package pl.krktransit.publicapi.application.delaymap;

import java.util.List;

public record DelayMap(List<DelayMapDirection> directions) {
    public DelayMap { directions = List.copyOf(directions); }
}
