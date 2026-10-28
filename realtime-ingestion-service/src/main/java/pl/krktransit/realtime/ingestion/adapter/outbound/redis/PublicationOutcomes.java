package pl.krktransit.realtime.ingestion.adapter.outbound.redis;

import io.lettuce.core.RedisCommandExecutionException;

final class PublicationOutcomes {
    private PublicationOutcomes() {}

    static PublicationStatistics.Outcome afterDispatch(Throwable failure) {
        if (failure == null) {
            return PublicationStatistics.Outcome.PUBLISHED;
        }
        if (isRedisRejection(failure)) {
            return PublicationStatistics.Outcome.DROPPED;
        }
        return PublicationStatistics.Outcome.UNCONFIRMED;
    }

    static boolean requiresReconnect(Throwable failure) {
        return failure != null && !isRedisRejection(failure);
    }

    private static boolean isRedisRejection(Throwable failure) {
        return failure instanceof RedisCommandExecutionException;
    }
}
