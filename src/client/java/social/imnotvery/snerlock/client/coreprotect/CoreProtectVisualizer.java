package social.imnotvery.snerlock.client.coreprotect;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CoreProtectVisualizer {

	private static final Pattern LEGACY_FORMATTING_PATTERN = Pattern.compile("(?i)§[0-9A-FK-OR]");
	private static final Pattern ACTION_LINE_PATTERN = Pattern.compile(
			"^(?:.+? ago )?- (\\.?[A-Za-z0-9_]{1,16}) (broke|placed) ([a-z0-9_:]+)\\.$"
	);
	private static final Pattern COORDINATE_LINE_PATTERN = Pattern.compile(
			"^\\^ \\(x(-?\\d+)/y(-?\\d+)/z(-?\\d+)/([^)]*)\\)$"
	);

	private static final int MAX_STORED_ACTIONS = 2048;
	private static final int MAX_RENDERED_ACTIONS = 512;
	private static final long PENDING_ACTION_TTL_MILLIS = 5_000L;
	private static final double FILL_BOX_INSET = 0.08D;
	private static final double CONNECTOR_Y_OFFSET = 0.56D;
	private static final double SAME_BLOCK_ARROW_HEIGHT = 0.72D;
	private static final double ARROW_MIN_LENGTH = 0.38D;
	private static final double ARROW_MAX_LENGTH = 0.90D;
	private static final float FILL_ALPHA = 0.5F;
	private static final float OUTLINE_ALPHA = 0.95F;
	private static final float CONNECTOR_ALPHA = 0.72F;
	private static final float ARROW_ALPHA = 0.92F;
	private static final float OUTLINE_WIDTH = 1.5F;
	private static final float CONNECTOR_WIDTH = 2.4F;
	private static final float ARROW_WIDTH = 3.2F;
	private static final int BROKE_OUTLINE_RGB = 0xFF6B6B;
	private static final int PLACED_OUTLINE_RGB = 0x66E38C;
	private static final int CONNECTOR_LINE_RGB = 0x8FE7FF;
	private static final int CONNECTOR_ARROW_RGB = 0xE8FBFF;
	private static final int DEEPSLATE_RGB = 0x4E5258;
	private static final int NETHERRACK_RGB = 0x8A5643;

	private final Deque<PendingAction> pendingActions = new ArrayDeque<>();
	private final List<RecordedAction> recordedActions = new ArrayList<>();

	private boolean recordingEnabled;
	private long nextSequence;

	public void handleMessage(Component message) {
		if (!this.recordingEnabled) {
			return;
		}

		this.discardExpiredPending();
		this.handlePlainMessage(normalizeForMatching(message.getString()));
	}

	public void handleGameMessage(Component message, boolean overlay) {
		if (overlay) {
			return;
		}

		this.handleMessage(message);
	}

	private void handlePlainMessage(String rawMessage) {
		Matcher actionMatcher = ACTION_LINE_PATTERN.matcher(rawMessage);

		if (actionMatcher.matches()) {
			this.pendingActions.addLast(new PendingAction(
					actionMatcher.group(1),
					ActionType.fromVerb(actionMatcher.group(2)),
					actionMatcher.group(3),
					System.currentTimeMillis()
			));
			return;
		}

		Matcher coordinateMatcher = COORDINATE_LINE_PATTERN.matcher(rawMessage);

		if (!coordinateMatcher.matches()) {
			return;
		}

		PendingAction pendingAction = this.pendingActions.pollFirst();

		if (pendingAction == null) {
			return;
		}

		BlockPos blockPos = new BlockPos(
				Integer.parseInt(coordinateMatcher.group(1)),
				Integer.parseInt(coordinateMatcher.group(2)),
				Integer.parseInt(coordinateMatcher.group(3))
		);
		String worldName = coordinateMatcher.group(4);
		VoxelShape shape = resolveShape(pendingAction.blockId());

		this.recordedActions.add(new RecordedAction(
				this.nextSequence++,
				pendingAction.playerName(),
				pendingAction.actionType(),
				pendingAction.blockId(),
				worldName,
				blockPos,
				shape.isEmpty() ? Shapes.block() : shape
		));

		if (this.recordedActions.size() > MAX_STORED_ACTIONS) {
			this.recordedActions.removeFirst();
		}
	}

	public void toggleRecording(Minecraft client) {
		this.recordingEnabled = !this.recordingEnabled;
		this.pendingActions.clear();
		this.sendStatusMessage(client, this.recordingEnabled
				? "Capture enabled"
				: "Capture disabled");
	}

	public void clear(Minecraft client) {
		this.pendingActions.clear();
		this.recordedActions.clear();
		this.sendStatusMessage(client, "Capture cleared");
	}

	public void renderFromLevelHook() {
		this.renderRecordedActions(Minecraft.getInstance());
	}

	private void renderRecordedActions(Minecraft client) {
		if (this.recordedActions.isEmpty()) {
			return;
		}

		ClientLevel level = client.level;

		if (level == null) {
			return;
		}

		int firstVisibleIndex = Math.max(0, this.recordedActions.size() - MAX_RENDERED_ACTIONS);
		List<RecordedAction> visibleActions = new ArrayList<>(this.recordedActions.subList(firstVisibleIndex, this.recordedActions.size()));

        for (RecordedAction visibleAction : visibleActions) {
            this.renderActionFill(visibleAction);
        }

		for (int i = 1; i < visibleActions.size(); i++) {
			this.renderConnector(visibleActions.get(i - 1), visibleActions.get(i));
		}

        for (RecordedAction visibleAction : visibleActions) {
            this.renderActionOutline(visibleAction);
        }
	}

	private void renderActionFill(RecordedAction action) {
		Integer oreRgb = resolveOreRgb(action.blockId());

		if (oreRgb == null) {
			return;
		}

		int alpha = Math.round(FILL_ALPHA * 255.0F);
		Gizmos.cuboid(
				insetBlockBounds(action.position()),
				GizmoStyle.fill(withAlpha(oreRgb, alpha))
		).setAlwaysOnTop();
	}

	private void renderActionOutline(RecordedAction action) {
		int alpha = Math.round(OUTLINE_ALPHA * 255.0F);
		int outlineRgb = action.actionType() == ActionType.BROKE ? BROKE_OUTLINE_RGB : PLACED_OUTLINE_RGB;
		Gizmos.cuboid(
				new AABB(action.position()),
				GizmoStyle.stroke(withAlpha(outlineRgb, alpha), OUTLINE_WIDTH)
		).setAlwaysOnTop();
	}

	private void renderConnector(RecordedAction newerAction, RecordedAction olderAction) {
		Vec3 start = connectorAnchor(olderAction.position());
		Vec3 end = connectorAnchor(newerAction.position());
		Vec3 delta = end.subtract(start);
		int lineColor = withAlpha(
				CONNECTOR_LINE_RGB,
				Math.round(CONNECTOR_ALPHA * 255.0F)
		);
		int arrowColor = withAlpha(
				CONNECTOR_ARROW_RGB,
				Math.round(ARROW_ALPHA * 255.0F)
		);

		if (delta.lengthSqr() < 1.0E-4D) {
			Vec3 verticalEnd = end.add(0.0D, SAME_BLOCK_ARROW_HEIGHT, 0.0D);
			Vec3 arrowStart = end.add(0.0D, SAME_BLOCK_ARROW_HEIGHT * 0.45D, 0.0D);
			Gizmos.line(end, verticalEnd, lineColor, CONNECTOR_WIDTH).setAlwaysOnTop();
			Gizmos.arrow(arrowStart, verticalEnd, arrowColor, ARROW_WIDTH).setAlwaysOnTop();
			return;
		}

		double arrowLength = Math.clamp(delta.length() * 0.38D, ARROW_MIN_LENGTH, ARROW_MAX_LENGTH);
		Vec3 arrowStart = end.subtract(delta.normalize().scale(arrowLength));

		Gizmos.line(start, end, lineColor, CONNECTOR_WIDTH).setAlwaysOnTop();
		Gizmos.arrow(arrowStart, end, arrowColor, ARROW_WIDTH).setAlwaysOnTop();
	}

	private void discardExpiredPending() {
		long cutoff = System.currentTimeMillis() - PENDING_ACTION_TTL_MILLIS;

		while (!this.pendingActions.isEmpty() && this.pendingActions.peekFirst().createdAtMillis() < cutoff) {
			this.pendingActions.removeFirst();
		}
	}

	private void sendStatusMessage(Minecraft client, String message) {
		if (client.player != null) {
			client.player.sendOverlayMessage(Component.literal("[Snerlock] " + message));
		}
	}

	private static VoxelShape resolveShape(String blockId) {
		Identifier identifier = parseBlockIdentifier(blockId);

		if (identifier == null || !BuiltInRegistries.BLOCK.containsKey(identifier)) {
			return Shapes.block();
		}

		VoxelShape shape = BuiltInRegistries.BLOCK
				.getValue(identifier)
				.defaultBlockState()
				.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());

		return shape.isEmpty() ? Shapes.block() : shape;
	}

	private static Identifier parseBlockIdentifier(String rawBlockId) {
		return rawBlockId.indexOf(':') >= 0
				? Identifier.tryParse(rawBlockId)
				: Identifier.tryBuild(Identifier.DEFAULT_NAMESPACE, rawBlockId);
	}

	private static String normalizeForMatching(String value) {
		String normalized = LEGACY_FORMATTING_PATTERN.matcher(value).replaceAll("");
		normalized = normalized
				.replace('\u00A0', ' ')
				.replace('\u2007', ' ')
				.replace('\u202F', ' ')
				.replace("\u200B", "")
				.replace("\u200C", "")
				.replace("\u200D", "")
				.replace("\uFEFF", "");

		return normalized.trim();
	}

	private static Vec3 connectorAnchor(BlockPos position) {
		return new Vec3(
				position.getX() + 0.5D,
				position.getY() + CONNECTOR_Y_OFFSET,
				position.getZ() + 0.5D
		);
	}

	private static AABB insetBlockBounds(BlockPos position) {
		return new AABB(
				position.getX() + CoreProtectVisualizer.FILL_BOX_INSET,
				position.getY() + CoreProtectVisualizer.FILL_BOX_INSET,
				position.getZ() + CoreProtectVisualizer.FILL_BOX_INSET,
				position.getX() + 1.0D - CoreProtectVisualizer.FILL_BOX_INSET,
				position.getY() + 1.0D - CoreProtectVisualizer.FILL_BOX_INSET,
				position.getZ() + 1.0D - CoreProtectVisualizer.FILL_BOX_INSET
		);
	}

	private static Integer resolveOreRgb(String blockId) {
		Identifier identifier = parseBlockIdentifier(blockId);

		if (identifier == null) {
			return null;
		}

		String path = identifier.getPath();
		int rgb = switch (path) {
			case "coal_ore", "deepslate_coal_ore" -> 0x7C8793;
			case "iron_ore", "deepslate_iron_ore" -> 0xC18A69;
			case "copper_ore", "deepslate_copper_ore" -> 0xD17A46;
			case "gold_ore", "deepslate_gold_ore", "nether_gold_ore" -> 0xE0C14A;
			case "redstone_ore", "deepslate_redstone_ore" -> 0xD94A42;
			case "lapis_ore", "deepslate_lapis_ore" -> 0x4E7FE8;
			case "diamond_ore", "deepslate_diamond_ore" -> 0x54DDD8;
			case "emerald_ore", "deepslate_emerald_ore" -> 0x45D978;
			case "nether_quartz_ore" -> 0xE8E2D9;
			case "ancient_debris" -> 0xA06750;
			default -> -1;
		};

		if (rgb < 0) {
			return null;
		}

		if (path.startsWith("deepslate_")) {
			return mixRgb(rgb, DEEPSLATE_RGB, 0.20F);
		}

		if (path.startsWith("nether_") || "ancient_debris".equals(path)) {
			return mixRgb(rgb, NETHERRACK_RGB, 0.14F);
		}

		return rgb;
	}

	private static int mixRgb(int fromRgb, int toRgb, float delta) {
		int red = Math.round(lerp((fromRgb >> 16) & 255, (toRgb >> 16) & 255, delta));
		int green = Math.round(lerp((fromRgb >> 8) & 255, (toRgb >> 8) & 255, delta));
		int blue = Math.round(lerp(fromRgb & 255, toRgb & 255, delta));
		return (red << 16) | (green << 8) | blue;
	}

	private static int withAlpha(int rgb, int alpha) {
		return ARGB.color(alpha, (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);
	}

	private static float lerp(float start, float end, float delta) {
		return start + (end - start) * delta;
	}

	private enum ActionType {
		BROKE("broke"),
		PLACED("placed");

        ActionType(String label) {
        }

		static ActionType fromVerb(String verb) {
			return "placed".equals(verb) ? PLACED : BROKE;
		}
	}

	private record PendingAction(String playerName, ActionType actionType, String blockId, long createdAtMillis) {
	}

	private record RecordedAction(
			long sequence,
			String playerName,
			ActionType actionType,
			String blockId,
			String worldName,
			BlockPos position,
			VoxelShape shape
	) {
	}
}
