package autodagger;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author Lukasz Piliszczuk - lukasz.pili@gmail.com
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface AutoInjector {

    Class<?>[] value() default void.class;

    Class<?>[] parameterizedTypes() default void.class;
}
