package org.techbd.service.http.hub.prime.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ErrorResponse(String status, String message) {
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST) // Set the appropriate HTTP status code
    @ApiResponse(responseCode = "400", description = "Validation Error", content = @Content(mediaType = "application/json", examples = @ExampleObject(value = "{\"status\":\"Error\",\"message\":\"${message}\"}")))
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(HttpMessageNotReadableException ex) {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                .getRequest();
        String tenantId = request.getHeader("X-Tenant-ID"); // Use your actual header name
        LOG.error("Validation Error: Required request body is missing. Tenant ID: {}", tenantId, ex);
        ErrorResponse response = new ErrorResponse("Error", "Validation Error: Required request body is missing");
        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ApiResponse(responseCode = "400", description = "Validation Error", content = @Content(mediaType = "application/json", examples = @ExampleObject(value = "{\"status\":\"Error\",\"message\":\"${message}\"}")))
    public ResponseEntity<String> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException ex) {
        String customMessage = ex.getMessage();
        String parameterName = ex.getName(); // Get the parameter name from ex.getName()
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                .getRequest();
        String tenantId = request.getHeader("X-Tenant-ID"); // Use your actual header name

        LOG.error("Validation Error: {}. Parameter Name: {}. Tenant ID: {}", customMessage, parameterName, tenantId,
                ex);

        // Directly creating a JSON response
        String responseBody = String.format(
                "{\"status\":\"Error\",\"message\":\"Validation Error: %s. Parameter Name: %s\"}", customMessage,
                parameterName);
        return new ResponseEntity<>(responseBody, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ApiResponse(responseCode = "400", description = "Validation Error", content = @Content(mediaType = "application/json", examples = @ExampleObject(value = "{\"status\":\"Error\",\"message\":\"${message}\"}")))
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException ex) {
        return handleException(ex, ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ApiResponse(responseCode = "400", description = "Validation Error", content = @Content(mediaType = "application/json", examples = @ExampleObject(value = "{\"status\":\"Error\",\"message\":\"${message}\"}")))
    public ResponseEntity<ErrorResponse> handleMissingRequestHeaderException(MissingRequestHeaderException ex) {
        return handleException(ex, ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MissingRequestValueException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ApiResponse(responseCode = "400", description = "Validation Error", content = @Content(mediaType = "application/json", examples = @ExampleObject(value = "{\"status\":\"Error\",\"message\":\"${message}\"}")))
    public ResponseEntity<ErrorResponse> handleMissingRequestValueException(MissingRequestValueException ex) {
        return handleException(ex, ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    @ApiResponse(responseCode = "415", description = "Unsupported Media Type", content = @Content(mediaType = "application/json", examples = @ExampleObject(value = "{\"status\":\"Error\",\"message\":\"Unsupported media type\"}")))
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupportedException(HttpMediaTypeNotSupportedException ex) {
        return handleException(ex, "Unsupported media type", HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    public ResponseEntity<String> handleGeneralException(Exception ex) {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                .getRequest();
        HttpSession session = request.getSession(false); // Retrieve session if it exists, else null
        String tenantId = request.getHeader("X-Tenant-ID");
        String sessionId = session != null ? session.getId() : "No session";
        String userAgent = request.getHeader("User-Agent");
        String remoteAddress = request.getRemoteAddr();
        String method = request.getMethod();
        String queryString = request.getQueryString();
        String requestUri = request.getRequestURI();
        LOG.error(
                "Internal Server Error occurred. Tenant ID: {}, Session ID: {},  User-Agent: {}, Remote Address: {}, Method: {}, Query: {}, URI: {}",
                tenantId, sessionId, userAgent, remoteAddress, method, queryString, requestUri, ex);

        String responseBody = "{\"status\":\"Error\",\"message\":\"An unexpected system error occurred.\"}";
        return new ResponseEntity<>(responseBody, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<ErrorResponse> handleException(Exception ex, String customMessage, HttpStatus status) {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                .getRequest();
        String tenantId = request.getHeader("X-Tenant-ID"); 
        LOG.error("Validation Error: {}. Tenant ID: {}", customMessage, tenantId, ex);
        HttpSession session = request.getSession(false); // Retrieve session if it exists, else null
        String sessionId = session != null ? session.getId() : "No session";
        String userAgent = request.getHeader("User-Agent");
        String remoteAddress = request.getRemoteAddr();
        String method = request.getMethod();
        String queryString = request.getQueryString();
        String requestUri = request.getRequestURI();

        LOG.error(
                "Error occurred. Tenant ID: {}, Session ID: {},  User-Agent: {}, Remote Address: {}, Method: {}, Query: {}, URI: {}",
                tenantId, sessionId, userAgent, remoteAddress, method, queryString, requestUri, ex);
        ErrorResponse response = new ErrorResponse("Error", String.format("Validation Error: %s", customMessage));
        return new ResponseEntity<>(response, status);
    }
}
