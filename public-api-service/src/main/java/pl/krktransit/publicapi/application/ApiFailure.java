package pl.krktransit.publicapi.application;

public abstract sealed class ApiFailure extends RuntimeException {
    private ApiFailure(String message, Throwable cause) { super(message, cause); }

    public static final class NotFound extends ApiFailure {
        public NotFound(String resource, String identifier) { super(resource + " '" + identifier + "' not found", null); }
    }

    public static final class InvalidRequest extends ApiFailure {
        public InvalidRequest(String message) { super(message, null); }
    }

    public static final class UpstreamUnavailable extends ApiFailure {
        public UpstreamUnavailable(String service, Throwable cause) { super(service + " is unavailable", cause); }
    }

    public static final class UpstreamBusy extends ApiFailure {
        public UpstreamBusy(String service, Throwable cause) { super(service + " is busy", cause); }
    }

    public static final class UpstreamRejected extends ApiFailure {
        public UpstreamRejected(String service, Throwable cause) { super(service + " rejected the request", cause); }
    }
}
