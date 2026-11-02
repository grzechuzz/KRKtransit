package pl.krktransit.publicapi.adapter.inbound.http.error;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.RequestDispatcher;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

class ApiErrorAttributesTest {
    private final ApiErrorAttributes attributes = new ApiErrorAttributes();

    @Test
    void unhandledServerErrorsHideDetailsAndOtherErrorsUseTheReasonPhrase() {
        assertThat(attributes.getErrorAttributes(request(500), ErrorAttributeOptions.defaults()))
                .isEqualTo(Map.of("error_code", "INTERNAL_ERROR", "message", "Internal server error"));
        assertThat(attributes.getErrorAttributes(request(404), ErrorAttributeOptions.defaults()))
                .isEqualTo(Map.of("detail", "Not Found"));
    }

    private static ServletWebRequest request(int status) {
        var request = new MockHttpServletRequest();
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("secret"));
        return new ServletWebRequest(request);
    }
}
