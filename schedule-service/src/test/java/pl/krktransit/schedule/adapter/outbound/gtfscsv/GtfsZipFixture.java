package pl.krktransit.schedule.adapter.outbound.gtfscsv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class GtfsZipFixture {
    private final Map<String, String> files = new LinkedHashMap<>();

    GtfsZipFixture() {
        files.put("routes.txt", "route_id,route_short_name\n001,010\n");
        files.put("stops.txt", "stop_id,stop_name,stop_code,stop_desc,stop_lat,stop_lon\n"
                + "001,\"Main, Station\",001,,50.1,19.9\n");
        files.put("trips.txt", "trip_id,route_id,service_id,direction_id,trip_headsign,shape_id\n"
                + "0001,001,weekday,0,Central,shape1\n");
        files.put("stop_times.txt", "trip_id,stop_sequence,stop_id,arrival_time,departure_time\n"
                + "0001,1,001,25:10:00,25:10:30\n");
        files.put("shapes.txt", "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n"
                + "shape1,50.1,19.9,1\n");
    }

    GtfsZipFixture file(String name, String contents) {
        files.put(name, contents);
        return this;
    }

    GtfsZipFixture remove(String name) {
        files.remove(name);
        return this;
    }

    Path write(Path directory) throws IOException {
        Path zip = Files.createTempFile(directory, "fixture-", ".zip");
        try (var output = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (var file : files.entrySet()) {
                var entry = new ZipEntry(file.getKey());
                entry.setTime(0);
                output.putNextEntry(entry);
                output.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return zip;
    }
}
