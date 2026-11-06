package br.com.careermatcher.similarity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Limits apply to a whole computation, across all rounds. */
@Component
@ConfigurationProperties(prefix = "similarity")
@lombok.Getter
@lombok.Setter
public class SimilaritySettings {
    private double alpha = .15, epsilon = .10, failureProbability = .000001;
    private int defaultK = 10, maxK = 100, maxRounds = 32;
    private long maxWalks = 2_000_000, maxMillis = 5_000, seed = 20261031;

    public Parameters parameters() {
        return new Parameters(alpha, epsilon, failureProbability, defaultK, maxK, maxRounds, maxWalks, maxMillis, seed);
    }
    public record Parameters(double alpha, double epsilon, double failureProbability, int defaultK,
                             int maxK, int maxRounds, long maxWalks, long maxMillis, long seed) {
        public Parameters {
            if (!(alpha > 0 && alpha < 1) || !(epsilon > 0 && epsilon < 1)
                    || !(failureProbability > 0 && failureProbability < 1)
                    || defaultK < 1 || maxK < defaultK || maxRounds < 1 || maxRounds > 1024
                    || maxWalks < 1 || maxMillis < 1 || maxMillis > 86_400_000)
                throw new IllegalArgumentException("Invalid similarity parameters");
        }
    }
}
