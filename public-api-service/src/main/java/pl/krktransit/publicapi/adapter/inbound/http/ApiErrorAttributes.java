package pl.krktransit.publicapi.adapter.inbound.http;

import java.util.Map;
import java.util.Objects;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.web.context.request.WebRequest;

public final class ApiErrorAttributes extends DefaultErrorAttributes {
    private static final ErrorAttributeOptions STATUS_AND_REASON =
            ErrorAttributeOptions.of(ErrorAttributeOptions.Include.STATUS, ErrorAttributeOptions.Include.ERROR);
    private static final Map<String, Object> INTERNAL_ERROR =
            Map.of("error_code", "INTERNAL_ERROR", "message", "Internal server error");
    private static final int FIRST_SERVER_ERROR = 500;

    @Override
    public Map<String, Object> getErrorAttributes(WebRequest request, ErrorAttributeOptions options) {
        var defaults = super.getErrorAttributes(request, STATUS_AND_REASON);
        return isServerError(defaults) ? INTERNAL_ERROR : Map.of("detail", Objects.toString(defaults.get("error")));
    }

    private static boolean isServerError(Map<String, Object> attributes) {
        return attributes.get("status") instanceof Integer status && status >= FIRST_SERVER_ERROR;
    }
}
