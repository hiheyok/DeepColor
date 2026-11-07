package dev.deepcolor.test.mixin;

import java.util.concurrent.Phaser;
import net.fabricmc.fabric.impl.client.gametest.threading.ThreadingImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes a startup deadlock in Fabric's client gametest harness (fabric-client-gametest-api-v1 6.0.12).
 *
 * <p>runTestThread publishes the test thread, then the new thread registers itself with PHASER. If the
 * client thread reaches postRunTasks in between, it is the Phaser's only party: enterPhase(1) advances
 * alone, it sees testThread != null and blocks on CLIENT_SEMAPHORE. The test thread then registers and
 * waits in enterPhase(1) for the client, which waits for the test. Both park forever. It happens when
 * the new thread starts slowly, so it gets more likely the busier the machine is.
 *
 * <p>Registering on the calling thread, before the test thread exists, closes the window. The
 * registration inside the thread becomes a no-op, so there is still exactly one party per test thread
 * and deregisterTestThread stays balanced. Only the test mod carries this, never the shipped jar.
 */
@Mixin(ThreadingImpl.class)
abstract class ThreadingImplMixin {
	@Inject(method = "runTestThread", at = @At("HEAD"))
	private static void deepcolortest$registerBeforeStart(Runnable testRunner, CallbackInfo ci) {
		ThreadingImpl.PHASER.register();
	}

	@Redirect(method = "lambda$runTestThread$0", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/Phaser;register()I"))
	private static int deepcolortest$alreadyRegistered(Phaser phaser) {
		return phaser.getPhase();
	}
}
