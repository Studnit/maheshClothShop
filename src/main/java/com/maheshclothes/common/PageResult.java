package com.maheshclothes.common;

import java.util.List;

public record PageResult<T>(List<T> items, int page, int size, long total) {

    public int totalPages() {
        return size <= 0 ? 1 : (int) Math.max(1, (total + size - 1) / size);
    }

    public boolean hasPrevious() {
        return page > 0;
    }

    public boolean hasNext() {
        return page + 1 < totalPages();
    }

    public long from() {
        return total == 0 ? 0 : (long) page * size + 1;
    }

    public long to() {
        return Math.min(total, (long) (page + 1) * size);
    }
}
