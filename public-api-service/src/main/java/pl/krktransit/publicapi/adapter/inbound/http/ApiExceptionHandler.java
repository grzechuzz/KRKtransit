package pl.krktransit.publicapi.adapter.inbound.http;

import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import pl.krktransit.publicapi.adapter.inbound.http.json.DetailResponse;
import pl.krktransit.publicapi.adapter.inbound.http.json.ErrorResponse;
import pl.krktransit.publicapi.adapter.inbound.http.json.ValidationErrorResponse;
import pl.krktransit.publicapi.adapter.inbound.http.json.ValidationIssue;
import pl.krktransit.publicapi.application.ApiFailure;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final System.Logger LOG = System.getLogger(ApiExceptionHandler.class.getName());
    private static final ErrorResponse INTERNAL_ERROR = new ErrorResponse("INTERNAL_ERROR", "Internal server error");

    @ExceptionHandler(ApiFailure.class)
    public ResponseEntity<ErrorResponse> apiFailure(ApiFailure failure) {
        return switch (failure) {
            case ApiFailure.NotFound notFound -> error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", notFound);
            case ApiFailure.InvalidRequest invalid -> error(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_ERROR", invalid);
            case ApiFailure.UpstreamUnavailable unavailable -> upstreamFailure(HttpStatus.BAD_GATEWAY, unavailable);
            case ApiFailure.UpstreamBusy busy -> upstreamFailure(HttpStatus.SERVICE_UNAVAILABLE, busy);
            case ApiFailure.UpstreamRejected rejected -> internalError(rejected);
        };
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException failure,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        return invalidParameters(ValidationIssues.of(failure.getBindingResult()));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException failure,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        return invalidParameters(ValidationIssues.of(failure));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception failure, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        return ResponseEntity.status(status).headers(headers).body(new DetailResponse(reasonPhrase(status)));
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, ApiFailure failure) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, failure.getMessage()));
    }

    private ResponseEntity<ErrorResponse> upstreamFailure(HttpStatus status, ApiFailure failure) {
        LOG.log(System.Logger.Level.WARNING, failure.getMessage(), failure.getCause());
        return error(status, "EXTERNAL_SERVICE_ERROR", failure);
    }

    private ResponseEntity<ErrorResponse> internalError(ApiFailure failure) {
        LOG.log(System.Logger.Level.ERROR, failure.getMessage(), failure.getCause());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(INTERNAL_ERROR);
    }

    private ResponseEntity<Object> invalidParameters(List<ValidationIssue> issues) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(new ValidationErrorResponse(issues));
    }

    private static String reasonPhrase(HttpStatusCode status) {
        var known = HttpStatus.resolve(status.value());
        return known == null ? String.valueOf(status.value()) : known.getReasonPhrase();
    }
}
