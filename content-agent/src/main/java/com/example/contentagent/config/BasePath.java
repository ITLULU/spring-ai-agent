package com.example.contentagent.config;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 数据文件相对路径的统一解析（与 rag-kb 的 DataPath 同款问题的同款解法）。
 * 背景：多模块聚合启动时 CWD 是仓库根（spring-ai-agent/），而 data/ideas、data/output
 * 在 content-agent/ 模块下——直接 Path.of("data/ideas") 解析到仓库根下不存在的目录，
 * 旧 MarkdownReader 对"目录不存在"静默返回空列表，run-all 首屏 200 + []，
 * 新用户完全无从得知"文件在模块目录里，我在聚合根外面"。
 * 解析优先级：
 *  1. 系统属性 -Dcontent.agent.base.dir 或环境变量 CONTENT_AGENT_BASE_DIR（显式指定）；
 *  2. 自动探测：CWD 存在 data/ → CWD（模块目录直启、mvn test 走这里）；
 *     否则存在 content-agent/data/ → 以 content-agent 为数据根（聚合根启动场景）；
 *  3. 都找不到则维持 CWD，让报错信息暴露真实路径。
 * 绝对路径一律原样通过。懒加载探测一次后缓存。
 */
public final class BasePath {

    private static volatile Path baseDir;

    private BasePath() {
    }

    /** 把（可能相对的）路径解析到数据根目录下；绝对路径原样返回 */
    public static Path resolve(String path) {
        Path p = Path.of(path);
        return p.isAbsolute() ? p : baseDir().resolve(p).normalize();
    }

    /** 当前生效的数据根目录（诊断/日志用） */
    public static Path baseDir() {
        Path b = baseDir;
        if (b == null) {
            synchronized (BasePath.class) {
                b = baseDir;
                if (b == null) {
                    b = detect();
                    baseDir = b;
                }
            }
        }
        return b;
    }

    private static Path detect() {
        String override = System.getProperty("content.agent.base.dir",
                System.getenv("CONTENT_AGENT_BASE_DIR") == null
                        ? "" : System.getenv("CONTENT_AGENT_BASE_DIR"));
        if (!override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        if (Files.isDirectory(Path.of("data"))) {
            return Path.of(".").toAbsolutePath().normalize();
        }
        if (Files.isDirectory(Path.of("content-agent", "data"))) {
            return Path.of("content-agent").toAbsolutePath().normalize();
        }
        return Path.of(".").toAbsolutePath().normalize();
    }
}
