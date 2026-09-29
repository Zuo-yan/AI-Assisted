package org.gwfx.aiassisted.core.build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 建筑构件语义分类器（零 MC 依赖，纯 Java 逻辑）。
 *
 * <p>将一组空间体素（Voxel）自动识别分类为：
 * <ul>
 *   <li><b>ROOF</b>：屋顶（坡顶楼梯、台阶、横梁、山墙）</li>
 *   <li><b>PILLAR</b>：立柱（原木、梁柱、栅栏柱）</li>
 *   <li><b>WINDOW</b>：门窗系统（玻璃、玻璃板、门、活板门）</li>
 *   <li><b>FLOOR</b>：地坪/地板（底层水平铺层）</li>
 *   <li><b>DECORATION</b>：装饰物（灯笼、火把、花盆、地毯等）</li>
 *   <li><b>WALL</b>：墙体（垂直大面积主结构）</li>
 * </ul>
 */
public final class BuildingComponentClassifier {

    public enum ComponentType {
        ROOF("屋顶"),
        PILLAR("立柱"),
        WALL("墙体"),
        WINDOW("门窗"),
        FLOOR("地板"),
        DECORATION("装饰物"),
        OTHER("其他");

        private final String label;

        ComponentType(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }

        public static ComponentType parse(String name) {
            if (name == null || name.isBlank()) {
                return OTHER;
            }
            String s = name.strip().toLowerCase(Locale.ROOT);
            if (s.contains("roof") || s.contains("顶")) return ROOF;
            if (s.contains("pillar") || s.contains("柱") || s.contains("beam")) return PILLAR;
            if (s.contains("wall") || s.contains("墙")) return WALL;
            if (s.contains("window") || s.contains("窗") || s.contains("door") || s.contains("门")) return WINDOW;
            if (s.contains("floor") || s.contains("地") || s.contains("地面")) return FLOOR;
            if (s.contains("decor") || s.contains("饰") || s.contains("灯")) return DECORATION;
            return OTHER;
        }
    }

    public record Voxel(int x, int y, int z, String blockId) {
    }

    private BuildingComponentClassifier() {
    }

    /** 对给定的体素集合进行构件分类，返回 构件类型 → 该构件包含的体素列表。 */
    public static Map<ComponentType, List<Voxel>> classify(List<Voxel> voxels) {
        if (voxels == null || voxels.isEmpty()) {
            return Map.of();
        }

        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        Set<Long> columnPillars = new HashSet<>();
        Map<Long, List<Voxel>> byColumn = new HashMap<>();

        for (Voxel v : voxels) {
            if (v.y < minY) minY = v.y;
            if (v.y > maxY) maxY = v.y;
            long colKey = (((long) v.x) << 32) | (v.z & 0xFFFFFFFFL);
            byColumn.computeIfAbsent(colKey, k -> new ArrayList<>()).add(v);
        }

        int height = Math.max(1, maxY - minY + 1);

        // 识别立柱：同一 (x,z) 列上连续出现 2 个以上 log/wood/pillar/fence
        for (Map.Entry<Long, List<Voxel>> entry : byColumn.entrySet()) {
            List<Voxel> col = entry.getValue();
            int logCount = 0;
            for (Voxel v : col) {
                String id = v.blockId().toLowerCase(Locale.ROOT);
                if (id.contains("log") || id.contains("wood") || id.contains("pillar") || id.contains("fence")) {
                    logCount++;
                }
            }
            if (logCount >= 2) {
                columnPillars.add(entry.getKey());
            }
        }

        Map<ComponentType, List<Voxel>> result = new HashMap<>();
        for (ComponentType type : ComponentType.values()) {
            result.put(type, new ArrayList<>());
        }

        for (Voxel v : voxels) {
            String id = v.blockId().toLowerCase(Locale.ROOT);
            long colKey = (((long) v.x) << 32) | (v.z & 0xFFFFFFFFL);
            double relY = (double) (v.y - minY) / height;

            if (id.contains("glass") || id.contains("pane") || id.contains("door") || id.contains("gate")) {
                result.get(ComponentType.WINDOW).add(v);
            } else if (id.contains("torch") || id.contains("lantern") || id.contains("flower_pot")
                    || id.contains("carpet") || id.contains("chain") || id.contains("banner")) {
                result.get(ComponentType.DECORATION).add(v);
            } else if ((id.contains("stairs") || id.contains("slab")) && relY >= 0.35) {
                result.get(ComponentType.ROOF).add(v);
            } else if (columnPillars.contains(colKey)
                    && (id.contains("log") || id.contains("wood") || id.contains("pillar") || id.contains("fence"))) {
                result.get(ComponentType.PILLAR).add(v);
            } else if (v.y == minY && !id.contains("air")) {
                result.get(ComponentType.FLOOR).add(v);
            } else if (relY >= 0.7 && (id.contains("stairs") || id.contains("slab") || id.contains("wood") || id.contains("planks"))) {
                result.get(ComponentType.ROOF).add(v);
            } else {
                result.get(ComponentType.WALL).add(v);
            }
        }

        Map<ComponentType, List<Voxel>> unmodifiable = new HashMap<>();
        for (Map.Entry<ComponentType, List<Voxel>> e : result.entrySet()) {
            unmodifiable.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
        }
        return Collections.unmodifiableMap(unmodifiable);
    }
}
