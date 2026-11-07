package dev.deepcolor.core;

import dev.deepcolor.DeepColor;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records injectors that failed to land. All injectors use require = 0 so that a mismatch cannot
 * crash the game, which means Mixin itself stays silent. DeepColorMixinPlugin inspects each
 * transformed class and reports here, and features that depend on a hook check it before enabling.
 */
public final class Hooks {
	public static final String ROUTE_PIPELINE = "deepcolor$routePipeline";
	public static final String TAKE_SCREENSHOT = "deepcolor$takeScreenshot";
	public static final String COMPILE_PIPELINE = "deepcolor$compilePipeline";

	private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

	private Hooks() {
	}

	public static void failed(String handler, String target) {
		FAILED.add(handler);
		DeepColor.LOGGER.warn("Injection {} did not apply to {}. The feature using it is inactive.", handler, target);
	}

	public static boolean hasFailed(String handler) {
		return FAILED.contains(handler);
	}
}
