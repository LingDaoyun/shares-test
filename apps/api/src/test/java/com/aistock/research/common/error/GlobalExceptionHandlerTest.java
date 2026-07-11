package com.aistock.research.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void preservesExpectedResponseStatusExceptions() {
        var response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "该股票尚未加入特别关注")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("该股票尚未加入特别关注");
    }
}
