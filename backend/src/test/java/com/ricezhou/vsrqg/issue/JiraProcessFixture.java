package com.ricezhou.vsrqg.issue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class JiraProcessFixture {
    private JiraProcessFixture() {}

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "arguments" -> {
                var expected = new String[] {"safe value", "literal;token", "literal&token", "literal$(token)"};
                System.out.print(Arrays.equals(Arrays.copyOfRange(args, 1, args.length), expected)
                    ? "ARGUMENTS_OK"
                    : "ARGUMENTS_BAD");
            }
            case "timeout" -> {
                Files.writeString(Path.of(args[1]), "PROCESS_STARTED");
                Thread.sleep(2_000L);
                Files.writeString(Path.of(args[2]), "PROCESS_SURVIVED_TIMEOUT");
            }
            case "streams" -> {
                var stdoutBytes = Integer.parseInt(args[1]);
                System.out.write("x".repeat(stdoutBytes).getBytes(StandardCharsets.UTF_8));
                System.err.print("runner-stderr-marker");
            }
            default -> throw new IllegalArgumentException("UNKNOWN_FIXTURE_MODE");
        }
    }
}
