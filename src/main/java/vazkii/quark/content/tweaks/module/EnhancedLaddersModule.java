package vazkii.quark.content.tweaks.module;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import vazkii.quark.base.Quark;
import vazkii.quark.base.module.LoadModule;
import vazkii.quark.base.module.ModuleCategory;
import vazkii.quark.base.module.QuarkModule;
import vazkii.quark.base.module.config.Config;

@LoadModule(category = ModuleCategory.TWEAKS, hasSubscriptions = true)
public class EnhancedLaddersModule extends QuarkModule {

	@Config.Max(0)
	@Config
	public double fallSpeed = -0.2;

	@Config public static boolean allowFreestanding = true;
	@Config public static boolean allowDroppingDown = true;
	@Config public static boolean allowSliding = true;
	@Config public static boolean allowInventorySneak = true;

	private static boolean staticEnabled;
	private static TagKey<Item> laddersTag;

	@Override
	public void setup() {
		laddersTag = ItemTags.create(new ResourceLocation(Quark.MOD_ID, "ladders"));
	}

	@Override
	public void configChanged() {
		staticEnabled = enabled;
	}

	private static boolean canAttachTo(BlockState state, Block ladder, LevelReader world, BlockPos pos, Direction facing) {
		if (ladder instanceof LadderBlock) {
			if(allowFreestanding)
				return canLadderSurvive(state, world, pos);

			BlockPos offset = pos.relative(facing);
			BlockState blockstate = world.getBlockState(offset);
			return !blockstate.isSignalSource() && blockstate.isFaceSturdy(world, offset, facing);
		}

		return false;
	}

	public static boolean canLadderSurvive(BlockState state, LevelReader world, BlockPos pos) {
		if(!staticEnabled || !allowFreestanding)
			return false;

		Direction facing = state.getValue(LadderBlock.FACING);
		Direction opposite = facing.getOpposite();
		BlockPos oppositePos = pos.relative(opposite);
		BlockState oppositeState = world.getBlockState(oppositePos);

		boolean solid = facing.getAxis() != Axis.Y && oppositeState.isFaceSturdy(world, oppositePos, facing) && !(oppositeState.getBlock() instanceof LadderBlock);
		BlockState topState = world.getBlockState(pos.above());
		return solid || (topState.getBlock() instanceof LadderBlock && (facing.getAxis() == Axis.Y || topState.getValue(LadderBlock.FACING) == facing));
	}

	public static boolean updateLadder(BlockState state, Direction facing, BlockState facingState, LevelAccessor world, BlockPos currentPos, BlockPos facingPos) {
		if(!staticEnabled || !allowFreestanding)
			return true;

		return canLadderSurvive(state, world, currentPos);
	}

	@SubscribeEvent
	public void onInteract(PlayerInteractEvent.RightClickBlock event) {
		if(!allowDroppingDown)
			return;

		Player player = event.getEntity();
		InteractionHand hand = event.getHand();
		ItemStack stack = player.getItemInHand(hand);

		if(!stack.isEmpty() && stack.is(laddersTag)) {
			Block block = Block.byItem(stack.getItem());
			Level world = event.getLevel();
			BlockPos pos = event.getPos();
			while(world.getBlockState(pos).getBlock() == block) {
				event.setCanceled(true);
				BlockPos posDown = pos.below();

				if(world.isOutsideBuildHeight(posDown))
					break;

				BlockState stateDown = world.getBlockState(posDown);

				if(stateDown.getBlock() == block)
					pos = posDown;
				else {
					boolean water = stateDown.getBlock() == Blocks.WATER;
					if(water || stateDown.isAir()) {
						BlockState copyState = world.getBlockState(pos);

						Direction facing = copyState.getValue(LadderBlock.FACING);
						if(canAttachTo(copyState, block, world, posDown, facing.getOpposite())) {
							world.setBlockAndUpdate(posDown, copyState.setValue(BlockStateProperties.WATERLOGGED, water));
							world.playSound(null, posDown.getX(), posDown.getY(), posDown.getZ(), SoundEvents.LADDER_PLACE, SoundSource.BLOCKS, 1F, 1F);

							if(world.isClientSide)
								player.swing(hand);

							if(!player.getAbilities().instabuild) {
								stack.shrink(1);

								if(stack.getCount() <= 0)
									player.setItemInHand(hand, ItemStack.EMPTY);
							}
						}
					}
					break;
				}
			}
		}
	}

	@SubscribeEvent
	public void onPlayerTick(TickEvent.PlayerTickEvent event) {
		if(!allowSliding)
			return;

		if(event.phase == TickEvent.Phase.START) {
			Player player = event.player;
			if(player.onClimbable() && player.level.isClientSide) {
				BlockPos playerPos = player.blockPosition();
				BlockPos downPos = playerPos.below();

				boolean scaffold = player.level.getBlockState(playerPos).isScaffolding(player);
				if(player.isCrouching() == scaffold &&
						player.zza == 0 &&
						player.yya <= 0 &&
						player.xxa == 0 &&
						player.getXRot() > 70 &&
						!player.jumping &&
						!player.getAbilities().flying &&
						player.level.getBlockState(downPos).isLadder(player.level, downPos, player)) {

					Vec3 move = new Vec3(0, fallSpeed, 0);
					player.setBoundingBox(player.getBoundingBox().move(move));
					player.move(MoverType.SELF, move);
				}
			}
		}
	}

	@SubscribeEvent
	@OnlyIn(Dist.CLIENT)
	public void onInput(MovementInputUpdateEvent event) {
		if(!allowInventorySneak)
			return;

		Player player = event.getEntity();
		if(player.onClimbable() && !player.getAbilities().flying &&
				!player.level.getBlockState(player.blockPosition()).isScaffolding(player)
				&& Minecraft.getInstance().screen != null && !(player.zza == 0 && player.getXRot() > 70) && !player.isOnGround()) {
			Input input = event.getInput();
			if(input != null)
				input.shiftKeyDown = true; // sneaking
		}
	}

}
