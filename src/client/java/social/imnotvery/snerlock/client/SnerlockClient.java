package social.imnotvery.snerlock.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import social.imnotvery.snerlock.client.coreprotect.CoreProtectVisualizer;

public class SnerlockClient implements ClientModInitializer {

	private static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(
			Identifier.fromNamespaceAndPath("snerlock", "general")
	);
	private static final CoreProtectVisualizer CORE_PROTECT_VISUALIZER = new CoreProtectVisualizer();

	private static final KeyMapping TOGGLE_RECORDING_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.snerlock.toggle_coreprotect_capture",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_F8,
			KEY_CATEGORY
	));

	private static final KeyMapping CLEAR_RECORDING_KEY = KeyMappingHelper.registerKeyMapping(new KeyMapping(
			"key.snerlock.clear_coreprotect_capture",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_F7,
			KEY_CATEGORY
	));

	@Override
	public void onInitializeClient() {

		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
				CORE_PROTECT_VISUALIZER.handleMessage(message));

		ClientReceiveMessageEvents.GAME.register(CORE_PROTECT_VISUALIZER::handleGameMessage);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (TOGGLE_RECORDING_KEY.consumeClick()) {
				CORE_PROTECT_VISUALIZER.toggleRecording(client);
			}

			while (CLEAR_RECORDING_KEY.consumeClick()) {
				CORE_PROTECT_VISUALIZER.clear(client);
			}
		});
	}

	public static CoreProtectVisualizer getCoreProtectVisualizer() {
		return CORE_PROTECT_VISUALIZER;
	}
}
