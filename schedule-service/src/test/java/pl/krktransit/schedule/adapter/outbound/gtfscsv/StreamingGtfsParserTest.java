package pl.krktransit.schedule.adapter.outbound.gtfscsv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.krktransit.schedule.application.importing.ImportFailure;
import pl.krktransit.schedule.application.port.RowSource;
import pl.krktransit.schedule.application.port.ScheduleWriter;
import pl.krktransit.schedule.domain.feed.FeedId;
import pl.krktransit.schedule.domain.schedule.Route;
import pl.krktransit.schedule.domain.schedule.ShapePoint;
import pl.krktransit.schedule.domain.schedule.Stop;
import pl.krktransit.schedule.domain.schedule.StopTime;
import pl.krktransit.schedule.domain.schedule.Trip;

class StreamingGtfsParserTest {
    private static final FeedId FEED = new FeedId("krakow", "mpk");
    private final StreamingGtfsParser parser = new StreamingGtfsParser();
    @TempDir Path temporary;

    @Test
    void streamsScopedIdsQuotedFieldsNullableValuesAndTimesAboveMidnight() throws IOException {
        var sink = parse(new GtfsZipFixture());

        assertThat(sink.tables).containsExactly("routes", "stops", "trips", "stop_times", "shapes");
        assertThat(sink.routes).containsExactly(new Route("krakow:mpk:001", "010"));
        assertThat(sink.stops).containsExactly(new Stop("krakow:mpk:001", "Main, Station", "001",
                null, 50.1, 19.9));
        assertThat(sink.trips).containsExactly(new Trip("krakow:mpk:0001", "krakow:mpk:001",
                "weekday", 0, "Central", "krakow:mpk:shape1"));
        assertThat(sink.times).containsExactly(new StopTime("krakow:mpk:0001", 1, "krakow:mpk:001", 90600, 90630));
        assertThat(sink.completed).containsExactlyElementsOf(sink.tables);
    }

    @Test
    void acceptsBomExtraColumnsAndUnusedFiles() throws IOException {
        var fixture = new GtfsZipFixture()
                .file("routes.txt", "\ufeffroute_id,route_short_name,ignored\n001,010,anything\n")
                .file("calendar.txt", "unused\nanything\n");

        assertThat(parse(fixture).routes).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"routes.txt", "stops.txt", "trips.txt", "stop_times.txt", "shapes.txt"})
    void rejectsMissingMappedFiles(String name) throws IOException {
        assertInvalid(new GtfsZipFixture().remove(name), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"routes.txt", "stops.txt", "trips.txt", "stop_times.txt"})
    void rejectsEmptyCoreTables(String name) throws IOException {
        assertInvalid(new GtfsZipFixture().file(name, headers(name)), name);
    }

    @Test
    void requiresMappedHeadersEvenWhenShapesHaveNoRows() throws IOException {
        assertInvalid(new GtfsZipFixture().file("shapes.txt", "shape_id,shape_pt_lat,shape_pt_lon\n"),
                "shape_pt_sequence");
    }

    @Test
    void acceptsShapesWithoutPointsAndImportsTripsWithoutShapeReferences() throws IOException {
        var fixture = new GtfsZipFixture()
                .file("shapes.txt", headers("shapes.txt"))
                .file("trips.txt", headers("trips.txt") + "0001,001,weekday,,,\n");

        var sink = parse(fixture);

        assertThat(sink.points).isEmpty();
        assertThat(sink.trips.getFirst()).isEqualTo(new Trip("krakow:mpk:0001", "krakow:mpk:001",
                "weekday", null, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {",010", " ,010", "\t,010"})
    void rejectsBlankOriginalIdsBeforePrefixing(String row) throws IOException {
        assertInvalid(new GtfsZipFixture().file("routes.txt", headers("routes.txt") + row + "\n"), "route_id");
    }

    @Test
    void rejectsBlankRequiredReferencesWithoutChangingValidOriginalIds() throws IOException {
        assertInvalid(new GtfsZipFixture().file("trips.txt", headers("trips.txt")
                + "0001, ,weekday,0,Central,shape1\n"), "route_id");
        var sink = parse(new GtfsZipFixture().file("routes.txt", headers("routes.txt") + " 001 ,010\n"));
        assertThat(sink.routes.getFirst().id()).isEqualTo("krakow:mpk: 001 ");
    }

    @Test
    void preservesColonsWhenScopingSourceIdsAndReferences() throws IOException {
        var sink = parse(new GtfsZipFixture()
                .file("routes.txt", headers("routes.txt") + "provider:001,010\n")
                .file("trips.txt", headers("trips.txt") + "vehicle:0001,provider:001,weekday,0,Central,shape:1\n")
                .file("stop_times.txt", headers("stop_times.txt") + "vehicle:0001,1,001,25:10:00,25:10:30\n"));

        assertThat(sink.routes.getFirst().id()).isEqualTo("krakow:mpk:provider:001");
        assertThat(sink.trips.getFirst().routeId()).isEqualTo("krakow:mpk:provider:001");
        assertThat(sink.trips.getFirst().id()).isEqualTo("krakow:mpk:vehicle:0001");
        assertThat(sink.trips.getFirst().shapeId()).isEqualTo("krakow:mpk:shape:1");
        assertThat(sink.times.getFirst().tripId()).isEqualTo("krakow:mpk:vehicle:0001");
    }

    @Test
    void acceptsMissingOptionalTrailingCellsAsPythonDictReaderDoes() throws IOException {
        var sink = parse(new GtfsZipFixture()
                .file("stops.txt", headers("stops.txt") + "001,Main\n")
                .file("trips.txt", headers("trips.txt") + "0001,001,weekday\n"));

        assertThat(sink.stops.getFirst()).isEqualTo(new Stop("krakow:mpk:001", "Main", null, null, null, null));
        assertThat(sink.trips.getFirst().directionId()).isNull();
        assertThat(sink.trips.getFirst().headsign()).isNull();
        assertThat(sink.trips.getFirst().shapeId()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"mpk", "mobilis", "tram"})
    void distinguishesAbsentTripShapeReferencesFromActualOriginalIds(String source) throws IOException {
        var fixture = new GtfsZipFixture().file("trips.txt", headers("trips.txt")
                + "0001,001,weekday,0,Central,\n"
                + "0002,001,weekday,0,Central\n"
                + "0003,001,weekday\n"
                + "0004,001,weekday,0,Central,None\n"
                + "0005,001,weekday,0,Central, shape:1 \n");
        var feed = new FeedId("krakow", source);
        var sink = new Rows();

        parser.parse(feed, fixture.write(temporary), sink);

        assertThat(sink.trips).hasSize(5);
        assertThat(sink.trips.subList(0, 3)).allSatisfy(trip -> assertThat(trip.shapeId()).isNull());
        assertThat(sink.trips.get(3).shapeId()).isEqualTo(feed.scope("None"));
        assertThat(sink.trips.get(4).shapeId()).isEqualTo(feed.scope(" shape:1 "));
        assertThat(sink.points).hasSize(1);
    }

    @Test
    void retainsDuplicateShapeSequencesAndDoesNotValidateRangesOrChronology() throws IOException {
        var fixture = new GtfsZipFixture()
                .file("shapes.txt", headers("shapes.txt") + "shape1,150,219,1\nshape1,150,219,1\n")
                .file("stop_times.txt", headers("stop_times.txt")
                        + "0001,3,001,25:10:00,25:09:00\n0001,9,001,24:00:00,24:00:00\n");

        var sink = parse(fixture);

        assertThat(sink.points).hasSize(2);
        assertThat(sink.times).extracting(StopTime::sequence).containsExactly(3, 9);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e400", "-1e400", "1e-400", "50.1d", "50.1f", "0x1.2p3d"})
    void rejectsNumbersThatPostgresWouldRejectInsteadOfSilentlyConvertingThem(String latitude) throws IOException {
        assertInvalid(new GtfsZipFixture().file("shapes.txt", headers("shapes.txt")
                + "shape1," + latitude + ",19.9,1\n"), "shape_pt_lat");
    }

    @Test
    void preservesPostgresExponentHexAndCaseInsensitiveSpecialValueInputs() throws IOException {
        var sink = parse(new GtfsZipFixture().file("shapes.txt", headers("shapes.txt")
                + "shape1,5.01e1,19.9,1\n"
                + "shape1,inf,-INFINITY,2\n"
                + "shape1,nan,NaN(payload),3\n"
                + "shape1,0x1.2p3,0x1.2,4\n"
                + "shape1,1e-323,0e-999,5\n"));

        assertThat(sink.points.get(0).latitude()).isEqualTo(50.1);
        assertThat(sink.points.get(1).latitude()).isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(sink.points.get(1).longitude()).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(sink.points.get(2).latitude()).isNaN();
        assertThat(sink.points.get(2).longitude()).isNaN();
        assertThat(sink.points.get(3).latitude()).isEqualTo(9.0);
        assertThat(sink.points.get(3).longitude()).isEqualTo(1.125);
        assertThat(sink.points.get(4).latitude()).isEqualTo(1e-323);
        assertThat(sink.points.get(4).longitude()).isZero();
    }

    @Test
    void rejectsMalformedTimesAndTruncatedRowsWithContext() throws IOException {
        assertInvalid(new GtfsZipFixture().file("stop_times.txt", headers("stop_times.txt")
                + "0001,1,001,25:60:00,25:10:00\n"), "stop_times.txt");
        assertInvalid(new GtfsZipFixture().file("stops.txt", headers("stops.txt") + "001\n"), "stops.txt");
    }

    @Test
    void rejectsInvalidZipAndLimitsDecompressedBytes() throws IOException {
        var invalidZip = Files.writeString(temporary.resolve("broken.zip"), "not a zip");
        assertThatThrownBy(() -> parser.parse(FEED, invalidZip, new Rows()))
                .isInstanceOf(ImportFailure.class);
        var zip = new GtfsZipFixture().write(temporary);
        assertThatThrownBy(() -> new StreamingGtfsParser(20).parse(FEED, zip, new Rows()))
                .isInstanceOf(ImportFailure.class).hasMessageContaining("limit");
    }

    @ParameterizedTest
    @ValueSource(strings = {"routes.txt", "stops.txt", "trips.txt", "stop_times.txt", "shapes.txt"})
    void rejectsChecksumMismatchInEveryMappedFile(String filename) throws IOException {
        var zip = new GtfsZipFixture().write(temporary);
        corruptChecksum(zip, filename);

        assertThatThrownBy(() -> parser.parse(FEED, zip, new Rows()))
                .isInstanceOfSatisfying(ImportFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ImportFailure.Kind.VALIDATION))
                .hasMessageContaining(filename);
    }

    @Test
    void leavesPersistenceFailuresUnchanged() throws IOException {
        var failure = new ImportFailure(ImportFailure.Kind.PERSISTENCE, "COPY failed");
        var zip = new GtfsZipFixture().write(temporary);
        var sink = new Rows() {
            @Override public void writeRoutes(RowSource<Route> rows) {
                rows.forEach(row -> { throw failure; });
            }
        };

        assertThatThrownBy(() -> parser.parse(FEED, zip, sink)).isSameAs(failure);
    }

    @Test
    void rejectsEmptyCoreDataInsideItsSourceBeforeCompletingTheWrite() throws IOException {
        var zip = new GtfsZipFixture().file("stop_times.txt", headers("stop_times.txt")).write(temporary);
        var writer = new Rows();

        assertThatThrownBy(() -> parser.parse(FEED, zip, writer))
                .isInstanceOfSatisfying(ImportFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ImportFailure.Kind.VALIDATION))
                .hasMessageContaining("stop_times.txt");
        assertThat(writer.tables).containsExactly("routes", "stops", "trips", "stop_times");
        assertThat(writer.completed).containsExactly("routes", "stops", "trips");
    }

    @Test
    void stopsReadingOtherTablesWhenTheWriterFails() throws IOException {
        var failure = new ImportFailure(ImportFailure.Kind.PERSISTENCE, "Cannot open stops COPY");
        var zip = new GtfsZipFixture().write(temporary);
        var writer = new Rows() {
            @Override public void writeStops(RowSource<Stop> rows) { throw failure; }
        };

        assertThatThrownBy(() -> parser.parse(FEED, zip, writer)).isSameAs(failure);
        assertThat(writer.completed).containsExactly("routes");
        assertThat(writer.trips).isEmpty();
    }

    private Rows parse(GtfsZipFixture fixture) throws IOException {
        var sink = new Rows();
        parser.parse(FEED, fixture.write(temporary), sink);
        return sink;
    }

    private static void corruptChecksum(Path zip, String filename) throws IOException {
        byte[] bytes = Files.readAllBytes(zip);
        var headers = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset = 0; offset <= bytes.length - 46; offset++) {
            if (headers.getInt(offset) != 0x02014b50) continue;
            int nameLength = Short.toUnsignedInt(headers.getShort(offset + 28));
            String name = new String(bytes, offset + 46, nameLength, StandardCharsets.UTF_8);
            if (!name.equals(filename)) continue;
            headers.putInt(offset + 16, headers.getInt(offset + 16) ^ 1);
            Files.write(zip, bytes);
            return;
        }
        throw new IllegalArgumentException("ZIP entry not found: " + filename);
    }

    private void assertInvalid(GtfsZipFixture fixture, String context) throws IOException {
        var zip = fixture.write(temporary);
        assertThatThrownBy(() -> parser.parse(FEED, zip, new Rows()))
                .isInstanceOfSatisfying(ImportFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ImportFailure.Kind.VALIDATION))
                .hasMessageContaining(context);
    }

    private static String headers(String name) {
        return switch (name) {
            case "routes.txt" -> "route_id,route_short_name\n";
            case "stops.txt" -> "stop_id,stop_name,stop_code,stop_desc,stop_lat,stop_lon\n";
            case "trips.txt" -> "trip_id,route_id,service_id,direction_id,trip_headsign,shape_id\n";
            case "stop_times.txt" -> "trip_id,stop_sequence,stop_id,arrival_time,departure_time\n";
            case "shapes.txt" -> "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n";
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static class Rows implements ScheduleWriter {
        final List<String> tables = new ArrayList<>();
        final List<String> completed = new ArrayList<>();
        final List<Route> routes = new ArrayList<>();
        final List<Stop> stops = new ArrayList<>();
        final List<Trip> trips = new ArrayList<>();
        final List<StopTime> times = new ArrayList<>();
        final List<ShapePoint> points = new ArrayList<>();

        @Override public void writeRoutes(RowSource<Route> rows) { collect("routes", rows, routes); }
        @Override public void writeStops(RowSource<Stop> rows) { collect("stops", rows, stops); }
        @Override public void writeTrips(RowSource<Trip> rows) { collect("trips", rows, trips); }
        @Override public void writeStopTimes(RowSource<StopTime> rows) { collect("stop_times", rows, times); }
        @Override public void writeShapePoints(RowSource<ShapePoint> rows) { collect("shapes", rows, points); }

        private <T> void collect(String table, RowSource<T> rows, List<T> target) {
            tables.add(table);
            rows.forEach(target::add);
            completed.add(table);
        }
    }
}
