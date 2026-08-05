package io.career262.fleet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RuleEngineTest {
    private final RuleEngine engine = new RuleEngine();
    private final RuleEngine.Profile profile = new RuleEngine.Profile(25, 10, 5);

    @Test void positionJumpIsCritical() {
        var previous = new RuleEngine.Packet("r", 1, 0, 0, 80, "moving", List.of(), "standard");
        var current = new RuleEngine.Packet("r", 2, 20, 20, 79, "moving", List.of(), "standard");
        var result = engine.evaluate(current, previous, profile);
        assertEquals("critical", result.severity());
        assertTrue(result.rules().contains("position_jump"));
    }

    @Test void unknownErrorIsCritical() {
        var current = new RuleEngine.Packet("r", 2, 0, 0, 79, "moving", List.of("E_UNKNOWN"), "standard");
        assertEquals("critical", engine.evaluate(current, null, profile).severity());
    }

    @Test void chargingLossWarns() {
        var previous = new RuleEngine.Packet("r", 1, 0, 0, 80, "charging", List.of(), "standard");
        var current = new RuleEngine.Packet("r", 2, 0, 0, 79, "charging", List.of(), "standard");
        assertTrue(engine.evaluate(current, previous, profile).rules().contains("charging_without_gain"));
    }
}
