package co.edu.corhuila.telemedia.documentgeneration.application.port.in;

import java.util.List;
import java.util.Objects;

/** One page of results with the metadata of {@code {data, meta}} (D-C5). */
public record Page<T>(List<T> items, int page, int limit, long total) {

    public Page {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
        if (items.size() > limit) {
            throw new IllegalArgumentException("a page cannot hold more items than its limit");
        }
    }

    public static <T> Page<T> of(List<T> items, PageRequest request, long total) {
        return new Page<>(items, request.page(), request.limit(), total);
    }

    /** {@code 0} when there are no results, as the contract requires. */
    public long totalPages() {
        return total == 0 ? 0 : (total + limit - 1) / limit;
    }
}
