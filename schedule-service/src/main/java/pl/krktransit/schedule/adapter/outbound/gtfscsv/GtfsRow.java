package pl.krktransit.schedule.adapter.outbound.gtfscsv;

import org.apache.commons.csv.CSVRecord;
import pl.krktransit.schedule.application.importing.ImportFailure;
import pl.krktransit.schedule.domain.feed.FeedId;
import pl.krktransit.schedule.domain.schedule.GtfsTime;
import pl.krktransit.schedule.domain.schedule.Route;
import pl.krktransit.schedule.domain.schedule.ShapePoint;
import pl.krktransit.schedule.domain.schedule.Stop;
import pl.krktransit.schedule.domain.schedule.StopTime;
import pl.krktransit.schedule.domain.schedule.Trip;

final class GtfsRow {
    private final FeedId feed;
    private final GtfsTable table;
    private final CSVRecord row;

    GtfsRow(FeedId feed, GtfsTable table, CSVRecord row) {
        this.feed = feed;
        this.table = table;
        this.row = row;
    }

    Route route() {
        return new Route(scopedId("route_id"), requiredText("route_short_name"));
    }

    Stop stop() {
        return new Stop(scopedId("stop_id"), requiredText("stop_name"), nullableText("stop_code"),
                nullableText("stop_desc"), nullableDouble("stop_lat"), nullableDouble("stop_lon"));
    }

    Trip trip() {
        return new Trip(scopedId("trip_id"), scopedId("route_id"), originalId("service_id"),
                nullableDirection(), nullableText("trip_headsign"), tripShapeId());
    }

    private String tripShapeId() {
        String original = nullableText("shape_id");
        return original == null ? null : feed.scope(original);
    }

    StopTime stopTime() {
        return new StopTime(scopedId("trip_id"), integer("stop_sequence"),
                scopedId("stop_id"), time("arrival_time"), time("departure_time"));
    }

    ShapePoint shapePoint() {
        return new ShapePoint(scopedId("shape_id"), decimal("shape_pt_lat"), decimal("shape_pt_lon"),
                integer("shape_pt_sequence"));
    }

    private String scopedId(String column) {
        return feed.scope(originalId(column));
    }

    private String text(String column) {
        return row.isSet(column) ? row.get(column) : null;
    }

    private String originalId(String column) {
        String value = text(column);
        if (value == null || value.isBlank()) throw invalid(column, "required original ID is blank");
        return value;
    }

    private String requiredText(String column) {
        String value = text(column);
        if (value == null || value.isEmpty()) throw invalid(column, "required value is empty");
        return value;
    }

    private String nullableText(String column) {
        String value = text(column);
        return value == null || value.isEmpty() ? null : value;
    }

    private int integer(String column) {
        try {
            return Integer.parseInt(requiredText(column).strip());
        } catch (NumberFormatException failure) {
            throw invalid(column, "invalid SQL integer", failure);
        }
    }

    private Integer nullableDirection() {
        String value = nullableText("direction_id");
        if (value == null) return null;
        try {
            return (int) Short.parseShort(value.strip());
        } catch (NumberFormatException failure) {
            throw invalid("direction_id", "invalid SQL smallint", failure);
        }
    }

    private double decimal(String column) {
        try {
            return SqlDoubleValue.parse(requiredText(column));
        } catch (NumberFormatException failure) {
            throw invalid(column, "invalid SQL double", failure);
        }
    }

    private Double nullableDouble(String column) {
        return nullableText(column) == null ? null : decimal(column);
    }

    private int time(String column) {
        try {
            return GtfsTime.parse(text(column));
        } catch (IllegalArgumentException failure) {
            throw invalid(column, "invalid GTFS time", failure);
        }
    }

    private ImportFailure invalid(String column, String reason) {
        return invalid(column, reason, null);
    }

    private ImportFailure invalid(String column, String reason, Throwable cause) {
        return new ImportFailure(ImportFailure.Kind.VALIDATION,
                table.filename() + " row " + row.getRecordNumber() + ", column " + column + ": " + reason, cause);
    }
}
