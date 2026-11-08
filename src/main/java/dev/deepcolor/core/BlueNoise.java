package dev.deepcolor.core;

import java.util.Random;

/**
 * Generates a tileable blue noise threshold map with Ulichney's void-and-cluster method (1993).
 *
 * <p>Blue noise keeps its energy at high spatial frequencies, which the eye blurs away. Modelled on a
 * dark ramp it shows about a third less visible grain than interleaved gradient noise at the same
 * amplitude. It is generated at startup instead of shipped, so the mod contains no opaque binary.
 */
final class BlueNoise {
	static final int SIZE = 64;
	private static final double SIGMA = 1.5;
	private static final int RADIUS = 6;
	/** Every pixel moved ten times over; the real relaxation needs a small fraction of that. */
	private static final int MAX_RELAXATION_MOVES = SIZE * SIZE * 10;

	private final double[] energy = new double[SIZE * SIZE];
	private final boolean[] set = new boolean[SIZE * SIZE];
	private final double[] kernel = new double[(2 * RADIUS + 1) * (2 * RADIUS + 1)];

	private BlueNoise() {
		for (int dy = -RADIUS; dy <= RADIUS; dy++) {
			for (int dx = -RADIUS; dx <= RADIUS; dx++) {
				this.kernel[(dy + RADIUS) * (2 * RADIUS + 1) + dx + RADIUS] = Math.exp(-(dx * dx + dy * dy) / (2.0 * SIGMA * SIGMA));
			}
		}
	}

	/** Rank of every pixel, 0 to SIZE * SIZE - 1, in row-major order. Deterministic. */
	static int[] generateRanks() {
		return new BlueNoise().run();
	}

	private int[] run() {
		Random random = new Random(0x0DEE9C01L);
		int initial = SIZE * SIZE / 10;
		for (int placed = 0; placed < initial; ) {
			int index = random.nextInt(SIZE * SIZE);
			if (!this.set[index]) {
				this.toggle(index, true);
				placed++;
			}
		}
		// Relax the random start: move the tightest cluster into the largest void until stable. That
		// takes 158 moves for this size and seed. A cap keeps other sizes, sigmas or seeds
		// that could swap between two states forever from hanging the game; the points are well
		// spread long before it.
		for (int move = 0; move < MAX_RELAXATION_MOVES; move++) {
			int cluster = this.extreme(true);
			this.toggle(cluster, false);
			int voidIndex = this.extreme(false);
			if (voidIndex == cluster) {
				this.toggle(cluster, true);
				break;
			}
			this.toggle(voidIndex, true);
		}

		int[] rank = new int[SIZE * SIZE];
		boolean[] startSet = this.set.clone();
		double[] startEnergy = this.energy.clone();
		// Rank the start points by removing clusters, then rank the rest by filling voids.
		for (int count = initial - 1; count >= 0; count--) {
			int cluster = this.extreme(true);
			this.toggle(cluster, false);
			rank[cluster] = count;
		}
		System.arraycopy(startSet, 0, this.set, 0, startSet.length);
		System.arraycopy(startEnergy, 0, this.energy, 0, startEnergy.length);
		for (int count = initial; count < SIZE * SIZE; count++) {
			int voidIndex = this.extreme(false);
			this.toggle(voidIndex, true);
			rank[voidIndex] = count;
		}
		return rank;
	}

	/** Highest energy among set pixels (tightest cluster), or lowest among unset (largest void). */
	private int extreme(boolean cluster) {
		int best = -1;
		for (int i = 0; i < this.energy.length; i++) {
			if (this.set[i] == cluster && (best < 0 || (cluster ? this.energy[i] > this.energy[best] : this.energy[i] < this.energy[best]))) {
				best = i;
			}
		}
		return best;
	}

	private void toggle(int index, boolean value) {
		this.set[index] = value;
		double sign = value ? 1.0 : -1.0;
		int x = index % SIZE;
		int y = index / SIZE;
		for (int dy = -RADIUS; dy <= RADIUS; dy++) {
			int row = Math.floorMod(y + dy, SIZE) * SIZE;
			for (int dx = -RADIUS; dx <= RADIUS; dx++) {
				this.energy[row + Math.floorMod(x + dx, SIZE)] += sign * this.kernel[(dy + RADIUS) * (2 * RADIUS + 1) + dx + RADIUS];
			}
		}
	}
}
