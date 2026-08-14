package org.matsim.contrib.freightcollaboration.learning;

/** Diagnostics calculated over the current visit's bounded sliding execution window. */
public record WindowStatistics(
	int size,
	double carrierMin,
	double carrierMax,
	double carrierRelativeRange,
	double receiverMin,
	double receiverMax,
	double receiverRelativeRange,
	double surplusMean,
	double surplusMin,
	double surplusMax,
	double surplusVariance,
	double coalitionSimilarity,
	boolean receiverParticipationFeasible
) {
	public static WindowStatistics empty() {
		return new WindowStatistics(0, Double.NaN, Double.NaN, Double.NaN,
			Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
			Double.NaN, Double.NaN, Double.NaN, false);
	}

	public boolean satisfies(int requiredSize, double tolerance,
			double coalitionStabilityThreshold) {
		return size == requiredSize && receiverParticipationFeasible
			&& coalitionSimilarity >= coalitionStabilityThreshold
			&& carrierRelativeRange <= tolerance && receiverRelativeRange <= tolerance;
	}
}
