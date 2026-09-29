package org.gwfx.aiassisted.core.build;

/**
 * 把"蓝图的相对坐标"映射成"世界的绝对坐标"（零 MC 依赖，可单测）。
 *
 * <p><b>为什么模型不参与选坐标</b>：一旦模型能指定绝对坐标，它就能把建筑盖到别人家、
 * 盖在别人的机器上、或者拿它当"隔空挖穿地皮"的手段。所以蓝图只有相对坐标（0..w, 0..h, 0..d），
 * 物理位置完全由"玩家站在哪、面朝哪"决定。
 *
 * <p><b>为什么原点要沿面朝方向偏移 2 格</b>：不偏移的话玩家自己就站在轮廓里，建完会卡在墙里。
 * 另一条路是"建完把玩家传送出去"，但那要处理坠落/骑乘/落点安全，还多出一次改世界的动作。
 * 纯数学偏移零风险，而且玩家能亲眼看着房子在面前长出来。
 * {@link #contains} 就是用来把这个不变量钉进单测的。
 *
 * <p><b>坐标约定</b>：本地 +Z 是"建筑的正面"，朝向玩家的面朝方向；本地 +X 是玩家的左手边；
 * +Y 向上。四个朝向都只是绕 Y 轴的一次旋转，不镜像（否则图纸会左右反）。
 */
public final class BuildPlacement {

    /** 水平朝向。core 不能引用 MC 的 {@code Direction}，所以自己定义一个四值枚举。 */
    public enum Facing {
        NORTH(0, -1),
        SOUTH(0, 1),
        WEST(-1, 0),
        EAST(1, 0);

        private final int stepX;
        private final int stepZ;

        Facing(int stepX, int stepZ) {
            this.stepX = stepX;
            this.stepZ = stepZ;
        }

        public int stepX() {
            return this.stepX;
        }

        public int stepZ() {
            return this.stepZ;
        }
    }

    /** 世界坐标（整格）。 */
    public record Position(int x, int y, int z) {
    }

    private final int originX;
    private final int originY;
    private final int originZ;
    private final Facing facing;

    private BuildPlacement(int originX, int originY, int originZ, Facing facing) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.facing = facing;
    }

    public Facing facing() {
        return this.facing;
    }

    public Position origin() {
        return new Position(this.originX, this.originY, this.originZ);
    }

    private static BuildPlacement at(int originX, int originY, int originZ, Facing facing) {
        return new BuildPlacement(originX, originY, originZ, facing == null ? Facing.NORTH : facing);
    }

    /**
     * 以玩家脚下方块为基准、沿面朝方向偏移 {@code offset} 格作为蓝图原点。
     *
     * @param offset 建议 ≥2（见类注释的"玩家不在轮廓里"不变量）
     */
    public static BuildPlacement inFrontOf(int footX, int footY, int footZ, Facing facing, int offset) {
        Facing safe = facing == null ? Facing.NORTH : facing;
        int steps = Math.max(0, offset);
        return at(footX + safe.stepX() * steps, footY, footZ + safe.stepZ() * steps, safe);
    }

    /** 相对坐标 → 世界坐标。 */
    public Position toWorld(int localX, int localY, int localZ) {
        int worldX;
        int worldZ;
        switch (this.facing) {
            case SOUTH -> {
                worldX = this.originX + localX;
                worldZ = this.originZ + localZ;
            }
            case WEST -> {
                worldX = this.originX - localZ;
                worldZ = this.originZ + localX;
            }
            case NORTH -> {
                worldX = this.originX - localX;
                worldZ = this.originZ - localZ;
            }
            default -> {
                worldX = this.originX + localZ;
                worldZ = this.originZ - localX;
            }
        }
        return new Position(worldX, this.originY + localY, worldZ);
    }

    /**
     * 该世界坐标是否落在蓝图包围盒内（含空气格）。
     *
     * <p>用途是断言"玩家所在格不在轮廓里" —— 这条不变量一旦破了，玩家会被埋在墙里，
     * 而且是在 {@code /ai confirm} 之后才发现。
     */
    public boolean contains(int worldX, int worldY, int worldZ, int width, int height, int depth) {
        int dx = worldX - this.originX;
        int dz = worldZ - this.originZ;
        int dy = worldY - this.originY;
        int localX;
        int localZ;
        switch (this.facing) {
            case SOUTH -> {
                localX = dx;
                localZ = dz;
            }
            case WEST -> {
                localX = dz;
                localZ = -dx;
            }
            case NORTH -> {
                localX = -dx;
                localZ = -dz;
            }
            default -> {
                localX = -dz;
                localZ = dx;
            }
        }
        return localX >= 0 && localX < width
                && localZ >= 0 && localZ < depth
                && dy >= 0 && dy < height;
    }
}
