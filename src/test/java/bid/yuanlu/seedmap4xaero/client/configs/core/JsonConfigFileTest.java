package bid.yuanlu.seedmap4xaero.client.configs.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JsonConfigFile 的迁移/损坏矩阵：与具体配置包无关的通用语义。
 * <p>
 * 迁移失败语义（核心不变量）：legacy <b>解码成功但落盘失败</b>时，
 * 必须仍返回解码数据供会话使用且 legacy 不改名——否则会话退到 fallback
 * 后被生命周期保存写成 .json，永久遮蔽 legacy 里的真实用户数据。
 */
class JsonConfigFileTest {

    @TempDir
    Path tmp;

    record Payload(String value) {
    }

    private static final JsonCodec<Payload> JSON_CODEC = new JsonCodec<>() {
        @Override
        public com.google.gson.JsonObject write(Payload data) {
            var json = new com.google.gson.JsonObject();
            json.addProperty("value", data.value());
            return json;
        }

        @Override
        public Payload read(com.google.gson.JsonObject json) throws IOException {
            try {
                return new Payload(json.get("value").getAsString());
            } catch (RuntimeException e) {
                throw new IOException("malformed payload", e);
            }
        }
    };

    private static final Sm4xCodec<Payload> SM4X_CODEC = new Sm4xCodec<>() {
        @Override
        public void write(Payload data, DataOutputStream out) throws IOException {
            out.writeBoolean(data.value() != null);
            if (data.value() != null)
                out.writeUTF(data.value());
        }

        @Override
        public Payload read(DataInputStream in) throws IOException {
            return new Payload(in.readBoolean() ? in.readUTF() : null);
        }
    };

    private static JsonConfigFile.Paths paths(Path base, String mainId) {
        return JsonConfigFile.pathsFor(base, mainId, "cfg.json", "cfg.sm4x");
    }

    private static void writeLegacy(Path file, String value) throws IOException {
        Files.createDirectories(file.getParent());
        Sm4xFile.writeFrame(file, new Payload(value), SM4X_CODEC);
    }

    // ─── 损坏矩阵: 每种损坏都只损失该文件, 继续回退链 ───────────

    @Test
    void corruptJsonMainFallsToLegacyMigration() throws IOException {
        writeLegacy(paths(tmp, "t").legacy(), "from-legacy");
        Files.createDirectories(paths(tmp, "t").target().getParent());
        Files.writeString(paths(tmp, "t").target(), "{not json");

        Payload loaded = JsonConfigFile.load(paths(tmp, "t"), new Payload("fb"), JSON_CODEC, SM4X_CODEC);

        assertEquals("from-legacy", loaded.value());
        assertTrue(Files.exists(paths(tmp, "t").target()), "migrated json exists");
        // 损坏的主文件被删除, .old 不存在 → 无残留
        assertTrue(Files.notExists(paths(tmp, "t").old()));
    }

    @Test
    void truncatedSm4xSkippedOldMigrated() throws IOException {
        Path good = tmp.resolve("good.sm4x");
        writeLegacy(good, "from-old");
        byte[] full = Files.readAllBytes(good);
        // 主文件: 有效 magic 开头但身体截断 → 解码失败
        Path main = paths(tmp, "t").legacy();
        Files.createDirectories(main.getParent());
        Files.write(main, java.util.Arrays.copyOf(full, full.length - 5));
        // .old: 完整 → 迁移源
        Files.write(paths(tmp, "t").legacyOld(), full);

        Payload loaded = JsonConfigFile.load(paths(tmp, "t"), new Payload("fb"), JSON_CODEC, SM4X_CODEC);

        assertEquals("from-old", loaded.value());
        assertTrue(Files.exists(paths(tmp, "t").target()));
        // 两个 legacy 都退出回退链
        assertFalse(Files.exists(main));
        assertFalse(Files.exists(paths(tmp, "t").legacyOld()));
    }

    @Test
    void allCandidatesCorruptFallsBack() throws IOException {
        Files.createDirectories(paths(tmp, "t").target().getParent());
        Files.writeString(paths(tmp, "t").target(), "");
        Files.writeString(paths(tmp, "t").old(), "]]]");
        Files.writeString(paths(tmp, "t").legacy(), "garbage");

        Payload loaded = JsonConfigFile.load(paths(tmp, "t"), new Payload("fallback"), JSON_CODEC, SM4X_CODEC);

        assertEquals("fallback", loaded.value());
        // legacy 损坏不改名 (保留现场供诊断)
        assertTrue(Files.exists(paths(tmp, "t").legacy()));
    }

    // ─── 迁移落盘失败: 数据保全 (核心不变量) ───────────────────

    @Test
    void persistFailureKeepsDecodedLegacyData() throws IOException {
        assumeTrue(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews()
                .contains("posix"), "posix perms required");
        writeLegacy(paths(tmp, "t").legacy(), "precious");
        Path dir = paths(tmp, "t").target().getParent();
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"));
        try {
            Payload loaded = JsonConfigFile.load(paths(tmp, "t"), new Payload("fb"), JSON_CODEC, SM4X_CODEC);

            assertEquals("precious", loaded.value(), "落盘失败仍返回解码数据, 会话不退到 fallback");
            assertTrue(Files.exists(paths(tmp, "t").legacy()), "legacy 不改名, 下次启动重试迁移");
            assertFalse(Files.exists(paths(tmp, "t").target()), "没有半成品 json");
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
    }

    // ─── 双重迁移幂等 (crash-after-save-before-retire 模拟) ─────

    @Test
    void reMigrationIsIdempotent() throws IOException {
        writeLegacy(paths(tmp, "t").legacy(), "same-data");
        Payload first = JsonConfigFile.load(paths(tmp, "t"), new Payload("fb"), JSON_CODEC, SM4X_CODEC);
        byte[] jsonFirst = Files.readAllBytes(paths(tmp, "t").target());
        assertEquals("same-data", first.value());

        // 模拟 crash: json 落盘后、retire 前进程死亡 → 重启后 json 存在走 json (legacy 静置)
        writeLegacy(paths(tmp, "t").legacy(), "same-data");
        Payload viaJson = JsonConfigFile.load(paths(tmp, "t"), new Payload("fb"), JSON_CODEC, SM4X_CODEC);
        assertEquals("same-data", viaJson.value());

        // 模拟 json 损坏: 删除后重新迁移 → 产物逐字节一致
        Files.delete(paths(tmp, "t").target());
        JsonConfigFile.load(paths(tmp, "t"), new Payload("fb"), JSON_CODEC, SM4X_CODEC);
        assertEquals(new String(jsonFirst, StandardCharsets.UTF_8),
                Files.readString(paths(tmp, "t").target()), "重新迁移产物一致");
    }
}
