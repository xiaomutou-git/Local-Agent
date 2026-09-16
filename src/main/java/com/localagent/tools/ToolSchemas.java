package com.localagent.tools;

import com.localagent.toolkit.ToolDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具参数 JSON Schema 注册表与轻量运行时校验器。
 *
 * 核心功能：
 * 1. 为全部工具维护一份与 OpenAI/Ollama function-calling 协议兼容的 parameters
 *    JSON Schema（参数名、类型、必填、枚举、嵌套数组/对象），随 tools 字段发给模型，
 *    替代过去"参数靠中文描述让模型猜"的做法，显著降低 8B 模型拼错参数名/类型的概率；
 * 2. 在工具执行前对模型回传的 arguments 做严格校验（必填、类型、枚举、数值范围、
 *    数组长度、未知参数），不通过时由 Agent 把中文错误结构化回填给模型，
 *    使其下一轮自行修正参数。
 *
 * 设计思路：Schema 用不可序列化期外的普通 Map 构建（Jackson 可直接序列化），
 * 通过流式构建器 {@link B} 压缩样板代码；校验器只实现本项目用到的 JSON Schema 子集
 * （string/integer/number/boolean/array/object/enum/minimum/maximum/
 * minItems/maxItems/additionalProperties），零外部依赖。
 * 所有数值上下界均取自 Tools/Office 执行代码中的实际 clamp 边界，不做惯例臆测。
 *
 * 适用场景：本地单 Agent 工具调用；跨字段"二选一"约束（如 kill_process 的 pid/name）
 * 不进 Schema，仍由各工具内部语义校验负责。
 *
 * 创建时间：2026-09-15，核心用途：收紧工具调用参数契约并支持运行时早失败。
 */
public final class ToolSchemas {

    /** 无参工具统一使用的空对象 Schema（additionalProperties=false，禁止任何参数）。 */
    private static final Map<String, Object> EMPTY_OBJECT = objectFragment("");

    /** 工具名 -> 参数 Schema；仅注册带参工具，未注册者视为无参。 */
    private static final Map<String, Map<String, Object>> REGISTRY = buildRegistry();

    private ToolSchemas() {}

    /**
     * 按工具名获取参数 Schema。
     * @param name 工具名（与 ToolDef.name 一致），允许为 null
     * @return 永不返回 null；带参工具返回其 Schema，其余返回空对象 Schema
     */
    public static Map<String, Object> of(String name) {
        Map<String, Object> s = name == null ? null : REGISTRY.get(name);
        return s != null ? s : EMPTY_OBJECT;
    }

    // ============================ 运行时校验 ============================

    /**
     * 按 ToolDef 携带的 Schema 校验模型给出的参数。
     * 执行逻辑：Schema 缺失直接放行；否则逐字段检查必填/类型/枚举/未知参数，
     * 数组与嵌套对象递归校验。
     * @param def  工具定义（提供 parameters Schema）；为 null 时返回空错误列表
     * @param args 模型回传并已 JSON 解析的参数 Map，可为 null（按空 Map 处理）
     * @return 中文错误说明列表；空列表表示校验通过
     * 异常说明：本方法不抛出受检异常；任何非预期结构按"类型不匹配"记为一条错误，
     * 绝不中断 Agent 主循环
     */
    public static List<String> validate(ToolDef def, Map<String, Object> args) {
        List<String> errors = new ArrayList<>();
        if (def == null || def.parameters() == null) return errors;
        checkObject(def.parameters(), args == null ? Map.of() : args, "$", errors);
        return errors;
    }

    /**
     * 校验一个 object 类型节点。
     * @param schema 对象 Schema（properties/required/additionalProperties）
     * @param value  实际参数 Map
     * @param path   当前字段路径（用于错误定位，如 "$" 或 "$.slides[2]"）
     * @param errors 错误收集列表（输出参数）
     */
    @SuppressWarnings("unchecked")
    private static void checkObject(Map<String, Object> schema, Map<String, Object> value,
                                    String path, List<String> errors) {
        Map<String, Object> props = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
        List<String> required = (List<String>) schema.getOrDefault("required", List.of());
        // 必填检查：缺失或纯空白字符串都视为未提供（数字/布尔的 false/0 是合法值）
        for (String key : required) {
            Object v = value.get(key);
            if (v == null || (v instanceof String s && s.isBlank())) {
                errors.add("缺少必填参数 " + leaf(path, key));
            }
        }
        // 显式 additionalProperties=false 时拒绝模型编造的未知参数
        boolean strict = schema.containsKey("additionalProperties")
                && !Boolean.TRUE.equals(schema.get("additionalProperties"));
        for (Map.Entry<String, Object> e : value.entrySet()) {
            String key = e.getKey();
            Object v = e.getValue();
            if (!props.containsKey(key)) {
                if (strict) errors.add("未知参数 " + leaf(path, key) + "（该工具不接受此参数）");
                continue;
            }
            // 可选参数显式给 null：视为未提供，跳过类型检查
            if (v != null) checkValue(props.get(key), v, leaf(path, key), errors);
        }
    }

    /**
     * 校验单个值是否符合属性 Schema。
     * @param schema 属性 Schema（type/enum/items）
     * @param value  实际值（非 null）
     * @param path   字段路径
     * @param errors 错误收集列表
     */
    @SuppressWarnings("unchecked")
    private static void checkValue(Object schema, Object value, String path, List<String> errors) {
        if (!(schema instanceof Map<?, ?>)) return;
        Map<String, Object> sm = (Map<String, Object>) schema;
        String type = String.valueOf(sm.get("type"));
        boolean typeOk = switch (type) {
            case "string" -> value instanceof String;
            case "integer" -> isIntegral(value);
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List<?>;
            case "object" -> value instanceof Map<?, ?>;
            default -> true;
        };
        if (!typeOk) {
            errors.add("参数 " + path + " 类型应为 " + zhType(type) + "，实际为 " + zhType(value));
            return;
        }
        // 枚举约束（本项目枚举值均为字符串）
        Object enumNode = sm.get("enum");
        if (enumNode instanceof List<?> allowed && !allowed.contains(value)) {
            errors.add("参数 " + path + " 取值必须是 " + allowed + " 之一");
        }
        // 数值范围约束 minimum/maximum（边界值与执行代码的 clamp 一致，闭区间）
        if (value instanceof Number n) {
            double d = n.doubleValue();
            Object lo = sm.get("minimum");
            Object hi = sm.get("maximum");
            if (lo instanceof Number min && d < min.doubleValue()) {
                errors.add("参数 " + path + " 数值不能小于 " + min + "（当前 " + n + "）");
            }
            if (hi instanceof Number max && d > max.doubleValue()) {
                errors.add("参数 " + path + " 数值不能大于 " + max + "（当前 " + n + "）");
            }
        }
        if (value instanceof List<?> list) {
            // 数组长度约束 minItems/maxItems
            int size = list.size();
            Object loN = sm.get("minItems");
            Object hiN = sm.get("maxItems");
            if (loN instanceof Number min && size < min.intValue()) {
                errors.add("参数 " + path + " 元素数量不能少于 " + min.intValue() + "（当前 " + size + "）");
            }
            if (hiN instanceof Number max && size > max.intValue()) {
                errors.add("参数 " + path + " 元素数量不能多于 " + max.intValue() + "（当前 " + size + "）");
            }
            // 数组元素递归校验（items 声明元素 Schema）
            if (sm.get("items") instanceof Map<?, ?>) {
                Map<String, Object> items = (Map<String, Object>) sm.get("items");
                for (int i = 0; i < list.size(); i++) {
                    Object el = list.get(i);
                    if (el == null) continue;
                    if ("object".equals(String.valueOf(items.get("type"))) && el instanceof Map<?, ?> raw) {
                        castObject(raw, path + "[" + i + "]", items, errors);
                    } else {
                        checkValue(items, el, path + "[" + i + "]", errors);
                    }
                }
            }
        }
    }

    /**
     * 把通配符 Map 安全转成 Map<String,Object> 后递归对象校验。
     * @param raw    数组元素中的原始 Map（键理论上都是 JSON 字符串键）
     * @param path   元素路径
     * @param schema 元素对象 Schema
     * @param errors 错误收集列表
     */
    private static void castObject(Map<?, ?> raw, String path, Map<String, Object> schema, List<String> errors) {
        Map<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<?, ?> en : raw.entrySet()) converted.put(String.valueOf(en.getKey()), en.getValue());
        checkObject(schema, converted, path, errors);
    }

    /** 判断值是否为整数值的 JSON 数字（Integer/Long 或无小数部分的 Double）。 */
    private static boolean isIntegral(Object v) {
        if (v instanceof Integer || v instanceof Long) return true;
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return !Double.isInfinite(d) && !Double.isNaN(d) && d == Math.rint(d);
        }
        return false;
    }

    /** Schema 类型名转中文（错误文案用）。 */
    private static String zhType(String type) {
        return switch (type) {
            case "string" -> "字符串";
            case "integer" -> "整数";
            case "number" -> "数字";
            case "boolean" -> "布尔值";
            case "array" -> "数组";
            case "object" -> "对象";
            default -> type;
        };
    }

    /** 实际值的 Java 类型转中文名（错误文案用）。 */
    private static String zhType(Object v) {
        if (v instanceof String) return "字符串";
        if (isIntegral(v)) return "整数";
        if (v instanceof Number) return "数字";
        if (v instanceof Boolean) return "布尔值";
        if (v instanceof List<?>) return "数组";
        if (v instanceof Map<?, ?>) return "对象";
        return v.getClass().getSimpleName();
    }

    /** 拼字段路径：根级显示参数名，嵌套显示父路径.字段。 */
    private static String leaf(String parent, String key) {
        return "$".equals(parent) ? key : parent + "." + key;
    }

    // ============================ Schema 构建器 ============================

    /** 构造一个空的 object 类型 Schema 骨架（带空 properties/required）。 */
    private static Map<String, Object> objectFragment(String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        if (desc != null && !desc.isEmpty()) m.put("description", desc);
        m.put("properties", new LinkedHashMap<String, Object>());
        m.put("required", new ArrayList<String>());
        m.put("additionalProperties", false);
        return m;
    }

    /** 字符串属性片段。 */
    private static Map<String, Object> str(String desc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "string"); m.put("description", desc);
        return m;
    }

    /**
     * 整数属性片段（上下界均受限）。
     * @param desc 参数中文说明
     * @param min  最小值（闭区间，取自执行代码 clamp 下界）
     * @param max  最大值（闭区间，取自执行代码 clamp 上界）
     * @return 带 minimum/maximum 的 integer Schema 片段
     */
    private static Map<String, Object> integer(String desc, long min, long max) {
        Map<String, Object> m = integer(desc, min);
        m.put("maximum", max);
        return m;
    }

    /**
     * 整数属性片段（仅下界，无上界；用于 PID 等天然正整数参数）。
     * @param desc 参数中文说明
     * @param min  最小值（闭区间）
     * @return 带 minimum 的 integer Schema 片段
     */
    private static Map<String, Object> integer(String desc, long min) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "integer"); m.put("description", desc);
        m.put("minimum", min);
        return m;
    }

    /**
     * 数字属性片段（允许小数），可声明上下界。
     * @param desc 参数中文说明
     * @param min  最小值（null 表示不限制下界）
     * @param max  最大值（null 表示不限制上界）
     * @return 按需带 minimum/maximum 的 number Schema 片段
     */
    private static Map<String, Object> number(String desc, Double min, Double max) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "number"); m.put("description", desc);
        if (min != null) m.put("minimum", min);
        if (max != null) m.put("maximum", max);
        return m;
    }

    /** 枚举字符串属性片段。 */
    private static Map<String, Object> enums(String desc, String... values) {
        Map<String, Object> m = str(desc);
        m.put("enum", List.of(values));
        return m;
    }

    /**
     * 数组属性片段（无长度约束）。
     * @param desc  参数中文说明
     * @param items 元素 Schema 片段
     * @return array Schema 片段
     */
    private static Map<String, Object> arr(String desc, Map<String, Object> items) {
        return arr(desc, items, null, null);
    }

    /**
     * 数组属性片段（可声明元素数量上下界）。
     * @param desc     参数中文说明
     * @param items    元素 Schema 片段
     * @param minItems 最少元素数（null 不限制），闭区间
     * @param maxItems 最多元素数（null 不限制），闭区间；取自执行代码的截断上限
     * @return 带 minItems/maxItems 的 array Schema 片段
     */
    private static Map<String, Object> arr(String desc, Map<String, Object> items,
                                           Integer minItems, Integer maxItems) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "array"); m.put("description", desc); m.put("items", items);
        if (minItems != null) m.put("minItems", minItems);
        if (maxItems != null) m.put("maxItems", maxItems);
        return m;
    }

    /**
     * 向 object 片段挂载一个属性。
     * @param obj  object 类型 Schema 片段
     * @param name 属性名
     * @param req  是否必填
     * @param sub  属性 Schema 片段
     */
    @SuppressWarnings("unchecked")
    private static void prop(Map<String, Object> obj, String name, boolean req, Map<String, Object> sub) {
        ((Map<String, Object>) obj.get("properties")).put(name, sub);
        if (req) ((List<String>) obj.get("required")).add(name);
    }

    /**
     * 根级工具参数 Schema 的流式构建器：链式调用 req/opt 声明参数，build 产出 Schema。
     */
    private static final class B {
        private final Map<String, Object> schema = objectFragment("");

        /** 声明必填参数。 */
        B req(String name, Map<String, Object> sub) { prop(schema, name, true, sub); return this; }

        /** 声明可选参数。 */
        B opt(String name, Map<String, Object> sub) { prop(schema, name, false, sub); return this; }

        /** 产出不可变语义的 Schema Map（构建完毕后不再修改）。 */
        Map<String, Object> build() { return schema; }
    }

    // ============================ 全部工具参数定义 ============================
    // 参数名、类型、必填项与 Tools/Office/ReminderScheduler 执行代码中 a.get(...) 完全对齐；
    // 数值/数组长度边界与执行代码的 clamp、截断逻辑一致（如 read_file 1024~500000、slides ≤200）。

    /**
     * 构建工具名到参数 Schema 的注册表。
     * @return 有序注册表（仅含带参工具；无参工具由 {@link #of(String)} 兜底空对象）
     */
    private static Map<String, Map<String, Object>> buildRegistry() {
        Map<String, Map<String, Object>> r = new LinkedHashMap<>();

        // ---- 文件系统 ----
        r.put("list_directory", new B().req("path", str("要列出的目录绝对路径")).build());
        r.put("get_file_info", new B().req("path", str("文件或目录的绝对路径")).build());
        r.put("read_file", new B()
                .req("path", str("要读取文件的绝对路径（UTF-8 文本，50MB 以内）"))
                .opt("limit", integer("最多返回的字节数，默认 32000，允许 1024~500000", 1024, 500000))
                .build());
        r.put("write_file", new B()
                .req("path", str("目标文件绝对路径（不存在则创建，存在则覆盖）"))
                .req("content", str("写入的完整文本内容（UTF-8，单次最多 10MB）"))
                .build());
        r.put("create_directory", new B().req("path", str("要创建的目录绝对路径（含父目录）")).build());
        r.put("copy_file", new B()
                .req("source", str("源文件绝对路径"))
                .req("target", str("目标文件绝对路径（父目录不存在会自动创建，已存在则覆盖）"))
                .build());
        r.put("move_file", new B()
                .req("source", str("源文件绝对路径"))
                .req("target", str("目标文件绝对路径（已存在则覆盖）"))
                .build());
        r.put("delete_file", new B().req("path", str("要永久删除的文件绝对路径（不可恢复）")).build());
        r.put("delete_directory", new B().req("path", str("要永久删除的目录绝对路径（递归删除，不可恢复）")).build());

        // ---- 命令与打开 ----
        r.put("run_command", new B()
                .req("program", str("可执行程序名或绝对路径；cmd/powershell 危险用法会被安全机制阻止"))
                .opt("args", arr("命令行参数数组，每个元素一个参数（不要拼成单个字符串）", str("单个命令行参数")))
                .opt("cwd", str("工作目录绝对路径，默认用户主目录"))
                .opt("timeout", integer("超时毫秒数，默认 120000，允许 1~600000（≤0 会立即超时）", 1, 600000))
                .build());
        r.put("open_in_explorer", new B().req("path", str("要在资源管理器中定位的文件/目录绝对路径")).build());
        r.put("open_file", new B().req("path", str("要用系统默认程序打开的文件绝对路径")).build());
        r.put("open_app", new B()
                .opt("path", str("要打开程序的绝对路径；与 name 二选一，提供 path 时优先使用"))
                .opt("name", enums("常用应用名（与 path 二选一）；不支持命令提示符/终端",
                        "notepad", "记事本", "calc", "计算器", "mspaint", "画图",
                        "taskmgr", "任务管理器", "explorer", "资源管理器",
                        "control", "控制面板", "write", "写字板", "winver"))
                .build());

        // ---- 屏幕与图像 ----
        r.put("take_screenshot", new B()
                .opt("path", str("截图保存的 PNG 绝对路径；不传则存到 图片/本机助手截图 目录"))
                .build());
        r.put("analyze_image", new B()
                .req("path", str("本地图片绝对路径（需视觉模型支持）"))
                .req("question", str("针对图片要问的问题"))
                .build());

        // ---- 进程与软件 ----
        r.put("list_processes", new B()
                .opt("limit", integer("返回进程数量，默认 15，允许 1~50", 1, 50))
                .build());
        r.put("kill_process", new B()
                .opt("pid", integer("要结束的进程 PID（正整数）；与 name 二选一，提供 pid 时优先使用", 1))
                .opt("name", str("要结束的进程名（仅限字母/数字/下划线/连字符/空格）；与 pid 二选一"))
                .build());
        r.put("list_installed_apps", new B()
                .opt("keyword", str("按软件显示名过滤的关键词，不传则列出全部"))
                .opt("limit", integer("最多返回条数，默认 80，允许 1~200", 1, 200))
                .build());

        // ---- 搜索 ----
        r.put("search_files", new B()
                .req("name", str("文件名通配符模式，支持 * 和 ?，如 *.pdf"))
                .opt("root", str("搜索根目录绝对路径，默认用户主目录（递归深度 8 层）"))
                .opt("limit", integer("最多返回条数，默认 50，允许 1~500", 1, 500))
                .build());

        // ---- Office 文档 ----
        r.put("create_ppt", new B()
                .req("path", str("输出 .pptx 文件绝对路径（必须以 .pptx 结尾）"))
                .req("slides", arr("幻灯片页列表（顺序即播放顺序，至少 1 页，最多 200 页）", slideSchema(), 1, 200))
                .build());
        r.put("create_docx", new B()
                .req("path", str("输出 .docx 文件绝对路径（必须以 .docx 结尾）"))
                .opt("title", str("文档标题（加粗居中大标题）"))
                .opt("paragraphs", arr("正文段落列表", paragraphSchema()))
                .build());
        r.put("create_xlsx", new B()
                .req("path", str("输出 .xlsx 文件绝对路径（必须以 .xlsx 结尾）"))
                .opt("sheets", arr("工作表列表；不传时生成一个示例工作表", sheetSchema()))
                .build());
        r.put("read_office", new B().req("path", str("要读取的 .docx/.xlsx/.pptx 文件绝对路径（50MB 以内）")).build());
        r.put("edit_docx", new B()
                .req("path", str("要修改的已有 .docx 文件绝对路径（原文件自动备份为同目录 .bak）"))
                .opt("paragraphs", arr("追加到文末的段落列表（插在分节符之前）", paragraphSchema()))
                .opt("find", str("要查找的原文（字面匹配，仅限同一文本节点内，不支持正则；与 replace 成对使用）"))
                .opt("replace", str("替换后的文本（与 find 成对使用；替换为危险内容会被拦截）"))
                .build());
        r.put("edit_xlsx", new B()
                .req("path", str("要修改的已有 .xlsx 文件绝对路径（原文件自动备份为同目录 .bak）"))
                .opt("sheet", str("目标工作表名称；不传时修改第一个工作表"))
                .opt("appendRows", arr("追加到工作表末尾的二维数据（每个元素是一行，行内每个元素是一个单元格，不要以 =/+/-/@ 开头）",
                        arr("一行的单元格数组", str("单元格文本"))))
                .opt("cells", arr("按 A1 引用写入/覆盖的单元格列表", cellWriteSchema()))
                .build());
        r.put("edit_ppt", new B()
                .req("path", str("要修改的已有 .pptx 文件绝对路径（原文件自动备份为同目录 .bak）"))
                .req("slides", arr("追加到演示文稿末尾的幻灯片列表（1~200 页）", slideSchema(), 1, 200))
                .build());

        // ---- 知识库 / 记忆 ----
        r.put("search_knowledge", new B()
                .req("query", str("检索关键词或问题（BM25 匹配）"))
                .opt("limit", integer("返回片段数量，默认 5，允许 1~20", 1, 20))
                .build());
        r.put("remember", new B()
                .req("text", str("用户明确要求长期记住的信息原文"))
                .opt("category", str("记忆分类，如 偏好/事实/任务；不传则默认分类"))
                .build());
        r.put("forget", new B().req("id", str("要删除的记忆 ID（recall 返回条目中的标识）")).build());
        r.put("recall", new B()
                .req("query", str("搜索记忆的关键词或问题"))
                .opt("limit", integer("返回条数，默认 10，允许 1~20", 1, 20))
                .build());

        // ---- 提醒 ----
        r.put("schedule_reminder", new B()
                .req("text", str("提醒内容"))
                .opt("at", str("ISO 格式触发时间（如 2026-09-20T09:00 或带时区 2026-09-20T09:00+08:00）；与 delayMinutes 二选一"))
                .opt("delayMinutes", number("延迟多少分钟后触发（支持小数，必须为非负数；0 或过去时间会被拒绝）；与 at 二选一", 0.0, null))
                .opt("repeat", enums("重复方式，默认 once", "once", "hourly", "daily", "weekly"))
                .build());
        r.put("cancel_reminder", new B().req("id", str("要取消的提醒 ID（list_reminders 返回）")).build());

        // ---- 语音 ----
        r.put("speak", new B().req("text", str("要离线朗读的文本内容")).build());

        return r;
    }

    /** create_ppt 的 slides 数组元素：{title, bullets?}。 */
    private static Map<String, Object> slideSchema() {
        Map<String, Object> s = objectFragment("单张幻灯片");
        prop(s, "title", true, str("幻灯片标题"));
        prop(s, "bullets", false, arr("该页要点列表（每行一条）", str("要点文本")));
        return s;
    }

    /** create_docx 的 paragraphs 数组元素：{text, type?}。 */
    private static Map<String, Object> paragraphSchema() {
        Map<String, Object> s = objectFragment("文档段落");
        prop(s, "text", true, str("段落文本"));
        prop(s, "type", false, enums("段落类型，默认 para", "para", "heading1", "title"));
        return s;
    }

    /** create_xlsx 的 sheets 数组元素：{name?, rows}（行内单元格统一按字符串给出）。 */
    private static Map<String, Object> sheetSchema() {
        Map<String, Object> s = objectFragment("工作表");
        prop(s, "name", false, str("工作表名称（标签页名）"));
        prop(s, "rows", true, arr("二维表格数据，每个元素是一行；行内每个元素是一个单元格文本（不要以 =/+/-/@ 开头）",
                arr("一行的单元格数组", str("单元格文本"))));
        return s;
    }

    /** edit_xlsx 的 cells 数组元素：{ref, value}，按 A1 引用定点写入。 */
    private static Map<String, Object> cellWriteSchema() {
        Map<String, Object> s = objectFragment("单元格定点写入");
        prop(s, "ref", true, str("单元格引用（列字母+行号），如 B3、AA12"));
        prop(s, "value", true, str("要写入的文本或数字；不要以 =/+/-/@ 开头（防公式注入）"));
        return s;
    }
}
