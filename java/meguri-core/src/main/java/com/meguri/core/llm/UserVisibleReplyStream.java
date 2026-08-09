package com.meguri.core.llm;

import java.util.Objects;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Keeps native Provider streaming user-visible when a model ignores the
 * natural-language-only instruction and starts emitting the response JSON.
 */
public final class UserVisibleReplyStream {
    private UserVisibleReplyStream() { }

    public static Flux<String> sanitize(Flux<String> providerChunks) {
        Objects.requireNonNull(providerChunks, "providerChunks");
        return Flux.defer(() -> {
            IncrementalReplyExtractor extractor = new IncrementalReplyExtractor();
            Flux<String> streaming = providerChunks.handle((chunk, sink) -> {
                String visible = extractor.accept(chunk);
                if (!visible.isEmpty()) sink.next(visible);
            });
            return streaming.concatWith(Mono.fromSupplier(extractor::finish)
                    .filter(value -> !value.isEmpty()));
        });
    }

    private enum Mode {
        UNDECIDED,
        PLAIN_TEXT,
        JSON_REPLY,
        COMPLETE
    }

    private static final class IncrementalReplyExtractor {
        private static final String REPLY_KEY = "\"reply\"";
        private final StringBuilder undecided = new StringBuilder();
        private Mode mode = Mode.UNDECIDED;
        private boolean escapePending;
        private boolean unicodePending;
        private final StringBuilder unicodeEscape = new StringBuilder(4);
        private Character pendingHighSurrogate;

        String accept(String chunk) {
            if (chunk == null || chunk.isEmpty() || mode == Mode.COMPLETE) return "";
            if (mode == Mode.PLAIN_TEXT) return chunk;
            if (mode == Mode.JSON_REPLY) return decodeReplyCharacters(chunk);

            undecided.append(chunk);
            Detection detection = detectPrefix(undecided);
            if (detection.kind() == DetectionKind.WAIT) return "";
            if (detection.kind() == DetectionKind.PLAIN_TEXT) {
                mode = Mode.PLAIN_TEXT;
                String visible = undecided.toString();
                undecided.setLength(0);
                return visible;
            }

            mode = Mode.JSON_REPLY;
            String remainder = undecided.substring(detection.valueStart());
            undecided.setLength(0);
            return decodeReplyCharacters(remainder);
        }

        String finish() {
            if (mode == Mode.UNDECIDED) {
                mode = Mode.COMPLETE;
                String visible = undecided.toString();
                undecided.setLength(0);
                return visible;
            }
            if (mode == Mode.JSON_REPLY) {
                mode = Mode.COMPLETE;
                StringBuilder trailing = new StringBuilder();
                if (pendingHighSurrogate != null) trailing.append(pendingHighSurrogate.charValue());
                if (escapePending) trailing.append('\\');
                if (unicodePending) trailing.append("\\u").append(unicodeEscape);
                pendingHighSurrogate = null;
                escapePending = false;
                unicodePending = false;
                unicodeEscape.setLength(0);
                return trailing.toString();
            }
            mode = Mode.COMPLETE;
            return "";
        }

        private String decodeReplyCharacters(String input) {
            StringBuilder visible = new StringBuilder(input.length());
            for (int index = 0; index < input.length() && mode == Mode.JSON_REPLY; index++) {
                char current = input.charAt(index);
                if (unicodePending) {
                    unicodeEscape.append(current);
                    if (unicodeEscape.length() == 4) {
                        appendUnicodeEscape(visible);
                        unicodePending = false;
                    }
                    continue;
                }
                if (escapePending) {
                    escapePending = false;
                    if (current == 'u') {
                        unicodePending = true;
                        unicodeEscape.setLength(0);
                        continue;
                    }
                    appendDecoded(visible, switch (current) {
                        case '"', '\\', '/' -> current;
                        case 'b' -> '\b';
                        case 'f' -> '\f';
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        default -> current;
                    });
                    continue;
                }
                if (current == '\\') {
                    escapePending = true;
                } else if (current == '"') {
                    if (pendingHighSurrogate != null) {
                        visible.append(pendingHighSurrogate.charValue());
                        pendingHighSurrogate = null;
                    }
                    mode = Mode.COMPLETE;
                } else {
                    appendDecoded(visible, current);
                }
            }
            return visible.toString();
        }

        private void appendUnicodeEscape(StringBuilder visible) {
            try {
                char decoded = (char) Integer.parseInt(unicodeEscape.toString(), 16);
                appendDecoded(visible, decoded);
            } catch (NumberFormatException ignored) {
                appendDecoded(visible, '\\');
                visible.append('u').append(unicodeEscape);
            } finally {
                unicodeEscape.setLength(0);
            }
        }

        private void appendDecoded(StringBuilder visible, char decoded) {
            if (pendingHighSurrogate != null) {
                if (Character.isLowSurrogate(decoded)) {
                    visible.append(pendingHighSurrogate.charValue()).append(decoded);
                    pendingHighSurrogate = null;
                    return;
                }
                visible.append(pendingHighSurrogate.charValue());
                pendingHighSurrogate = null;
            }
            if (Character.isHighSurrogate(decoded)) {
                pendingHighSurrogate = decoded;
            } else {
                visible.append(decoded);
            }
        }

        private static Detection detectPrefix(CharSequence value) {
            int index = 0;
            while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
            if (index == value.length()) return Detection.waitForMore();
            if (value.charAt(index) != '{') return Detection.plainText();
            index++;
            while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
            if (index == value.length()) return Detection.waitForMore();

            for (int keyIndex = 0; keyIndex < REPLY_KEY.length(); keyIndex++) {
                if (index + keyIndex >= value.length()) return Detection.waitForMore();
                if (value.charAt(index + keyIndex) != REPLY_KEY.charAt(keyIndex)) {
                    return Detection.plainText();
                }
            }
            index += REPLY_KEY.length();
            while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
            if (index == value.length()) return Detection.waitForMore();
            if (value.charAt(index) != ':') return Detection.plainText();
            index++;
            while (index < value.length() && Character.isWhitespace(value.charAt(index))) index++;
            if (index == value.length()) return Detection.waitForMore();
            if (value.charAt(index) != '"') return Detection.plainText();
            return Detection.jsonReply(index + 1);
        }
    }

    private enum DetectionKind {
        WAIT,
        PLAIN_TEXT,
        JSON_REPLY
    }

    private record Detection(DetectionKind kind, int valueStart) {
        static Detection waitForMore() { return new Detection(DetectionKind.WAIT, -1); }
        static Detection plainText() { return new Detection(DetectionKind.PLAIN_TEXT, -1); }
        static Detection jsonReply(int valueStart) { return new Detection(DetectionKind.JSON_REPLY, valueStart); }
    }
}
