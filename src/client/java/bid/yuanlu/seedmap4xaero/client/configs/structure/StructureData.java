package bid.yuanlu.seedmap4xaero.client.configs.structure;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import bid.yuanlu.seedmap4xaero.client.configs.core.JsonCodec;

/**
 * structure 配置文档（{@code structure_settings.json}）的数据体：
 * 文件级的组可见性与用户组（原 structure_data.sm4x 的非标记部分）。
 * <p>
 * 结构持久标记已拆分到 {@code marks/<seed>/<mwId>/r.<x>.<z>.json} 分片
 * （{@link MarksStore}），与本设置文档独立读写。组改名/删组对标记引用的
 * 重写由 {@link StructureDataConfig} 编排（跨全部分片文档）。
 * <p>
 * 只负责数据与自身序列化；原子写、损坏回退、legacy 迁移在
 * {@link bid.yuanlu.seedmap4xaero.client.configs.core.JsonConfigFile}。
 *
 * <pre>
 * hiddenGroups : [ String … ]     // 面板中关闭显示的组
 * userGroups   : [ UserGroup … ]  // 用户组 + 内置组颜色覆盖
 * </pre>
 */
public class StructureData {

    private final ArrayList<String> hiddenGroups = new ArrayList<>();
    /**
     * 用户组表: 用户自建组 + 内置组的颜色覆盖条目。
     * mark.group() 以组名字符串引用; 内置组无条目时用 {@link StructureGroups} 默认色。
     */
    private final ArrayList<UserGroup> userGroups = new ArrayList<>();

    /** 用户组名上限。 */
    public static final int MAX_GROUP_NAME = 32;

    /** 组色条目: 用户组 (增删改查对象) 或内置组颜色覆盖。color 为 ARGB。 */
    public record UserGroup(String name, int color) {
    }

    final AtomicBoolean dirty = new AtomicBoolean(false);

    void makeDirty() {
        this.dirty.set(true);
    }

    // ─── 组可见性 ───────────────────────────────────────────────

    /** 组在面板/地图上是否被隐藏。 */
    public boolean isGroupHidden(String group) {
        synchronized (hiddenGroups) {
            return hiddenGroups.contains(group);
        }
    }

    /** 设置组可见性; 默认组允许隐藏 (隐藏所有未分组结构)。 */
    public void setGroupHidden(String group, boolean hidden) {
        synchronized (hiddenGroups) {
            if (hidden) {
                if (hiddenGroups.contains(group))
                    return;
                hiddenGroups.add(group);
            } else {
                if (!hiddenGroups.remove(group))
                    return;
            }
            makeDirty();
        }
    }

    // ─── 用户组 (三阶段) ────────────────────────────────────────

    /** 用户组快照 (含内置组颜色覆盖条目)。 */
    public List<UserGroup> userGroups() {
        synchronized (userGroups) {
            return List.copyOf(userGroups);
        }
    }

    /** 组名是否可用: 非空白、≤{@link #MAX_GROUP_NAME}、不与内置组重名。 */
    public static boolean validGroupName(String name) {
        if (name == null)
            return false;
        String t = name.trim();
        if (t.isEmpty() || t.length() > MAX_GROUP_NAME)
            return false;
        return !StructureGroups.isBuiltin(t);
    }

    /** 新建用户组; false = 名称非法或重名。 */
    public boolean addGroup(String name, int color) {
        if (!validGroupName(name))
            return false;
        String t = name.trim();
        synchronized (userGroups) {
            if (indexOfGroup(t) >= 0)
                return false;
            userGroups.add(new UserGroup(t, color));
        }
        makeDirty();
        return true;
    }

    /**
     * 设置任意组的颜色; 组无条目时创建 (内置组 = 颜色覆盖条目, 未知组拒绝)。
     * false = 未知组或颜色无变化。
     */
    public boolean setGroupColor(String name, int color) {
        synchronized (userGroups) {
            int i = indexOfGroup(name);
            if (i >= 0) {
                if (userGroups.get(i).color() == color)
                    return false;
                userGroups.set(i, new UserGroup(name, color));
            } else {
                if (!StructureGroups.isBuiltin(name))
                    return false;
                userGroups.add(new UserGroup(name, color));
            }
        }
        makeDirty();
        return true;
    }

    /** 移除内置组的颜色覆盖 (恢复默认色); 用户组请用 {@link #removeGroup}。 */
    public boolean clearGroupColor(String name) {
        if (!StructureGroups.isBuiltin(name))
            return false;
        synchronized (userGroups) {
            int i = indexOfGroup(name);
            if (i < 0)
                return false;
            userGroups.remove(i);
        }
        makeDirty();
        return true;
    }

    /**
     * 重命名用户组的设置部分（组表条目 + 隐藏表）；
     * 标记引用的重写在 {@link MarksStore#rewriteGroupRefs}（由门面编排）。
     * false = 名称非法/重名/内置组。
     */
    public boolean renameGroup(String from, String to) {
        if (StructureGroups.isBuiltin(from) || !validGroupName(to))
            return false;
        String t = to.trim();
        synchronized (userGroups) {
            int i = indexOfGroup(from);
            if (i < 0 || indexOfGroup(t) >= 0)
                return false;
            userGroups.set(i, new UserGroup(t, userGroups.get(i).color()));
        }
        synchronized (hiddenGroups) {
            int hi = hiddenGroups.indexOf(from);
            if (hi >= 0)
                hiddenGroups.set(hi, t);
        }
        makeDirty();
        return true;
    }

    /**
     * 删除用户组的设置部分（组表条目 + 隐藏表）；
     * 标记引用的重写在 {@link MarksStore#rewriteGroupRefs}（由门面编排）。
     * false = 内置组或不存在。
     */
    public boolean removeGroup(String name) {
        if (StructureGroups.isBuiltin(name))
            return false;
        synchronized (userGroups) {
            int i = indexOfGroup(name);
            if (i < 0)
                return false;
            userGroups.remove(i);
        }
        synchronized (hiddenGroups) {
            hiddenGroups.remove(name);
        }
        makeDirty();
        return true;
    }

    /** 组名 → 条目下标 (须在 userGroups 锁内调用)。 */
    private int indexOfGroup(String name) {
        for (int i = 0; i < userGroups.size(); i++)
            if (userGroups.get(i).name().equals(name))
                return i;
        return -1;
    }

    /** 用户组/覆盖条目的颜色; 无条目返回 0 (由调用方回退内置默认色)。 */
    public int colorOf(String group) {
        synchronized (userGroups) {
            int i = indexOfGroup(group);
            return i >= 0 ? userGroups.get(i).color() : 0;
        }
    }

    // ─── JSON 持久化 ────────────────────────────────────────────

    private JsonObject writeJson() {
        var json = new JsonObject();
        json.addProperty("version", 1);
        var hidden = new JsonArray();
        synchronized (hiddenGroups) {
            for (String g : hiddenGroups)
                hidden.add(g);
        }
        if (hidden.size() > 0)
            json.add("hiddenGroups", hidden);

        var groups = new JsonArray();
        synchronized (userGroups) {
            for (UserGroup ug : userGroups) {
                var o = new JsonObject();
                o.addProperty("name", ug.name());
                o.addProperty("color", ug.color());
                groups.add(o);
            }
        }
        if (groups.size() > 0)
            json.add("userGroups", groups);
        return json;
    }

    private static StructureData readJson(JsonObject json) throws IOException {
        try {
            var data = new StructureData();
            if (json.has("hiddenGroups")) {
                synchronized (data.hiddenGroups) {
                    for (var el : json.getAsJsonArray("hiddenGroups"))
                        data.hiddenGroups.add(el.getAsString());
                }
            }
            if (json.has("userGroups")) {
                synchronized (data.userGroups) {
                    for (var el : json.getAsJsonArray("userGroups")) {
                        var o = el.getAsJsonObject();
                        data.userGroups.add(new UserGroup(
                                o.get("name").getAsString(), o.get("color").getAsInt()));
                    }
                }
            }
            return data;
        } catch (RuntimeException e) {
            throw new IOException("Malformed structure settings", e);
        }
    }

    /** structure 设置文档的 JSON 编解码器，配合 {@code JsonConfigFile} 使用。 */
    public static final JsonCodec<StructureData> JSON_CODEC = new JsonCodec<>() {
        @Override
        public JsonObject write(StructureData data) {
            return data.writeJson();
        }

        @Override
        public StructureData read(JsonObject json) throws IOException {
            return StructureData.readJson(json);
        }
    };
}
