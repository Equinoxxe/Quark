package vazkii.quark.base.handler;

import com.google.common.collect.Lists;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import org.apache.commons.lang3.tuple.Pair;
import vazkii.quark.api.ICollateralMover;
import vazkii.quark.api.ICollateralMover.MoveResult;
import vazkii.quark.api.IConditionalSticky;
import vazkii.quark.api.IIndirectConnector;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.function.Predicate;

public class QuarkPistonStructureResolver extends PistonStructureResolver {

	private final PistonStructureResolver parent;

	private final Level world;
	private final BlockPos pistonPos;
	private final BlockPos blockToMove;
	private final Direction moveDirection;
	private final List<BlockPos> toMove = Lists.newArrayList();
	private final List<BlockPos> toDestroy = Lists.newArrayList();

	public QuarkPistonStructureResolver(PistonStructureResolver parent, Level worldIn, BlockPos posIn, Direction pistonFacing, boolean extending) {
		super(worldIn, posIn, pistonFacing, extending);
		this.parent = parent;

		this.world = worldIn;
		this.pistonPos = posIn;
		if(extending) {
			this.moveDirection = pistonFacing;
			this.blockToMove = posIn.relative(pistonFacing);
		} else {
			this.moveDirection = pistonFacing.getOpposite();
			this.blockToMove = posIn.relative(pistonFacing, 2);
		}
	}

	@Override
	public boolean resolve() {
		if(!GeneralConfig.usePistonLogicRepl)
			return parent.resolve();

		toMove.clear();
		toDestroy.clear();
		BlockState iblockstate = world.getBlockState(blockToMove);

		if(!PistonBaseBlock.isPushable(iblockstate, world, blockToMove, moveDirection, false, moveDirection)) {
			if(iblockstate.getPistonPushReaction() == PushReaction.DESTROY) {
				toDestroy.add(blockToMove);
				return true;
			} else return false;
		}
		else if(!addBlockLine(blockToMove, moveDirection))
			return false;
		else {
			for(int i = 0; i < toMove.size(); ++i) {
				BlockPos blockpos = toMove.get(i);
				if (addBranchingBlocks(world, blockpos, isBlockBranching(world, blockpos)) == MoveResult.PREVENT)
					return false;
			}

			return true;
		}
	}

	private boolean addBlockLine(BlockPos origin, Direction face) {
		final int max = GeneralConfig.pistonPushLimit;

		BlockPos target = origin;
		BlockState state = world.getBlockState(target);

		if(state.isAir()
				|| !PistonBaseBlock.isPushable(state, world, origin, moveDirection, false, face)
				|| origin.equals(pistonPos)
				|| toMove.contains(origin))
			return true;

		else {
			int lineLen = 1;

			if(lineLen + toMove.size() > max)
				return false;
			else {
				BlockPos oldPos = origin;
				BlockState oldState = world.getBlockState(origin);

				boolean skippingNext = false;
				while(true) {
					if(!isBlockBranching(world, target))
						break;

					MoveResult res = getBranchResult(world, target);
					if(res == MoveResult.PREVENT)
						return false;
					else if(res != MoveResult.MOVE) {
						skippingNext = true;
						break;
					}

					target = origin.relative(moveDirection.getOpposite(), lineLen);
					state = world.getBlockState(target);

					if(state.isAir() || !PistonBaseBlock.isPushable(state, world, target, moveDirection, false, moveDirection.getOpposite()) || target.equals(pistonPos))
						break;

					if(getStickCompatibility(world, state, oldState, target, oldPos) != MoveResult.MOVE)
						break;

					oldState = state;
					oldPos = target;

					lineLen++;

					if(lineLen + toMove.size() > max)
						return false;
				}

				int collisionEnd = 0;

				for(int j = lineLen - 1; j >= 0; --j) {
					BlockPos movePos = origin.relative(moveDirection.getOpposite(), j);
					if(toDestroy.contains(movePos))
						break;

					toMove.add(movePos);
					collisionEnd++;
				}

				if(skippingNext)
					return true;

				int offset = 1;

				while(true) {
					BlockPos currentPos = origin.relative(moveDirection, offset);
					int collisionStart = toMove.indexOf(currentPos);

					MoveResult res;

					if(collisionStart > -1) {
						reorderListAtCollision(collisionEnd, collisionStart);

						for(int i = 0; i <= collisionStart + collisionEnd; ++i) {
							BlockPos collidingPos = toMove.get(i);

							if(addBranchingBlocks(world, collidingPos, isBlockBranching(world, collidingPos)) == MoveResult.PREVENT)
								return false;
						}

						return true;
					}

					state = world.getBlockState(currentPos);

					if(state.isAir())
						return true;

					if(!PistonBaseBlock.isPushable(state, world, currentPos, moveDirection, true, moveDirection) || currentPos.equals(pistonPos))
						return false;

					if(state.getPistonPushReaction() == PushReaction.DESTROY) {
						toDestroy.add(currentPos);
						toMove.remove(currentPos);
						return true;
					}

					boolean doneFinding = false;
					if(isBlockBranching(world, currentPos)) {
						res = getBranchResult(world, currentPos);
						if(res == MoveResult.PREVENT)
							return false;

						if(res != MoveResult.MOVE)
							doneFinding = true;
					}

					if(toMove.size() >= max)
						return false;

					toMove.add(currentPos);

					++collisionEnd;
					++offset;

					if(doneFinding)
						return true;
				}
			}
		}
	}

	private void reorderListAtCollision(int collisionEnd, int collisionStart) {
		List<BlockPos> before = Lists.newArrayList(toMove.subList(0, collisionStart));
		List<BlockPos> collision = Lists.newArrayList(toMove.subList(toMove.size() - collisionEnd, toMove.size()));
		List<BlockPos> after = Lists.newArrayList(toMove.subList(collisionStart, toMove.size() - collisionEnd));
		toMove.clear();
		toMove.addAll(before);
		toMove.addAll(collision);
		toMove.addAll(after);
	}

	@SuppressWarnings("incomplete-switch")
	private MoveResult addBranchingBlocks(Level world, BlockPos fromPos, boolean isSourceBranching) {
		BlockState state = world.getBlockState(fromPos);
		Block block = state.getBlock();

		Direction opposite = moveDirection.getOpposite();
		MoveResult retResult = MoveResult.SKIP;
		for(Direction face : Direction.values()) {
				MoveResult res;
				BlockPos targetPos = fromPos.relative(face);
				BlockState targetState = world.getBlockState(targetPos);

				if(!isSourceBranching) {
					IIndirectConnector indirect = getIndirectStickiness(targetState);
					if(indirect != null && indirect.isEnabled() && indirect.canConnectIndirectly(world, targetPos, fromPos, targetState, state))
						res = getStickCompatibility(world, state, targetState, fromPos, targetPos);
					else res = MoveResult.SKIP;
				}
				else {
					if(block instanceof ICollateralMover collateralMover)
						res = collateralMover.getCollateralMovement(world, pistonPos, moveDirection, face, fromPos);
					else res = getStickCompatibility(world, state, targetState, fromPos, targetPos);
				}

				switch(res) {
				case PREVENT:
					return MoveResult.PREVENT;
				case MOVE:
					if(!addBlockLine(targetPos, face))
						return MoveResult.PREVENT;
					break;
				case BREAK:
					if(PistonBaseBlock.isPushable(targetState, world, targetPos, moveDirection, true, moveDirection)) {
						toDestroy.add(targetPos);
						toMove.remove(targetPos);
						return MoveResult.BREAK;
					}

					return MoveResult.PREVENT;
				}

				if(face == opposite)
					retResult = res;
			}

		return retResult;
	}

	private boolean isBlockBranching(Level world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		Block block = state.getBlock();

		return block instanceof ICollateralMover ? ((ICollateralMover) block).isCollateralMover(world, pistonPos, moveDirection, pos) : isBlockSticky(state);
	}

	private MoveResult getBranchResult(Level world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		Block block = state.getBlock();

		if(block instanceof ICollateralMover collateralMover)
			return collateralMover.getCollateralMovement(world, pistonPos, moveDirection, moveDirection, pos);

		return MoveResult.MOVE;
	}

	private MoveResult getStickCompatibility(Level world, BlockState state1, BlockState state2, BlockPos pos1, BlockPos pos2) {
		IConditionalSticky stick = getStickCondition(state1);
		if(stick != null && !stick.canStickToBlock(world, pistonPos, pos1, pos2, state1, state2, moveDirection))
			return MoveResult.SKIP;

		stick = getStickCondition(state2);
		if(stick != null && !stick.canStickToBlock(world, pistonPos, pos2, pos1, state2, state1, moveDirection))
			return MoveResult.SKIP;

		return MoveResult.MOVE;
	}

	private IConditionalSticky getStickCondition(BlockState state) {
		Block block = state.getBlock();

		if(block instanceof IConditionalSticky sticky)
			return sticky;

		IIndirectConnector indirect = getIndirectStickiness(state);
		if(indirect != null)
			return indirect.isEnabled() ? indirect.getStickyCondition() : null;

		if (state.isStickyBlock())
			return DefaultStickCondition.INSTANCE;

		return null;
	}

	@Nonnull
	@Override
	public List<BlockPos> getToPush() {
		if(!GeneralConfig.usePistonLogicRepl)
			return parent.getToPush();

		return toMove;
	}

	@Nonnull
	@Override
	public List<BlockPos> getToDestroy() {
		if(!GeneralConfig.usePistonLogicRepl)
			return parent.getToDestroy();

		return toDestroy;
	}

	private static IIndirectConnector getIndirectStickiness(BlockState state) {
		for(Pair<Predicate<BlockState>, IIndirectConnector> p : IIndirectConnector.INDIRECT_STICKY_BLOCKS)
			if(p.getLeft().test(state))
				return p.getRight();

		return null;
	}

	private static boolean isBlockSticky(BlockState state) {
		if(state.isStickyBlock())
			return true;

		IIndirectConnector indirect = getIndirectStickiness(state);
		return indirect != null && indirect.isEnabled();
	}

	private static class DefaultStickCondition implements IConditionalSticky {

		private static final DefaultStickCondition INSTANCE = new DefaultStickCondition();

		@Override
		public boolean canStickToBlock(Level world, BlockPos pistonPos, BlockPos pos, BlockPos slimePos, BlockState state, BlockState slimeState, Direction direction) {
			return slimeState.canStickTo(state);
		}

	}

}
