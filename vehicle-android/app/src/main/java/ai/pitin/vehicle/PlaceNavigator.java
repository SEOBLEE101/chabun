package ai.pitin.vehicle;

/** Asks the car's navigation to go to a {@link RestPlace}. Each build supplies its own (see PlaceNavigatorFactory). */
interface PlaceNavigator extends AutoCloseable {
    interface Callback { void done(boolean ok, String message); }

    /** True when this build can hand a destination to a real navigation. */
    boolean live();

    /** Called on the main thread; {@code callback} is called once, on the main thread. */
    void go(RestPlace place, Callback callback);

    /**
     * Like {@link #go(RestPlace, Callback)}, but {@code stillParked} is asked again on the main thread right before the
     * request goes to the navigation, because connecting can take a while. False ends the request without sending.
     */
    default void go(RestPlace place, java.util.function.BooleanSupplier stillParked, Callback callback) { go(place, callback); }

    @Override void close();
}
