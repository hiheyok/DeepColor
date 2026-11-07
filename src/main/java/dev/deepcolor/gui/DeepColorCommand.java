package dev.deepcolor.gui;

import com.mojang.brigadier.CommandDispatcher;
import dev.deepcolor.core.OutputState;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class DeepColorCommand {
	private DeepColorCommand() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommands.literal("deepcolor")
			.then(ClientCommands.literal("gradient").executes(context -> {
				Minecraft minecraft = context.getSource().getClient();
				// Queued, because the chat screen closes itself right after running a command.
				minecraft.execute(() -> minecraft.gui.setScreen(new GradientScreen(null)));
				return 1;
			}))
			.then(ClientCommands.literal("status").executes(context -> {
				context.getSource().sendFeedback(Component.literal(OutputState.statusLine()));
				return 1;
			})));
	}
}
