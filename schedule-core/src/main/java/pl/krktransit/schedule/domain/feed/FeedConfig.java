package pl.krktransit.schedule.domain.feed;

import java.net.URI;
import java.util.Objects;

public record FeedConfig(FeedId id, URI staticUrl) {
    public FeedConfig {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(staticUrl, "staticUrl");
        if (!staticUrl.isAbsolute() || staticUrl.getHost() == null
                || !("https".equals(staticUrl.getScheme()) || "http".equals(staticUrl.getScheme()))) {
            throw new IllegalArgumentException("staticUrl must be an absolute HTTP(S) URL");
        }
    }
}
