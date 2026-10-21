package pl.krktransit.schedule.adapter.outbound.http;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import pl.krktransit.schedule.adapter.outbound.http.exception.DownloadLimitExceeded;
import pl.krktransit.schedule.adapter.outbound.http.exception.DownloadWriteFailure;
import pl.krktransit.schedule.application.importing.ImportFailure;

final class LimitedBodyHandler implements HttpResponse.BodyHandler<Path>, AutoCloseable {
    private final LimitedSubscriber subscriber;

    LimitedBodyHandler(Path path, long limit) {
        this(path, limit, openOutput(path));
    }

    LimitedBodyHandler(Path path, long limit, WritableByteChannel output) {
        subscriber = new LimitedSubscriber(path, limit, output);
    }

    private static WritableByteChannel openOutput(Path path) {
        try {
            return FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException failure) {
            throw new ImportFailure(ImportFailure.Kind.DOWNLOAD, "Cannot open temporary download", failure);
        }
    }

    @Override
    public HttpResponse.BodySubscriber<Path> apply(HttpResponse.ResponseInfo info) {
        subscriber.receiveBody(info.statusCode() >= 200 && info.statusCode() < 300);
        return subscriber;
    }

    @Override
    public void close() throws DownloadWriteFailure {
        subscriber.close();
    }

    private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<Path> {
        private final Path path;
        private final long limit;
        private final WritableByteChannel output;
        private final CompletableFuture<Path> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private long received;
        private boolean writeBody;
        private boolean finished;

        private LimitedSubscriber(Path path, long limit, WritableByteChannel output) {
            this.path = path;
            this.limit = limit;
            this.output = output;
        }

        private synchronized void receiveBody(boolean writeBody) {
            if (!finished) this.writeBody = writeBody;
        }

        @Override public CompletionStage<Path> getBody() { return body; }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (subscribe(subscription)) subscription.request(1);
            else subscription.cancel();
        }

        private synchronized boolean subscribe(Flow.Subscription subscription) {
            if (finished || this.subscription != null) return false;
            this.subscription = subscription;
            return true;
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            IOException failure = receive(buffers);
            if (failure != null) {
                finish(failure);
                cancelSubscription();
            } else {
                requestNext();
            }
        }

        private synchronized IOException receive(List<ByteBuffer> buffers) {
            if (finished) return null;
            try {
                countBytes(buffers);
                if (writeBody) write(buffers);
                return null;
            } catch (DownloadLimitExceeded failure) {
                return failure;
            } catch (IOException failure) {
                return new DownloadWriteFailure("Cannot write downloaded ZIP", failure);
            }
        }

        private void countBytes(List<ByteBuffer> buffers) throws DownloadLimitExceeded {
            for (var buffer : buffers) {
                if (buffer.remaining() > limit - received) throw new DownloadLimitExceeded();
                received += buffer.remaining();
            }
        }

        private void write(List<ByteBuffer> buffers) throws IOException {
            for (var buffer : buffers) {
                while (buffer.hasRemaining()) output.write(buffer);
            }
        }

        private void requestNext() {
            Flow.Subscription current;
            synchronized (this) { current = finished ? null : subscription; }
            if (current != null) current.request(1);
        }

        private void cancelSubscription() {
            Flow.Subscription current;
            synchronized (this) { current = subscription; }
            if (current != null) current.cancel();
        }

        @Override public void onError(Throwable failure) { finish(failure); }

        @Override public void onComplete() { finish(null); }

        private DownloadWriteFailure finish(Throwable failure) {
            DownloadWriteFailure closeFailure = null;
            synchronized (this) {
                if (finished) return null;
                finished = true;
                try {
                    output.close();
                } catch (IOException cause) {
                    closeFailure = new DownloadWriteFailure("Cannot close downloaded ZIP", cause);
                    if (failure != null) closeFailure.addSuppressed(failure);
                    failure = closeFailure;
                }
            }
            if (failure == null) body.complete(writeBody ? path : null);
            else body.completeExceptionally(failure);
            return closeFailure;
        }

        private void close() throws DownloadWriteFailure {
            DownloadWriteFailure failure = finish(new CancellationException("Download body closed"));
            cancelSubscription();
            if (failure != null) throw failure;
        }
    }
}
