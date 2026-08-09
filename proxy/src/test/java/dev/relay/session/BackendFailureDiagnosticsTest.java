package dev.relay.session;

import dev.relay.proxy.ConnectionResult;
import dev.relay.proxy.RegisteredServer;
import dev.relay.config.RelayConfig.ServerEntry;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the diagnostics on a failed backend connection.
 *
 * <p>These exist because a real failure was reported as nothing more than "could not
 * reach lobby" while a backend was demonstrably running on that port. The message a
 * player sees can stay short, but the cause must survive as far as the log &mdash;
 * discarding it turned three unrelated faults into one indistinguishable symptom.
 */
class BackendFailureDiagnosticsTest {

    private static final RegisteredServer LOBBY = new RegisteredServer(
            new ServerEntry("lobby", InetSocketAddress.createUnresolved("127.0.0.1", 25566)));

    @Test
    void unreachableCarriesItsCause() {
        Throwable cause = new IllegalStateException("Backend lobby hung up part-way through login");
        ConnectionResult result = new ConnectionResult.Unreachable(LOBBY, cause);

        assertFalse(result.successful());
        assertTrue(result instanceof ConnectionResult.Unreachable unreachable
                && unreachable.cause() == cause, "the cause must be retained, not flattened to a message");
    }

    /** Wrapped causes are the norm, and the useful message is usually not the outermost. */
    @Test
    void causeChainIsFlattenedForLogging() {
        Throwable root = new java.net.ConnectException("Connection refused: no further information");
        Throwable wrapped = new IllegalStateException("Backend lobby unreachable", root);

        String described = BackendConnector.describeCauseForTest(wrapped);

        assertTrue(described.contains("Backend lobby unreachable"), described);
        assertTrue(described.contains("Connection refused"), described);
        assertTrue(described.contains("ConnectException"), described);
    }

    @Test
    void selfReferencingCauseDoesNotLoopForever() {
        Exception looping = new Exception("looping") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertTrue(BackendConnector.describeCauseForTest(looping).contains("looping"));
    }

    @Test
    void causeWithoutAMessageStillNamesItsType() {
        assertTrue(BackendConnector.describeCauseForTest(new java.nio.channels.ClosedChannelException())
                .contains("ClosedChannelException"));
    }

    @Test
    void rejectionKeepsTheBackendsOwnReason() {
        Component reason = Component.text("Outdated client! Please use 1.21.4");
        ConnectionResult result = new ConnectionResult.Rejected(LOBBY, reason);

        assertFalse(result.successful());
        assertTrue(BackendConnector.plain(((ConnectionResult.Rejected) result).reason())
                .contains("Outdated client"));
    }
}
