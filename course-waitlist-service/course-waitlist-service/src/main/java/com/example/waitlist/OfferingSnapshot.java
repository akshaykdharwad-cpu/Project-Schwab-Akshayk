package com.example.waitlist;

import java.util.List;

/** Immutable, consistent point-in-time view of one offering's seats and waitlist. */
public record OfferingSnapshot(String offeringId, int capacity, List<String> enrolled, List<String> waitlist) {

    public OfferingSnapshot {
        enrolled = List.copyOf(enrolled);
        waitlist = List.copyOf(waitlist);
    }

    public int seatsAvailable() {
        return capacity - enrolled.size();
    }
}
