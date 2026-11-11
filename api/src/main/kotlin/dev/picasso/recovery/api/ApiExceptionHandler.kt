package dev.picasso.recovery.api

import com.fasterxml.jackson.core.JsonProcessingException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** A body that fails to parse and an illegal argument are 400s; a generic exception is left a 500. */
@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(JsonProcessingException::class, IllegalArgumentException::class)
    fun handleBadRequest(e: Exception): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(mapOf("error" to (e.message ?: "invalid request")))
}
