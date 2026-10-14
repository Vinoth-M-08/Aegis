package com.aegis.support;

import java.util.concurrent.Callable;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Runs JDBC and other blocking calls off the event loop.
 */
public final class Blocking {

    private Blocking() {
    }

    public static <T> Mono<T> mono(Callable<T> call) {
        return Mono.fromCallable(call).subscribeOn(Schedulers.boundedElastic());
    }

    public static Mono<Void> run(Runnable action) {
        return Mono.<Void>fromRunnable(action).subscribeOn(Schedulers.boundedElastic());
    }
}
