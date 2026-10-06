package br.com.careermatcher.matching;

import br.com.careermatcher.DTOs.matching.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class LatestExecutionStoreTest {
    @Test void exposesLastPublishedResponseOnlyWithinTheCurrentProcess() {
        var store = new LatestExecutionStore();
        assertThat(store.latest(1)).isNull();
        var allocation = new AllocationResponse(List.of(), Set.of(), 0);
        var first = new MatchingRunResponse("RUN-1", Instant.EPOCH, allocation);
        var second = new MatchingRunResponse("RUN-2", Instant.EPOCH.plusSeconds(1), allocation);
        store.publish(1, first);
        store.publish(1, second);
        assertThat(store.latest(1).response()).isSameAs(second);
        assertThat(store.latest(2)).isNull();
        store.publish(2, first);
        assertThat(store.latest(1).response()).isSameAs(second);
        assertThat(store.latest(2).response()).isSameAs(first);
        assertThat(new LatestExecutionStore().latest(1)).isNull();
    }
}
