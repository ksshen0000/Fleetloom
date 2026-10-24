package org.rcs3000.traffic.core;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Direction locks of the single-lane corridors of one map (see {@link RoadGraph.Corridor}). Vehicles
 * may follow each other through a corridor, but nobody enters it against them, so two vehicles never
 * meet head-on inside. Not thread-safe; owned by the traffic controller's thread.
 */
public final class CorridorTable {

    private final Map<String, Lock> locks = new HashMap<>();

    /**
     * The vehicle that keeps {@code vehicle} from entering {@code corridor} in {@code direction}, if any.
     *
     * @param waiter one that waits longer to drive the other way: it goes next, so the corridor is left
     *               to drain instead of letting further vehicles in behind those already inside
     */
    public Optional<String> blocker(String corridor, int direction, String vehicle, Optional<String> waiter) {
        Lock lock = locks.get(corridor);
        if (lock == null) {
            return waiter;
        }
        if (lock.holders.contains(vehicle) || lock.direction == direction && waiter.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(lock.holders.iterator().next());
    }

    /** Takes the corridor for {@code vehicle}; only after {@link #blocker} found nothing in the way. */
    public void acquire(String corridor, int direction, String vehicle) {
        locks.computeIfAbsent(corridor, k -> new Lock(direction)).holders.add(vehicle);
    }

    /** Gives back every corridor {@code vehicle} holds except {@code keep}. */
    public void retainOnly(String vehicle, Set<String> keep) {
        locks.entrySet().removeIf(e -> {
            if (!keep.contains(e.getKey())) {
                e.getValue().holders.remove(vehicle);
            }
            return e.getValue().holders.isEmpty();
        });
    }

    public void releaseAll(String vehicle) {
        retainOnly(vehicle, Set.of());
    }

    /** Corridor → the vehicles driving through it. */
    public Map<String, Set<String>> snapshot() {
        Map<String, Set<String>> result = new HashMap<>();
        locks.forEach((corridor, lock) -> result.put(corridor, Set.copyOf(lock.holders)));
        return result;
    }

    private static final class Lock {

        final int direction;
        final Set<String> holders = new LinkedHashSet<>();

        Lock(int direction) {
            this.direction = direction;
        }
    }
}
