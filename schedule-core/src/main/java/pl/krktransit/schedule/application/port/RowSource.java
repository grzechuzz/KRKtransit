package pl.krktransit.schedule.application.port;

import java.util.function.Consumer;

@FunctionalInterface
public interface RowSource<T> {
    void forEach(Consumer<? super T> receiver);
}
