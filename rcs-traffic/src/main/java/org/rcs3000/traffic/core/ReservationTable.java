package org.rcs3000.traffic.core;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Who holds which resource on one map. Not thread-safe; owned by the traffic controller's thread. */
public final class ReservationTable {

    private final Map<Resource, String> holders = new HashMap<>();
    private final Map<String, Set<Resource>> byVehicle = new HashMap<>();

    /** Reserves all resources for {@code vehicle}, or none if any is held by another vehicle. */
    public boolean tryReserve(String vehicle, Collection<Resource> resources) {
        if (blocker(vehicle, resources).isPresent()) {
            return false;
        }
        resources.forEach(r -> put(vehicle, r));
        return true;
    }

    /** Reserves regardless of other holders; used to adopt what a vehicle is already doing. Returns conflicts. */
    public Set<Resource> forceReserve(String vehicle, Collection<Resource> resources) {
        Set<Resource> conflicts = new HashSet<>();
        for (Resource r : resources) {
            String holder = holders.get(r);
            if (holder != null && !holder.equals(vehicle)) {
                conflicts.add(r);
                byVehicle.get(holder).remove(r);
            }
            put(vehicle, r);
        }
        return conflicts;
    }

    /** The first resource in {@code resources} held by a vehicle other than {@code vehicle}, with its holder. */
    public Optional<Map.Entry<Resource, String>> blocker(String vehicle, Collection<Resource> resources) {
        for (Resource r : resources) {
            String holder = holders.get(r);
            if (holder != null && !holder.equals(vehicle)) {
                return Optional.of(Map.entry(r, holder));
            }
        }
        return Optional.empty();
    }

    /** Releases everything {@code vehicle} holds except {@code keep}. */
    public void retainOnly(String vehicle, Set<Resource> keep) {
        Set<Resource> held = byVehicle.getOrDefault(vehicle, Set.of());
        for (Resource r : Set.copyOf(held)) {
            if (!keep.contains(r)) {
                holders.remove(r);
                held.remove(r);
            }
        }
    }

    public void releaseAll(String vehicle) {
        retainOnly(vehicle, Set.of());
    }

    public Optional<String> holder(Resource resource) {
        return Optional.ofNullable(holders.get(resource));
    }

    public Set<Resource> heldBy(String vehicle) {
        return Set.copyOf(byVehicle.getOrDefault(vehicle, Set.of()));
    }

    public Map<Resource, String> snapshot() {
        return Map.copyOf(holders);
    }

    private void put(String vehicle, Resource r) {
        holders.put(r, vehicle);
        byVehicle.computeIfAbsent(vehicle, k -> new HashSet<>()).add(r);
    }
}
