package pl.krktransit.publicapi.adapter.inbound.http;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Size(max = 128)
@Pattern(regexp = "^[a-zA-Z0-9_:\\-]+$")
@Constraint(validatedBy = {})
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface EntityId {
    String message() default "Invalid identifier";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
