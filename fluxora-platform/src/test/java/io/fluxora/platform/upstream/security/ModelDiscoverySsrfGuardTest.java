package io.fluxora.platform.upstream.security;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
class ModelDiscoverySsrfGuardTest {
 @Test void rejectsLoopbackAndPrivateTargets(){assertThatThrownBy(()->ModelDiscoverySsrfGuard.validate("http://127.0.0.1:8080/v1")).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->ModelDiscoverySsrfGuard.validate("http://localhost/v1")).isInstanceOf(IllegalArgumentException.class);}
 @Test void rejectsNonHttpSchemes(){assertThatThrownBy(()->ModelDiscoverySsrfGuard.validate("file:///etc/passwd")).isInstanceOf(IllegalArgumentException.class);}
}
