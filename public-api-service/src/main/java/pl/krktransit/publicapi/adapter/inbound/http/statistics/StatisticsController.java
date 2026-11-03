package pl.krktransit.publicapi.adapter.inbound.http.statistics;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.krktransit.publicapi.adapter.inbound.http.json.DelayMapResponse;
import pl.krktransit.publicapi.adapter.inbound.http.json.MaxDelayResponse;
import pl.krktransit.publicapi.adapter.inbound.http.json.PunctualityResponse;
import pl.krktransit.publicapi.adapter.inbound.http.json.RouteDelayResponse;
import pl.krktransit.publicapi.adapter.inbound.http.json.TrendResponse;
import pl.krktransit.publicapi.adapter.inbound.http.validation.LineNumber;
import pl.krktransit.publicapi.application.delaymap.LineDelayMap;
import pl.krktransit.publicapi.application.statistics.LineStatistics;

@RestController
@RequestMapping("/v1/lines/{line_number}/stats")
public class StatisticsController {
    private final LineStatistics statistics;
    private final LineDelayMap delayMap;
    private final DelayMapResponseMapper delayMapMapper = new DelayMapResponseMapper();
    private final StatisticsResponseMapper mapper = new StatisticsResponseMapper();

    public StatisticsController(LineStatistics statistics, LineDelayMap delayMap) {
        this.statistics = statistics;
        this.delayMap = delayMap;
    }

    @GetMapping("/delay-map")
    public DelayMapResponse delayMap(
            @PathVariable("line_number") @LineNumber String lineNumber, @Valid StatisticsParams params) {
        var query = params.query(lineNumber);
        return delayMapMapper.response(query, delayMap.get(query));
    }

    @GetMapping("/max-delay")
    public MaxDelayResponse maxDelay(@PathVariable("line_number") @LineNumber String lineNumber,
                                     @Valid StatisticsParams params) {
        var query = params.query(lineNumber);
        return mapper.maxDelay(query, statistics.maxDelay(query));
    }

    @GetMapping("/route-delay")
    public RouteDelayResponse routeDelay(@PathVariable("line_number") @LineNumber String lineNumber,
                                         @Valid StatisticsParams params) {
        var query = params.query(lineNumber);
        return mapper.routeDelay(query, statistics.routeDelay(query));
    }

    @GetMapping("/punctuality")
    public PunctualityResponse punctuality(@PathVariable("line_number") @LineNumber String lineNumber,
                                           @Valid StatisticsParams params) {
        var query = params.query(lineNumber);
        return mapper.punctuality(query, statistics.punctuality(query));
    }

    @GetMapping("/trend")
    public TrendResponse trend(@PathVariable("line_number") @LineNumber String lineNumber,
                               @Valid StatisticsParams params) {
        var query = params.query(lineNumber);
        return mapper.trend(query, statistics.trend(query));
    }
}
