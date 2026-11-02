package pl.krktransit.publicapi.adapter.inbound.http.error;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.minBy;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.core.MethodParameter;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import pl.krktransit.publicapi.adapter.inbound.http.json.ValidationIssue;

final class ValidationIssues {
    private static final String MISSING = "missing";
    private static final String DATE_PARSING = "date_parsing";
    private static final String BOOL_PARSING = "bool_parsing";
    private static final String STRING_TOO_LONG = "string_too_long";
    private static final String STRING_PATTERN_MISMATCH = "string_pattern_mismatch";
    private static final String VALUE_ERROR = "value_error";
    private static final List<String> PRIORITY =
            List.of(MISSING, DATE_PARSING, BOOL_PARSING, STRING_TOO_LONG, STRING_PATTERN_MISMATCH, VALUE_ERROR);
    private static final String PATH = "path";
    private static final String QUERY = "query";
    private static final String TYPE_MISMATCH = "typeMismatch.";
    private static final String INVALID_DATE = "Input should be a valid date";
    private static final String INVALID_BOOLEAN = "Input should be a valid boolean, unable to interpret input";
    private static final String INVALID_VALUE = "Invalid value";
    private static final String FIELD_REQUIRED = "Field required";

    private ValidationIssues() {}

    static List<ValidationIssue> of(HandlerMethodValidationException failure) {
        return firstPerField(failure.getParameterValidationResults().stream().flatMap(ValidationIssues::issues));
    }

    static List<ValidationIssue> of(BindingResult result) {
        return firstPerField(result.getFieldErrors().stream().map(ValidationIssues::issue));
    }

    private static Stream<ValidationIssue> issues(ParameterValidationResult result) {
        if (result instanceof ParameterErrors errors) return errors.getFieldErrors().stream().map(ValidationIssues::issue);
        var location = location(result.getMethodParameter());
        return result.getResolvableErrors().stream()
                .map(error -> constraintIssue(result.unwrap(error, ConstraintViolation.class), location, result.getArgument()));
    }

    private static ValidationIssue issue(FieldError error) {
        var location = List.of(QUERY, snakeCase(error.getField()));
        if (error.contains(ConstraintViolation.class)) {
            return constraintIssue(error.unwrap(ConstraintViolation.class), location, error.getRejectedValue());
        }
        return conversionIssue(error, location);
    }

    private static ValidationIssue constraintIssue(ConstraintViolation<?> violation, List<String> location, Object input) {
        return switch (violation.getConstraintDescriptor().getAnnotation()) {
            case Pattern pattern -> new ValidationIssue(STRING_PATTERN_MISMATCH, location,
                    "String should match pattern '" + pattern.regexp() + "'", input);
            case Size size -> new ValidationIssue(STRING_TOO_LONG, location,
                    "String should have at most " + size.max() + " characters", input);
            case NotNull ignored -> new ValidationIssue(MISSING, location, FIELD_REQUIRED, null);
            default -> new ValidationIssue(VALUE_ERROR, location, violation.getMessage(), input);
        };
    }

    private static ValidationIssue conversionIssue(FieldError error, List<String> location) {
        Object input = error.getRejectedValue();
        if (rejectsType(error, LocalDate.class)) return new ValidationIssue(DATE_PARSING, location, INVALID_DATE, input);
        if (rejectsType(error, Boolean.class)) return new ValidationIssue(BOOL_PARSING, location, INVALID_BOOLEAN, input);
        return new ValidationIssue(VALUE_ERROR, location, INVALID_VALUE, input);
    }

    private static boolean rejectsType(FieldError error, Class<?> type) {
        return error.getCodes() != null && Arrays.asList(error.getCodes()).contains(TYPE_MISMATCH + type.getName());
    }

    private static List<String> location(MethodParameter parameter) {
        var path = parameter.getParameterAnnotation(PathVariable.class);
        return path == null ? List.of(QUERY, parameter.getParameterName()) : List.of(PATH, path.value());
    }

    private static String snakeCase(String field) { return field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(); }

    private static List<ValidationIssue> firstPerField(Stream<ValidationIssue> issues) {
        return issues.collect(groupingBy(ValidationIssue::loc, LinkedHashMap::new, minBy(comparing(ValidationIssues::priority))))
                .values().stream().flatMap(Optional::stream).sorted(comparing(issue -> String.join(".", issue.loc())))
                .toList();
    }

    private static int priority(ValidationIssue issue) { return PRIORITY.indexOf(issue.type()); }
}
