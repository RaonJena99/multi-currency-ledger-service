package com.github.raonjena99.multi_currency_ledger_service.common.exception;

public class DoubleEntryImbalanceException extends RuntimeException {
    public DoubleEntryImbalanceException(String message) { super(message); }
}
