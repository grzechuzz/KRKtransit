package pl.krktransit.publicapi.adapter.inbound.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

public final class RateLimitInterceptor implements HandlerInterceptor {
    private final ClientRateLimits limits;

    public RateLimitInterceptor(ClientRateLimits limits) { this.limits = limits; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (CorsUtils.isPreFlightRequest(request)) return true;
        var route = route(request);
        if (!limits.tryAcquire(route, request.getRemoteAddr())) throw new RateLimitExceeded(route);
        return true;
    }

    private static String route(HttpServletRequest request) {
        return Objects.toString(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE), "");
    }
}
