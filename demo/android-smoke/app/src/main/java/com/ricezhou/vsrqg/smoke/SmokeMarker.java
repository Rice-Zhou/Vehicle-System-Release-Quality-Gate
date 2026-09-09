package com.ricezhou.vsrqg.smoke;

public final class SmokeMarker {
    private static final String UUID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private SmokeMarker() {}

    public static String render(String id, String mode) {
        if (id == null || !id.matches(UUID_PATTERN)) {
            throw new IllegalArgumentException("SMOKE_INPUT_INVALID");
        }
        if ("normal".equals(mode)) {
            return "VSRQG_SMOKE_READY:" + id;
        }
        if ("assertion-failure".equals(mode)) {
            return "VSRQG_SMOKE_NOT_READY:" + id;
        }
        throw new IllegalArgumentException("SMOKE_INPUT_INVALID");
    }
}
