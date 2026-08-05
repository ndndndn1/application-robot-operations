package io.career262.fleet;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class RuleEngine {
    public record Profile(double batteryWarning, double batteryCritical, double maxJumpMeters) {}
    public record Packet(String robotId, double timestamp, double x, double y, double battery,
                         String taskState, List<String> errorCodes, String modelId) {}
    public record Result(String severity, List<String> rules, Double deltaMeters, Double deltaBattery) {}

    private static final Set<String> KNOWN_ERRORS = Set.of(
            "E_LIDAR_TIMEOUT", "E_MOTOR_OVERHEAT", "E_PATH_BLOCKED", "E_ESTOP", "E_LOCALIZATION_LOST");

    public Result evaluate(Packet current, Packet previous, Profile profile) {
        List<String> rules = new ArrayList<>();
        int level = 0;
        Double distance = null;
        Double batteryDelta = null;
        if (current.battery() <= profile.batteryCritical()) { rules.add("battery_critical"); level = 2; }
        else if (current.battery() <= profile.batteryWarning()) { rules.add("battery_low"); level = 1; }
        if (previous != null) {
            distance = Math.hypot(current.x() - previous.x(), current.y() - previous.y());
            batteryDelta = current.battery() - previous.battery();
            if (distance > profile.maxJumpMeters()) { rules.add("position_jump"); level = 2; }
            if (batteryDelta < -5.0) { rules.add("battery_drop_spike"); level = Math.max(level, 1); }
            if (current.timestamp() < previous.timestamp()) { rules.add("out_of_order_timestamp"); level = Math.max(level, 1); }
            if (current.taskState().equals("idle") && distance > 0.5) { rules.add("idle_but_moving"); level = Math.max(level, 1); }
            if (current.taskState().equals("charging") && batteryDelta < 0) { rules.add("charging_without_gain"); level = Math.max(level, 1); }
        }
        for (String error : current.errorCodes()) {
            if (KNOWN_ERRORS.contains(error)) { rules.add("known_error:" + error); level = Math.max(level, 1); }
            else { rules.add("unknown_error:" + error); level = 2; }
        }
        return new Result(level == 2 ? "critical" : level == 1 ? "warn" : "normal", rules, distance, batteryDelta);
    }
}
