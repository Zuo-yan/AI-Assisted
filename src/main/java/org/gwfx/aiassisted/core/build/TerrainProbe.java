package org.gwfx.aiassisted.core.build;

/**
 * 覆盖检查用的"世界现状"探针（接口放 core 是为了能用假实现做单测）。
 *
 * <p>MC 侧的实现要守住两条：
 * <ol>
 *   <li><b>只读已加载区块</b>（{@code ServerChunkCache#getChunkNow}）——
 *       {@code Level#getBlockState} 会<b>同步加载区块</b>，为了校验一张图纸就把远处的区块拉起来，
 *       是"校验把服务器卡死"的经典写法。</li>
 *   <li>自然地形判定要往<b>保守</b>方向做：判不准就当人工方块（拒绝建造），
 *       宁可让玩家自己去挖一下，也不要覆盖掉别人的机器。</li>
 * </ol>
 */
public interface TerrainProbe {

    /** 该位置所在区块是否已加载。未加载时其余两个方法都不会被调用。 */
    boolean isLoaded(int worldX, int worldY, int worldZ);

    /** 现状方块 id（形如 {@code minecraft:oak_planks}）。仅在已加载时调用。 */
    String currentBlockId(int worldX, int worldY, int worldZ);

    /** 现状是否属于"自然地形"（可被覆盖）。仅在已加载时调用。 */
    boolean isNaturalTerrain(int worldX, int worldY, int worldZ);
}
