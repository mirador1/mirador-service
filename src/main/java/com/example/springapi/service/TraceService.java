package com.example.springapi.service;

import com.example.springapi.context.RequestContext;
import org.springframework.stereotype.Service;

@Service
public class TraceService {

    public String currentRequestIdOrDefault() {
        return RequestContext.REQUEST_ID.orElse("no-request-id");
    }
}
