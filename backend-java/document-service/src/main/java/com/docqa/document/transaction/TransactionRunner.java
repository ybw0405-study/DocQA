package com.docqa.document.transaction;

import java.util.function.Supplier;

public interface TransactionRunner {

    <T> T execute(Supplier<T> action);
}
