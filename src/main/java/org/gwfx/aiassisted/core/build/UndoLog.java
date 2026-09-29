package org.gwfx.aiassisted.core.build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次建造的撤销记录（零 MC 依赖，可单测；MC 侧用 {@code UndoLog<BlockState>} 实例化）。
 *
 * <p><b>只记真正被改动的格子</b>：目标位置原本就是同一种方块时既没放也没改，
 * 记进去只会在撤销时多一次无意义的写入，还会让"跳过了 N 格"这个计数变得没意义。
 *
 * <p><b>为什么要同时记 {@code placedBlockId}</b>：撤销前要确认"这一格现在还是我们放下的那个方块"。
 * 如果玩家（或别人）在我们建造之后又改了这一格，就不该撤销它 ——
 * 否则撤销会吃掉别人后来的改动。这就是"条件撤销"。
 *
 * @param <S> 旧方块的表示（MC 侧是 {@code BlockState}；单测里可以是 String）
 */
public final class UndoLog<S> {

    /** 一条撤销记录：位置 + 被替换掉的旧方块 + 我们放下的方块 id。 */
    public record Entry<S>(int x, int y, int z, S previousState, String placedBlockId) {
    }

    private final List<Entry<S>> entries = new ArrayList<>();

    /** 记录一次成功改动（{@code setBlock} 返回 true 之后才调）。 */
    public void record(int x, int y, int z, S previousState, String placedBlockId) {
        this.entries.add(new Entry<>(x, y, z, previousState, placedBlockId));
    }

    /** 按记录顺序（= 放置顺序）。 */
    public List<Entry<S>> entries() {
        return List.copyOf(this.entries);
    }

    /** 撤销顺序：与放置顺序相反，先拆最后放的。 */
    public List<Entry<S>> reversed() {
        List<Entry<S>> copy = new ArrayList<>(this.entries);
        Collections.reverse(copy);
        return List.copyOf(copy);
    }

    public int size() {
        return this.entries.size();
    }

    public boolean isEmpty() {
        return this.entries.isEmpty();
    }
}
