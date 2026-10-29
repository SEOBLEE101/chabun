package ai.pitin.vehicle;

/** Foreground observations and explicit unresolved commands are independent. */
final class PresetSyncPolicy {
    long generation, revision = -1;
    String epoch;
    boolean foreground, fresh, unresolved, restarted;
    void start() { foreground = true; fresh = false; generation++; }
    void stop() { foreground = false; fresh = false; generation++; }
    void beginCommand() { unresolved = true; generation++; }
    void failed() { fresh = false; }
    void resolved() { unresolved = false; }
    boolean canWrite() { return foreground && fresh && !unresolved; }
    boolean observe(long responseGeneration, String responseEpoch, long responseRevision) {
        if (!foreground || generation != responseGeneration || responseEpoch.isEmpty() || responseRevision < 0) return false;
        if (responseEpoch.equals(epoch) && responseRevision < revision) return false;
        if (epoch != null && !epoch.equals(responseEpoch)) restarted = true;
        epoch = responseEpoch; revision = responseRevision; fresh = true; return true;
    }
    static boolean validComfort(String value) {
        return "cool_down".equals(value) || "quiet_rest".equals(value) || "gentle_refresh".equals(value);
    }
    /**
     * Debug/review test hook ({@code --es reviewRelayOrigin}): only the local HTTPS relay
     * {@code https://<127.0.0.1|localhost|10.0.2.2>:9443} is accepted (preset sync then derives
     * {@code https://<host>:9447}). Returns the normalised origin, or null when refused.
     */
    static String reviewRelayOrigin(String value) {
        if (value == null || value.length() > 64) return null;
        try {
            java.net.URI uri = new java.net.URI(value.trim());
            String host = uri.getHost(), path = uri.getRawPath();
            if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() != 9443
                    || (path != null && !path.isEmpty() && !"/".equals(path))) return null;
            if (!"127.0.0.1".equals(host) && !"localhost".equals(host) && !"10.0.2.2".equals(host)) return null;
            return "https://" + host + ":9443";
        } catch (Exception invalid) { return null; }
    }
}
