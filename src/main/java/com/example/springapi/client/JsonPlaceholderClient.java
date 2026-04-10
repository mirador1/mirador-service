package com.example.springapi.client;

import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import java.util.List;

/**
 * Declarative HTTP client (Spring 6 HTTP Interface) targeting JSONPlaceholder.
 * The proxy is created by HttpServiceProxyFactory in HttpClientConfig.
 */
@HttpExchange(url = "https://jsonplaceholder.typicode.com")
public interface JsonPlaceholderClient {

    @GetExchange("/users/{id}/todos")
    List<TodoItem> getTodos(long id);
}
