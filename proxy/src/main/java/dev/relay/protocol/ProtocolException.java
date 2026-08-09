package dev.relay.protocol;

/**
 * Thrown when a peer sends something malformed. Per spec &sect;8 the proxy validates
 * before relaying, so these are always fatal to the offending connection &mdash; a
 * backend should never see input Relay could not itself parse.
 */
public class ProtocolException extends RuntimeException {

    public ProtocolException(String message) {
        super(message, null, false, false);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause, false, false);
    }
}
