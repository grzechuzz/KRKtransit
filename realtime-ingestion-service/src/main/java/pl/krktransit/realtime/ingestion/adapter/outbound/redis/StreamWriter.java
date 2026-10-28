package pl.krktransit.realtime.ingestion.adapter.outbound.redis;

import java.util.concurrent.CompletionStage;

interface StreamWriter extends AutoCloseable {
    boolean isOpen();

    CompletionStage<String> append(StreamTarget target, byte[] payload);

    @Override
    void close();
}
