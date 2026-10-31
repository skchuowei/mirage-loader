package io.mirage.mgscript.runtime;

/** {@code 0..10}（不含 10）或 {@code 0..=10}（含 10）。惰性求值会更好，但 P0 直接展开成元素。 */
public record RangeValue(double from, double to, boolean inclusiveEnd) {

    public int size() {
        double end = inclusiveEnd ? to + 1 : to;
        return Math.max(0, (int) Math.ceil(end - from));
    }
}
