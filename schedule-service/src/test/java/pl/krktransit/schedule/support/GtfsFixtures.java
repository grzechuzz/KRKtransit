package pl.krktransit.schedule.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Synthetic provider input, deliberately independent from the production parser. */
public final class GtfsFixtures {
    private GtfsFixtures() {}

    public static Map<String, String> tables(String arrival) {
        var tables = new LinkedHashMap<String, String>();
        tables.put("routes.txt", "\ufeffroute_id,route_short_name,ignored\nR01,001,extra\n");
        tables.put("stops.txt", "stop_id,stop_name,stop_code,stop_desc,stop_lat,stop_lon\n"
                + "S01,\"Zażółć, gęślą\",001,,50.1,19.9\n"
                + "S02,Second,,,0,0\n");
        tables.put("trips.txt", "trip_id,route_id,service_id,direction_id,trip_headsign,shape_id\n"
                + "001,R01,weekday,0,\"Kierunek \"\"A\"\"\",0007\n");
        tables.put("stop_times.txt", "trip_id,stop_sequence,stop_id,arrival_time,departure_time\n"
                + "001,1,S01,00:00:00,00:01:00\n"
                + "001,3,S02," + arrival + ",25:31:00\n"
                + "001,8,S01,26:00:00,26:00:00\n");
        tables.put("shapes.txt", "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n"
                + "0007,50.1,19.9,1\n0007,50.2,20.0,1\n0007,50.3,20.1,3\n");
        tables.put("unused.txt", "ignored\n");
        return tables;
    }

    public static byte[] valid(String arrival) { return zip(tables(arrival)); }

    public static Map<String, String> lineRoutes() {
        var tables = new LinkedHashMap<String, String>();
        tables.put("routes.txt", "route_id,route_short_name\nR,198\n");
        tables.put("stops.txt", "stop_id,stop_name,stop_code,stop_desc,stop_lat,stop_lon\n"
                + "A,UR Al. 29 Listopada,,01,50.0,19.0\nB,Zażółć,,,50.0,19.01\nC,End,,02,50.0,19.02\n");
        tables.put("trips.txt", "trip_id,route_id,service_id,direction_id,trip_headsign,shape_id\n"
                + "T1,R,weekday,0,End,S1\nT2,R,weekday,1,End,S2\nT3,R,weekday,0,Start,S3\n");
        tables.put("stop_times.txt", "trip_id,stop_sequence,stop_id,arrival_time,departure_time\n"
                + "T1,1,A,07:00:00,07:00:00\nT1,2,B,07:05:00,07:05:00\nT1,3,C,07:10:00,07:10:00\n"
                + "T2,1,A,08:00:00,08:00:00\nT2,2,B,08:05:00,08:05:00\nT2,3,C,08:10:00,08:10:00\n"
                + "T3,1,C,09:00:00,09:00:00\nT3,2,B,09:05:00,09:05:00\nT3,3,A,09:10:00,09:10:00\n");
        tables.put("shapes.txt", "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n"
                + "S1,50.0,19.0,1\nS1,50.0,19.01,2\nS1,50.0,19.02,3\n"
                + "S2,50.0,19.0,1\nS2,50.0,19.01,2\nS2,50.0,19.02,3\n"
                + "S3,50.0,19.02,1\nS3,50.0,19.01,2\nS3,50.0,19.0,3\n");
        return tables;
    }

    public static byte[] invalidReference() {
        var tables = tables("25:30:00");
        tables.put("trips.txt", tables.get("trips.txt").replace("001,R01,", "001,missing-route,"));
        return zip(tables);
    }

    public static byte[] zip(Map<String, String> tables) {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var table : tables.entrySet()) {
                var entry = new ZipEntry(table.getKey());
                entry.setTime(0);
                output.putNextEntry(entry);
                output.write(table.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        } catch (IOException failure) { throw new AssertionError(failure); }
        return bytes.toByteArray();
    }

    public static String hash(byte[] zip) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(zip)); }
        catch (NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
    }
}
