package com.ricezhou.vsrqg.smoke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class SmokeMarkerTest {
    @Test
    public void modesAndInvalidInputRemainDistinct() {
        String id = "01990000-0000-7000-8000-000000000001";
        assertEquals("VSRQG_SMOKE_READY:" + id, SmokeMarker.render(id, "normal"));
        assertEquals(
                "VSRQG_SMOKE_NOT_READY:" + id,
                SmokeMarker.render(id, "assertion-failure"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SmokeMarker.render("1-1-1-1-1", "normal"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SmokeMarker.render(id, "anything"));
    }
}
