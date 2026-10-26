package watch.nabu.infrastructure.redis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskProducerTest {

    @Test
    void medianOf_singleElement_returnsThatElement() {
        assertEquals(42, TaskProducer.medianOf(List.of(42)));
    }

    @Test
    void medianOf_oddSize_returnsMiddleAfterSort() {
        // unsorted input exercises the internal sort
        assertEquals(50, TaskProducer.medianOf(List.of(10, 100, 50, 30, 80)));
    }

    @Test
    void medianOf_evenSize_returnsUpperMiddle() {
        // 4 elements sorted → [10, 30, 50, 80]; size/2 = 2 → 50
        assertEquals(50, TaskProducer.medianOf(List.of(10, 80, 50, 30)));
    }

    @Test
    void medianOf_outlierDoesNotSkew() {
        // 3 legit values ~50 + 1 outlier 2 (WAF shell). Median stays at 50,
        // proving the robustness property TaskProducer relies on.
        assertEquals(50, TaskProducer.medianOf(List.of(48, 50, 52, 2)));
    }
}
