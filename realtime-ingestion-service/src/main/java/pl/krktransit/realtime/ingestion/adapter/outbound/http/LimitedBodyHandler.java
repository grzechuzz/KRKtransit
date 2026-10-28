package pl.krktransit.realtime.ingestion.adapter.outbound.http;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

final class LimitedBodyHandler implements HttpResponse.BodyHandler<byte[]> {
    private final int limit;

    LimitedBodyHandler(int limit) {
        this.limit = limit;
    }

    @Override
    public HttpResponse.BodySubscriber<byte[]> apply(HttpResponse.ResponseInfo info) {
        if (isSuccess(info.statusCode())) return new LimitedSubscriber(limit);
        return HttpResponse.BodySubscribers.replacing(null);
    }

    static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }

    private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private LimitedSubscriber(int limit) {
            this.limit = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() { return body; }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            for (var buffer : buffers) {
                if (exceedsLimit(buffer)) {
                    rejectOversizedBody();
                    return;
                }
                append(buffer);
            }
        }

        private boolean exceedsLimit(ByteBuffer buffer) {
            return buffer.remaining() > limit - received.size();
        }

        private void append(ByteBuffer buffer) {
            byte[] chunk = new byte[buffer.remaining()];
            buffer.get(chunk);
            received.writeBytes(chunk);
        }

        private void rejectOversizedBody() {
            body.completeExceptionally(new BodyTooLarge(limit));
            subscription.cancel();
        }

        @Override
        public void onError(Throwable failure) { body.completeExceptionally(failure); }

        @Override
        public void onComplete() { body.complete(received.toByteArray()); }
    }
}
