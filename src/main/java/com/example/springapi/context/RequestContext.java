package com.example.springapi.context;

public final class RequestContext {

    private RequestContext() {
    }

    public static final ScopedValue<String> REQUEST_ID = ScopedValue.newInstance();
}
