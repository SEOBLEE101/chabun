package ai.pitin.vehicle;

/** Asks the car's navigation to go to a {@link RestPlace}. Each build supplies its own (see PlaceNavigatorFactory). */
interface PlaceNavigator extends AutoCloseable {
    interface Callback { void done(boolean ok, String message); }

    /** True when this build can hand a destination to a real navigation. */
    boolean live();

    /** Called on the main thread; {@code callback} is called once, on the main thread. */
    void go(RestPlace place, Callback callback);

    @Override void close();
}
