package ai.pitin.vehicle;

public interface VehicleEnvironment extends AutoCloseable {
    SafetyGate.Snapshot snapshot(long nowMs);
    void refresh();
    String description();
    default String[] requiredPermissions() { return new String[0]; }
    /** Changes seat heat/ventilation, climate and ambient light; null in a build that never touches the cabin. */
    default CabinActuator cabin() { return null; }
    /** The car's set temperature in on-screen words, for a build without a cabin actuator; null when not read. */
    default String climateNow() { return null; }
    default void simulate(String scenario) {
        throw new UnsupportedOperationException("This environment has no simulation controls.");
    }
    @Override void close();
}
