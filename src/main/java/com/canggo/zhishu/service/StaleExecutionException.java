package com.canggo.zhishu.service;

public class StaleExecutionException extends RuntimeException {

    public StaleExecutionException(String message) {
        super(message);
    }
}
