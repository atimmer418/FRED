package com.investingapp.backend.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No route may put the text of an exception into its reply.
 *
 * <p>An exception's text is written for a developer. It can carry the broker's or the bank's raw
 * answer, a web address with an account id in it, SQL, or a Java class name. The rule is: log the
 * detail, and answer with a sentence written for the user.
 *
 * <p>The one exception to the rule is {@code UserFacingException}, whose text IS written for the
 * user. A line that passes its text on says so with the marker {@code // user-facing}.
 *
 * <p>This reads the controller sources, so a new route that breaks the rule fails here even if
 * nobody thought to write a test for it.
 */
class RepliesNeverEchoExceptionTextTest {

    private static final Path CONTROLLERS = Path.of("src/main/java/com/investingapp/backend/controller");
    /** Reads a code or a type off the exception, never its text. */
    private static final Pattern ALLOWED_USE = Pattern.compile(
            "instanceof|\\.getStatusCode\\(\\)|\\.reason\\b|\\.equals\\(e\\.getMessage\\(\\)\\)|\\.getBindingResult\\(\\)|\\.code\\(\\)|detailOf\\(");
    private static final Pattern METHOD_START = Pattern.compile("^    (public|private|protected|static|@)");
    private static final Pattern LOG_CALL = Pattern.compile("^\\s*(logger|log|LOGGER)\\.");

    @Test
    @DisplayName("no controller builds a reply out of an exception's text")
    void noControllerEchoesExceptionText() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(CONTROLLERS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                // Dev tools are off in production and sit behind their own key.
                if (file.getFileName().toString().startsWith("Dev")) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file);
                String caught = null;
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    var catchMatch = Pattern.compile("catch \\(([\\w.| ]+?) (\\w+)\\)").matcher(line);
                    if (catchMatch.find()) {
                        caught = catchMatch.group(2);
                    }
                    // The end of a method ends the catch block's reach.
                    if (line.equals("    }") || METHOD_START.matcher(line).find()) {
                        caught = null;
                    }
                    String stripped = line.strip();
                    if (caught == null || line.contains("// user-facing") || line.contains("// not in the reply")
                            || stripped.startsWith("//") || stripped.startsWith("*") || stripped.startsWith("/*")) {
                        continue;
                    }
                    // Words inside quotes are text, not the variable.
                    line = line.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
                    // ANY use of the caught exception is suspect, not only getMessage(): "+ e",
                    // String.valueOf(e), e.getCause(), a helper given e. What is allowed: logging
                    // it, throwing it (or wrapping it in another exception), testing its type or
                    // a code on it, and the catch line itself.
                    boolean uses = Pattern.compile("\\b" + caught + "\\b").matcher(line).find();
                    boolean allowed = catchMatch.find(0)
                            || isLogStatement(lines, i)
                            || line.strip().startsWith("throw ")
                            || ALLOWED_USE.matcher(line).find();
                    if (uses && !allowed) {
                        offenders.add(CONTROLLERS.relativize(file) + ":" + (i + 1) + "  " + line.strip());
                    }
                }
            }
        }
        assertThat(offenders)
                .as("replies built from exception text (log the detail and answer with a fixed sentence)")
                .isEmpty();
    }

    /** True when the line is a logger call or a continuation line of one. */
    private static boolean isLogStatement(List<String> lines, int index) {
        for (int i = index; i >= 0 && i >= index - 4; i--) {
            String line = lines.get(i);
            // An earlier line that ends a statement is a different statement: stop there.
            if (i != index && (line.strip().endsWith(";") || line.strip().endsWith("{"))) {
                return false;
            }
            if (LOG_CALL.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }
}
