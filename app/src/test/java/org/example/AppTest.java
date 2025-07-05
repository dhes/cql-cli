package org.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppTest {
    @Test void appCanInstantiate() {
        App classUnderTest = new App();
        assertNotNull(classUnderTest, "app should be able to instantiate");
    }
}