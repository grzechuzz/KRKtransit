package pl.krktransit.schedule.adapter.outbound.gtfscsv;

import java.util.List;

enum GtfsTable {
    ROUTES("routes.txt", "route_id", "route_short_name"),
    STOPS("stops.txt", "stop_id", "stop_name", "stop_code", "stop_desc", "stop_lat", "stop_lon"),
    TRIPS("trips.txt", "trip_id", "route_id", "service_id", "direction_id", "trip_headsign", "shape_id"),
    STOP_TIMES("stop_times.txt", "trip_id", "stop_sequence", "stop_id", "arrival_time", "departure_time"),
    SHAPES("shapes.txt", "shape_id", "shape_pt_lat", "shape_pt_lon", "shape_pt_sequence");

    private final String filename;
    private final List<String> headers;

    GtfsTable(String filename, String... headers) {
        this.filename = filename;
        this.headers = List.of(headers);
    }

    String filename() { return filename; }
    List<String> headers() { return headers; }
    boolean requiresRows() { return this != SHAPES; }
}
