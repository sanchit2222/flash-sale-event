package com.cred.freestyle.flashsale.api.exception;

import com.cred.freestyle.flashsale.api.dto.ErrorResponse;
import com.cred.freestyle.flashsale.exception.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;

import java.util.HashMap;
import java.util.Map;

/**
 * Global exception handler for the flash sale API.
 * Catches all exceptions thrown by controllers and converts them to standardized error responses.
 *
 * @author Flash Sale Team
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handle OutOfStockException.
     * Returns 409 CONFLICT when product is out of stock.
     */
    @ExceptionHandler(OutOfStockException.class)
    public ResponseEntity<ErrorResponse> handleOutOfStockException(
            OutOfStockException ex,
            HttpServletRequest request
    ) {
        logger.warn("Out of stock: {}", ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                "Out of Stock",
                ex.getMessage(),
                request.getRequestURI()
        );
        error.addDetail("skuId", ex.getSkuId());
        error.addDetail("requestedQuantity", ex.getRequestedQuantity());
        error.addDetail("availableQuantity", ex.getAvailableQuantity());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    /**
     * Handle UserLimitExceededException.
     * Returns 403 FORBIDDEN when user exceeds purchase limit.
     */
    @ExceptionHandler(UserLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleUserLimitExceededException(
            UserLimitExceededException ex,
            HttpServletRequest request
    ) {
        logger.warn("User limit exceeded: {}", ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                "Purchase Limit Exceeded",
                ex.getMessage(),
                request.getRequestURI()
        );
        error.addDetail("userId", ex.getUserId());
        error.addDetail("skuId", ex.getSkuId());

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error);
    }

    /**
     * Handle ReservationExpiredException.
     * Returns 410 GONE when reservation has expired.
     */
    @ExceptionHandler(ReservationExpiredException.class)
    public ResponseEntity<ErrorResponse> handleReservationExpiredException(
            ReservationExpiredException ex,
            HttpServletRequest request
    ) {
        logger.warn("Reservation expired: {}", ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                HttpStatus.GONE.value(),
                "Reservation Expired",
                ex.getMessage(),
                request.getRequestURI()
        );
        error.addDetail("reservationId", ex.getReservationId());

        return ResponseEntity.status(HttpStatus.GONE).body(error);
    }

    /**
     * Handle ReservationFailedException.
     * Returns appropriate HTTP status based on rejection reason.
     */
    @ExceptionHandler(ReservationFailedException.class)
    public ResponseEntity<ErrorResponse> handleReservationFailedException(
            ReservationFailedException ex,
            HttpServletRequest request
    ) {
        logger.warn("Reservation failed: {} - {}", ex.getStatus(), ex.getErrorMessage());

        // Determine HTTP status based on rejection status
        HttpStatus httpStatus;
        String errorTitle;

        String status = ex.getStatus();
        if (status.contains("OUT_OF_STOCK")) {
            httpStatus = HttpStatus.CONFLICT;
            errorTitle = "Out of Stock";
        } else if (status.contains("USER_ALREADY_PURCHASED")) {
            httpStatus = HttpStatus.FORBIDDEN;
            errorTitle = "Purchase Limit Exceeded";
        } else if (status.contains("USER_HAS_ACTIVE_RESERVATION")) {
            httpStatus = HttpStatus.CONFLICT;
            errorTitle = "Active Reservation Exists";
        } else if (status.contains("DUPLICATE_REQUEST")) {
            httpStatus = HttpStatus.CONFLICT;
            errorTitle = "Duplicate Request";
        } else if (status.contains("INVALID_REQUEST")) {
            httpStatus = HttpStatus.BAD_REQUEST;
            errorTitle = "Invalid Request";
        } else {
            httpStatus = HttpStatus.BAD_REQUEST;
            errorTitle = "Reservation Failed";
        }

        ErrorResponse error = new ErrorResponse(
                httpStatus.value(),
                errorTitle,
                ex.getErrorMessage(),
                request.getRequestURI()
        );
        error.addDetail("rejectionStatus", ex.getStatus());

        return ResponseEntity.status(httpStatus).body(error);
    }

    /**
     * Handle ReservationNotFoundException.
     * Returns 404 NOT FOUND when reservation doesn't exist.
     */
    @ExceptionHandler(ReservationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleReservationNotFoundException(
            ReservationNotFoundException ex,
            HttpServletRequest request
    ) {
        logger.warn("Reservation not found: {}", ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                "Reservation Not Found",
                ex.getMessage(),
                request.getRequestURI()
        );
        error.addDetail("reservationId", ex.getReservationId());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * Handle ResourceNotFoundException.
     * Returns 404 NOT FOUND when any resource doesn't exist.
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFoundException(
            ResourceNotFoundException ex,
            HttpServletRequest request
    ) {
        logger.warn("Resource not found: {}", ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                "Resource Not Found",
                ex.getMessage(),
                request.getRequestURI()
        );
        error.addDetail("resourceType", ex.getResourceType());
        error.addDetail("resourceId", ex.getResourceId());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    /**
     * Handle IllegalStateException.
     * Returns 400 BAD REQUEST for general business logic violations.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalStateException(
            IllegalStateException ex,
            HttpServletRequest request
    ) {
        logger.warn("Illegal state: {}", ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Invalid Request",
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Handle IllegalArgumentException.
     * Returns 400 BAD REQUEST for invalid arguments.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException ex,
            HttpServletRequest request
    ) {
        logger.warn("Illegal argument: {}", ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Invalid Argument",
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Handle validation errors from @Valid annotation.
     * Returns 400 BAD REQUEST with field-level validation errors.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        logger.warn("Validation failed: {} field errors", ex.getBindingResult().getFieldErrorCount());

        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }

        ErrorResponse error = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Validation Failed",
                "Request validation failed. Please check the field errors.",
                request.getRequestURI()
        );
        error.addDetail("fieldErrors", fieldErrors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * Handle all other uncaught exceptions.
     * Returns 500 INTERNAL SERVER ERROR.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(
            Exception ex,
            HttpServletRequest request
    ) {
        logger.error("Unexpected error: ", ex);

        ErrorResponse error = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                "An unexpected error occurred. Please try again later.",
                request.getRequestURI()
        );

        // In production, don't expose internal error details
        // error.addDetail("exceptionType", ex.getClass().getSimpleName());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}
