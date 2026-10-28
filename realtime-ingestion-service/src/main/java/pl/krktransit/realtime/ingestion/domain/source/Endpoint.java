package pl.krktransit.realtime.ingestion.domain.source;

import java.net.URI;
import java.util.Objects;

public record Endpoint(SourceId source, FeedKind kind, URI url) {
    public Endpoint {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(url, "url");
        if (!isAbsoluteHttpUrl(url)) {
            throw new IllegalArgumentException("Realtime feed URL must be an absolute HTTP(S) URL");
        }
    }

    private static boolean isAbsoluteHttpUrl(URI url) {
        return url.isAbsolute() && url.getHost() != null
                && ("https".equals(url.getScheme()) || "http".equals(url.getScheme()));
    }

    @Override
    public String toString() {
        return source + "/" + kind;
    }
}
