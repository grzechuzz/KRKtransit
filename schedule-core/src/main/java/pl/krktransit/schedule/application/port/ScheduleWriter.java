package pl.krktransit.schedule.application.port;

import pl.krktransit.schedule.domain.schedule.Route;
import pl.krktransit.schedule.domain.schedule.ShapePoint;
import pl.krktransit.schedule.domain.schedule.Stop;
import pl.krktransit.schedule.domain.schedule.StopTime;
import pl.krktransit.schedule.domain.schedule.Trip;

public interface ScheduleWriter {
    void writeRoutes(RowSource<Route> rows);
    void writeStops(RowSource<Stop> rows);
    void writeTrips(RowSource<Trip> rows);
    void writeStopTimes(RowSource<StopTime> rows);
    void writeShapePoints(RowSource<ShapePoint> rows);
}
