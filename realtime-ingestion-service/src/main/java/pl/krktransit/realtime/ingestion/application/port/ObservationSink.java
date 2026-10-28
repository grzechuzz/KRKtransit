package pl.krktransit.realtime.ingestion.application.port;

import java.util.List;
import pl.krktransit.realtime.ingestion.domain.observation.RealtimeObservation;

public interface ObservationSink {
    void awaitCapacity() throws InterruptedException;

    boolean offer(List<RealtimeObservation> observations);
}
