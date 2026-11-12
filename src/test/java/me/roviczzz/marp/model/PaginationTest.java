package me.roviczzz.marp.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PaginationTest {

    @Test
    void emptyListStillHasPageOne() {
        Page page = Pagination.page(0, 0, 6);
        assertEquals(0, page.index());
        assertEquals(1, page.number());
        assertEquals(1, page.pages());
        assertFalse(page.hasPrevious());
        assertFalse(page.hasNext());
        assertTrue(page.slice(List.of()).isEmpty());
    }

    @Test
    void singleEntryAndFullPageStayOnOnePage() {
        Page one = Pagination.page(0, 1, 6);
        assertEquals(1, one.pages());
        assertFalse(one.hasNext());

        Page full = Pagination.page(0, 45, 6);
        assertEquals(1, full.pages());
        assertFalse(full.hasNext());
    }

    @Test
    void oneOverPageSizeAddsASecondPage() {
        List<Integer> entries = IntStream.range(0, 46).boxed().toList();
        Page first = Pagination.page(0, entries.size(), 6);
        assertEquals(2, first.pages());
        assertTrue(first.hasNext());
        assertEquals(45, first.slice(entries).size());

        Page second = Pagination.page(1, entries.size(), 6);
        assertTrue(second.hasPrevious());
        assertFalse(second.hasNext());
        assertEquals(List.of(45), second.slice(entries));
    }

    @Test
    void deletingTheLastEntryOnAPageStepsBack() {
        assertEquals(0, Pagination.pageAfterDelete(1, 45, 6));
        assertEquals(1, Pagination.pageAfterDelete(1, 46, 6));
        assertEquals(0, Pagination.pageAfterDelete(0, 0, 6));
    }

    @Test
    void outOfRangePageIsClamped() {
        assertEquals(0, Pagination.page(-4, 10, 6).index());
        assertEquals(1, Pagination.page(99, 46, 6).index());
    }
}
