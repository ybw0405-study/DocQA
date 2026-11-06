package com.docqa.document.lock;

import java.time.Duration;
import java.util.function.Supplier;

public interface DistributedLock {

    <T> T withLock(String key, Duration waitTime, Supplier<T> action);
}
