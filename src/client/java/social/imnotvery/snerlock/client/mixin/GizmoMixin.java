package social.imnotvery.snerlock.client.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import social.imnotvery.snerlock.client.SnerlockClient;

@Mixin(LevelRenderer.class)
public class GizmoMixin {

	@Inject(
			method = "submitFeatures(Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;Z)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/LevelRenderer;finalizeGizmoCollection()V",
					shift = At.Shift.BEFORE
			)
	)
	private void onFinalizeGizmoCollection(
			LevelRenderState levelRenderState,
			SubmitNodeCollector submitNodeCollector,
			boolean renderBlockOutline,
			CallbackInfo ci
	) {
		SnerlockClient.getCoreProtectVisualizer().renderFromLevelHook();
	}
}
