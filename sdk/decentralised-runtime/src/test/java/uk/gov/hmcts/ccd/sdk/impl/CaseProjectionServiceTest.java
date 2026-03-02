package uk.gov.hmcts.ccd.sdk.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class CaseProjectionServiceTest {

  @Test
  void resolveStateMatchesEnumIgnoringCase() {
    Enum<?> resolved = invokeResolveState(TestState.class, "in_review");

    assertThat(resolved).isEqualTo(TestState.IN_REVIEW);
  }

  @Test
  void resolveStateThrowsForUnknownState() {
    assertThatThrownBy(() -> invokeResolveState(TestState.class, "not_a_state"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unknown state 'not_a_state' for enum TestState");
  }

  private static Enum<?> invokeResolveState(Class<? extends Enum<?>> stateType, String state) {
    try {
      Method method = CaseProjectionService.class.getDeclaredMethod(
          "resolveState", Class.class, String.class
      );
      method.setAccessible(true);
      return (Enum<?>) method.invoke(null, stateType, state);
    } catch (InvocationTargetException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new RuntimeException(cause);
    } catch (ReflectiveOperationException exception) {
      throw new RuntimeException(exception);
    }
  }

  private enum TestState {
    DRAFT,
    IN_REVIEW
  }
}
