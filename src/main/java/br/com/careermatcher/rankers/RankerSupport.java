package br.com.careermatcher.rankers;

import java.util.List;
import java.util.Locale;

final class RankerSupport {

    private RankerSupport() {
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    static double candidateWeight(Double weight) {
        return isValidNumber(weight) && weight > 0.0 ? weight : 0.0;
    }

    static double requirementWeight(Double weight) {
        return isValidNumber(weight) && weight > 0.0 ? weight : 1.0;
    }

    static int nonNegativeMonths(Integer months) {
        return months == null ? 0 : Math.max(0, months);
    }

    static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static boolean isValidNumber(Double value) {
        return value != null && Double.isFinite(value);
    }
}
